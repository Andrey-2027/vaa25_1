package org.ipro.telemetry.core;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.hibernate.Hibernate;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Замер времени вызовов сервисного слоя (основной перехват) и методов,
 * помеченных @Measured (opt-in вне сервисов). Создаёт фреймы в
 * OperationContext; для корневого фрейма происходит финализация операции.
 * <p>
 * Известное ограничение Spring AOP: self-invocation (this.b()) минует
 * прокси — фрейм для b не создаётся, итоги ложатся на внешний фрейм.
 * <p>
 * Порядок: @Order(1) — транзакционный advice приложения зарегистрирован
 * с order=0 (см. @EnableTransactionManagement в Application), поэтому этот
 * аспект гарантированно выполняется ВНУТРИ транзакционной границы:
 * снимок сущности (EntitySnapshot) делается при открытой Hibernate-сессии.
 */
@Aspect
@Component
@Order(1)
public class ExecutionTimeAspect {

    private static final int MAX_SNAPSHOT_CHARS = 16_384;

    private final OperationContext operationContext;
    private final boolean entityDataEnabled;

    public ExecutionTimeAspect(OperationContext operationContext, boolean entityDataEnabled) {
        this.operationContext = operationContext;
        this.entityDataEnabled = entityDataEnabled;
    }

    @Pointcut("execution(* org.ip.service..*(..))")
    void serviceLayer() {
    }

    @Pointcut("@annotation(org.ipro.telemetry.api.Measured)")
    void measured() {
    }

    @Around("serviceLayer() || measured()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        String name = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        Frame frame = operationContext.beginFrame(name);
        if (frame == null) {
            return pjp.proceed();
        }
        try {
            captureEntityContext(pjp.getArgs());
        } catch (RuntimeException e) {
            // снимок не должен ломать бизнес-вызов
        }
        Throwable failure = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            operationContext.endFrame(frame, frame.elapsedNanos(), failure);
        }
    }

    /**
     * Из аргументов метода (например save(entity) или replaceAll(parent, rows))
     * извлекает сущность и её id (через рефлексию getId) и кладёт в контекст
     * операции — виден в trace-файле и в entity/entity_id события. Пропускает
     * примитивы, коллекции и объекты фреймворков; не перезаписывает уже заданный
     * контекст (например entityId из openItemForm).
     * <p>
     * Строки табличной части (второй аргумент-коллекция, напр. replaceAll):
     * табличные части живут in-memory в форме и не являются полем шапки —
     * снимок строится вместе с ними (EntitySnapshot.renderWithRows). Если строки
     * присутствуют, снимок обновляется даже поверх ранее зафиксированного
     * (save(шапка) идёт раньше replaceAll в одной операции).
     */
    private void captureEntityContext(Object[] args) {
        Operation operation = operationContext.currentOperation();
        if (operation == null) {
            return;
        }
        Object entityArg = null;
        Collection<?> rowsArg = null;
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            if (entityArg == null && isEntityLike(arg)) {
                entityArg = arg;
            } else if (rowsArg == null && arg instanceof Collection<?> collection
                    && isEntityRows(collection)) {
                rowsArg = collection;
            }
            if (entityArg != null && rowsArg != null) {
                break;
            }
        }
        if (entityArg == null) {
            return;
        }
        if (operation.getContextValue(MdcKeys.ENTITY_ID) == null) {
            Object id = findId(entityArg);
            if (id != null) {
                operationContext.putContext(MdcKeys.ENTITY, entityArg.getClass().getSimpleName());
                operationContext.putContext(MdcKeys.ENTITY_ID, id.toString());
            }
        }
        if (entityDataEnabled
                && (operation.getContextValue(MdcKeys.ENTITY_DATA) == null || rowsArg != null)) {
            String snapshot = rowsArg == null
                    ? EntitySnapshot.render(entityArg, MAX_SNAPSHOT_CHARS)
                    : EntitySnapshot.renderWithRows(entityArg, rowsArg, MAX_SNAPSHOT_CHARS);
            if (snapshot != null) {
                operationContext.putEntityData(snapshot);
            }
        }
    }

    /** Коллекция — строки табличной части: элементы entity-подобны. */
    private static boolean isEntityRows(Collection<?> collection) {
        if (!Hibernate.isInitialized(collection)) {
            return false;
        }
        for (Object element : collection) {
            if (element == null) {
                continue;
            }
            return isEntityLike(element);
        }
        return false;
    }

    private static boolean isEntityLike(Object arg) {
        if (arg instanceof CharSequence || arg instanceof Number || arg instanceof Boolean
                || arg instanceof Character || arg instanceof Iterable || arg instanceof Map
                || arg instanceof Optional) {
            return false;
        }
        Class<?> type = arg.getClass();
        if (type.isArray() || type.isEnum() || type.isAnnotation()) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jakarta.")
                || name.startsWith("org.springframework.") || name.startsWith("org.hibernate.")
                || name.startsWith("com.vaadin.") || name.startsWith("org.vaadin.")) {
            return false;
        }
        return hasNoArgMethod(type, "getId");
    }

    private static Object findId(Object arg) {
        try {
            Method getId = arg.getClass().getMethod("getId");
            return getId.invoke(arg);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static boolean hasNoArgMethod(Class<?> type, String methodName) {
        try {
            type.getMethod(methodName);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}