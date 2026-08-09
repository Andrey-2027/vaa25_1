package org.ipro.telemetry.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.hibernate.Hibernate;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PreDeleteEvent;
import org.hibernate.event.spi.PreDeleteEventListener;
import org.hibernate.event.spi.PreInsertEvent;
import org.hibernate.event.spi.PreInsertEventListener;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Field-level аудит (этап 10): захват изменений сущностей на событиях
 * Hibernate flush'а. Регистрируется Hibernate'ом (Integrator, без Spring),
 * конфигурация и контекст — через {@link FieldAuditBridge} и
 * {@link SqlTimingBridge}.
 * <p>
 * Скалярные поля: {@code oldState[]/state[]} из события по индексам
 * {@code persister.getPropertyNames()} — Hibernate уже вычислил изменения
 * (dirty checking), ничего грузить из БД не нужно.
 * <p>
 * Строки табличных частей (@TableSectionMetadata) диффятся теми же событиями
 * на row-сущности и агрегируются в сводку родителя (added/removed/changed) —
 * единая модель для обеих схем (PrdSpec @OneToMany и ReceivingDocument
 * с отдельными сущностями строк). Дубликаты строк в сводку не попадают
 * (ключ накопителя — entity#id, тип DELETE имеет приоритет).
 * <p>
 * INSERT фиксируется на PostInsertEvent, а не PreInsert: при identity-колонках
 * (bigserial, как в этой БД) id в PreInsertEvent ещё null, а журнал обязан
 * иметь entity_id в каждой строке.
 * <p>
 * Изменения копятся в аккумуляторах операции ({@link Operation#fieldAudit})
 * и персистятся в {@code entity_change_log} при завершении операции
 * (FieldAuditOperationHandler). Вне операции (raw JPA вне сервисного слоя) —
 * immediate durable-запись.
 */
public final class FieldAuditListener
        implements PostInsertEventListener, PreInsertEventListener,
        PreUpdateEventListener, PreDeleteEventListener {

    private static final Logger log = LoggerFactory.getLogger("ipro.telemetry.field-audit");

    /** Имя аннотации табличных частей — по имени, без зависимости от домена (паттерн EntitySnapshot). */
    private static final String TABLE_SECTION_METADATA =
            "org.ip.metadata.annotation.TableSectionMetadata";
    private static final int MAX_VALUE_CHARS = 200;

    @Override
    public boolean onPreInsert(PreInsertEvent event) {
        // INSERT фиксируется на PostInsertEvent: identity-генерация даёт null id
        // в PreInsertEvent, а строка журнала обязана иметь entity_id.
        return false;
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        handle(event, "INSERT", null, event.getState(),
                event.getPersister().getPropertyNames());
    }

    @Override
    public boolean onPreUpdate(PreUpdateEvent event) {
        handle(event, "UPDATE", event.getOldState(), event.getState(),
                event.getPersister().getPropertyNames());
        return false;
    }

    @Override
    public boolean onPreDelete(PreDeleteEvent event) {
        handle(event, "DELETE", event.getDeletedState(), null,
                event.getPersister().getPropertyNames());
        return false;
    }

    private void handle(org.hibernate.event.spi.AbstractDatabaseOperationEvent event,
                        String type, Object[] oldState, Object[] state, String[] propertyNames) {
        if (!TelemetryGuard.isEnabled() || TelemetryGuard.isInsideLogging()) {
            return;
        }
        Object entity = event.getEntity();
        if (entity == null) {
            return;
        }
        Class<?> entityClass = Hibernate.getClass(entity);
        String entityName = entityClass.getSimpleName();
        if (!FieldAuditBridge.isAudited(entityClass, entityName)) {
            return;
        }
        try {
            ParentRef parent = parentRef(entity);
            if (parent != null) {
                if (FieldAuditBridge.isAudited(parent.entityClass, parent.entityName)) {
                    recordSection(parent, entityName, type);
                }
                return;
            }
            Object id = event.getId();
            if (id == null) {
                return;
            }
            recordScalars(entityName, String.valueOf(id), type, oldState, state, propertyNames);
        } catch (RuntimeException e) {
            // аудит не должен ронять бизнес-flush
            log.warn("field audit capture failed for {}: {}", entityName, e.toString());
        }
    }

    // ---------------------------------------------------------- скалярные поля

    private void recordScalars(String entityName, String entityId, String type,
                               Object[] oldState, Object[] state, String[] propertyNames) {
        List<FieldChange> changes = diffFields(type, oldState, state, propertyNames);
        if (changes.isEmpty()) {
            return;
        }
        Operation operation = SqlTimingBridge.currentOperation();
        if (operation != null) {
            FieldAuditAccumulator acc = operation.fieldAudit(entityName, entityId);
            acc.setType(type);
            for (FieldChange change : changes) {
                acc.addScalar(change);
            }
        } else {
            FieldAuditAccumulator acc = new FieldAuditAccumulator(entityName, entityId);
            acc.setType(type);
            for (FieldChange change : changes) {
                acc.addScalar(change);
            }
            emitDurable(acc);
        }
    }

    /** Отличия oldState/state по именам свойств (redaction применяется сразу). */
    private List<FieldChange> diffFields(String type, Object[] oldState, Object[] state,
                                         String[] propertyNames) {
        List<FieldChange> changes = new ArrayList<>();
        for (int i = 0; i < propertyNames.length; i++) {
            String field = propertyNames[i];
            Object oldValue = oldState == null ? null : oldState[i];
            Object newValue = state == null ? null : state[i];
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            if (FieldAuditBridge.isRedacted(field)) {
                changes.add(new FieldChange(field, "***", "***"));
            } else {
                changes.add(new FieldChange(field, render(oldValue), render(newValue)));
            }
        }
        return changes;
    }

    // --------------------------------------------------- табличные части (строки)

    private void recordSection(ParentRef parent, String rowName, String type) {
        Operation operation = SqlTimingBridge.currentOperation();
        if (operation != null) {
            FieldAuditAccumulator acc = operation.fieldAudit(parent.entityName, parent.entityId);
            acc.setType("UPDATE");
            acc.addSection(rowName, type);
        } else {
            FieldAuditAccumulator acc = new FieldAuditAccumulator(parent.entityName, parent.entityId);
            acc.setType("UPDATE");
            acc.addSection(rowName, type);
            emitDurable(acc);
        }
    }

    // ---------------------------------------------------------------- утилиты

    private static void emitDurable(FieldAuditAccumulator acc) {
        if (acc.isEmpty()) {
            return;
        }
        try {
            TelemetryBridge.getSink().acceptFieldChangeDurable(acc.toRecord());
        } catch (RuntimeException e) {
            log.warn("field audit write failed: {}", e.toString());
        }
    }

    /** Родитель строки табличной части (по @TableSectionMetadata.parentField) или null. */
    private static ParentRef parentRef(Object entity) {
        Class<?> type = Hibernate.getClass(entity);
        Annotation annotation = annotationByClassName(type, TABLE_SECTION_METADATA);
        if (annotation == null) {
            return null;
        }
        String parentField = annotationString(annotation, "parentField");
        if (parentField == null || parentField.isBlank()) {
            return null;
        }
        Object parent = invokeGetter(entity, parentField);
        if (parent == null) {
            return null;
        }
        Object parentId = findId(parent);
        if (parentId == null) {
            return null;
        }
        return new ParentRef(Hibernate.getClass(parent).getSimpleName(),
                String.valueOf(parentId), Hibernate.getClass(parent));
    }

    private static Annotation annotationByClassName(Class<?> type, String annotationClassName) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> annotationClass =
                    (Class<? extends Annotation>) Class.forName(annotationClassName);
            return type.getAnnotation(annotationClass);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String annotationString(Annotation annotation, String methodName) {
        try {
            Method method = annotation.getClass().getMethod(methodName);
            return String.valueOf(method.invoke(annotation));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private static Object invokeGetter(Object target, String fieldName) {
        String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
            Method method = target.getClass().getMethod(getterName);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Object findId(Object value) {
        try {
            Method getId = value.getClass().getMethod("getId");
            return getId.invoke(value);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Строковое представление значения для payload: скаляры — как есть,
     * сущности/прокси — "DisplayName (#id)" для инициализированных ссылок,
     * иначе "Class#id" (без порождения SQL), массивы — длина,
     * коллекции/карты — маркер (в состояние не попадают).
     */
    private static String render(Object value) {
        if (value == null) {
            return null;
        }
        if (value.getClass().isArray()) {
            return "[" + Array.getLength(value) + "]";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum
                || value instanceof java.time.temporal.TemporalAccessor) {
            String s = String.valueOf(value);
            return s.length() <= MAX_VALUE_CHARS ? s : s.substring(0, MAX_VALUE_CHARS) + "...";
        }
        if (value instanceof Collection<?> || value instanceof Map) {
            return "<" + value.getClass().getSimpleName() + ">";
        }
        try {
            Object id = findId(value);
            String name = EntitySnapshot.displayNameOf(value);
            if (name != null) {
                return name + " (#" + (id == null ? "null" : id) + ")";
            }
            return Hibernate.getClass(value).getSimpleName() + "#" + (id == null ? "null" : id);
        } catch (RuntimeException e) {
            String s = String.valueOf(value);
            return s.length() <= MAX_VALUE_CHARS ? s : s.substring(0, MAX_VALUE_CHARS) + "...";
        }
    }

    /** Ссылка на родителя строки табличной части. */
    record ParentRef(String entityName, String entityId, Class<?> entityClass) {
    }
}
