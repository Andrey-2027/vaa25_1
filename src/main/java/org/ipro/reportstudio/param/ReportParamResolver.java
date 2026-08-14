package org.ipro.reportstudio.param;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Резолвинг значений параметров отчёта при запуске (Фаза 3). Для каждого
 * параметра шаблона источник значения выбирается по valueSource:
 * <ul>
 * <li>FORM — значение с формы запуска ({@code formValues});</li>
 * <li>DEFAULT — константа из {@code defaultValue} (JSON-представление);</li>
 * <li>COMPUTED — предвычисленное платформой значение: NOW (момент из контекста,
 *     фиксированный один раз), CURRENT_USER (имя пользователя), RLS_ORG
 *     (единственная доступная организация по измерению rls-org-dimension);</li>
 * <li>CONTEXT — из {@link ReportContext} (документ/грид), сопоставление по
 *     {@link ReportContext#matches} (isAssignableFrom).</li>
 * </ul>
 * PERIOD резолвится в два бинд-имени {@code nameFrom}/{@code nameTo}.
 * Сущностные значения (ENTITY/ENTITY_LIST) перезапрашиваются через
 * {@link EntityParamRefresher} — биндится свежий инстанс под активным RLS;
 * «не найдено / недоступно» — жёсткое прерывание (имя параметра + id; для
 * списка — какой элемент не прошёл).
 */
public class ReportParamResolver {

    private final EntityParamRefresher refresher;
    private final AccessService accessService;
    private final RlsCurrentUser currentUser;
    private final SessionFactoryImplementor sessionFactory;
    private final jakarta.persistence.PersistenceUnitUtil persistenceUnitUtil;
    private final ObjectMapper objectMapper;
    private final String rlsOrgDimension;

    public ReportParamResolver(EntityParamRefresher refresher,
                               AccessService accessService,
                               RlsCurrentUser currentUser,
                               SessionFactoryImplementor sessionFactory,
                               ObjectMapper objectMapper,
                               String rlsOrgDimension) {
        this.refresher = refresher;
        this.accessService = accessService;
        this.currentUser = currentUser;
        this.sessionFactory = sessionFactory;
        this.persistenceUnitUtil = sessionFactory.getPersistenceUnitUtil();
        this.objectMapper = objectMapper;
        this.rlsOrgDimension = rlsOrgDimension;
    }

    /**
     * @param params      параметры шаблона (порядок не важен)
     * @param context     контекст запуска (момент, пользователь, документ/выборка)
     * @param formValues  значения, введённые на форме (ключи — имена параметров;
     *                    для PERIOD — {@code nameFrom}/{@code nameTo})
     */
    public ResolvedParams resolve(List<ReportParam> params, ReportContext context,
                                  Map<String, Object> formValues) {
        ResolvedParams.Builder out = ResolvedParams.builder();
        if (params != null) {
            for (ReportParam param : params) {
                resolveParam(param, context, formValues, out);
            }
        }
        return out.build();
    }

    private void resolveParam(ReportParam param, ReportContext context,
                              Map<String, Object> formValues, ResolvedParams.Builder out) {
        switch (param.getKind()) {
            case SCALAR -> resolveScalar(param, context, formValues, out);
            case PERIOD -> resolvePeriod(param, context, formValues, out);
            case ENTITY, ENTITY_LIST -> resolveEntity(param, context, formValues, out);
            default -> out.error("Параметр :" + param.getName() + ": неизвестный вид "
                + param.getKind());
        }
    }

    // === SCALAR ===

    private void resolveScalar(ReportParam param, ReportContext context,
                               Map<String, Object> formValues, ResolvedParams.Builder out) {
        switch (param.getValueSource()) {
            case FORM -> {
                Object value = rawFormValue(formValues, param.getName());
                if (isEmpty(value)) {
                    requireFilled(param, out);
                    return;
                }
                out.bind(param.getName(), value);
            }
            case DEFAULT -> {
                Object value = decodeJson(param.getName(), param.getDefaultValue(), out);
                if (isEmpty(value)) {
                    requireFilled(param, out);
                    return;
                }
                out.bind(param.getName(), value);
            }
            case COMPUTED -> {
                Object value = computedValue(param, context, out);
                if (value == null) {
                    requireFilled(param, out);
                    return;
                }
                out.bind(param.getName(), value);
            }
            case CONTEXT -> out.error("Параметр :" + param.getName()
                + ": источник CONTEXT применим только к сущностным параметрам (kind=ENTITY/ENTITY_LIST)");
        }
    }

    // === PERIOD: два бинд-имени nameFrom/nameTo ===

    private void resolvePeriod(ReportParam param, ReportContext context,
                               Map<String, Object> formValues, ResolvedParams.Builder out) {
        String fromName = param.getName() + "From";
        String toName = param.getName() + "To";
        Object from = null;
        Object to = null;

        switch (param.getValueSource()) {
            case FORM -> {
                from = rawFormValue(formValues, fromName);
                to = rawFormValue(formValues, toName);
            }
            case DEFAULT -> {
                List<Object> pair = decodeJsonArray(param.getName(), param.getDefaultValue(), out);
                if (pair != null && !pair.isEmpty()) {
                    from = pair.size() > 0 ? pair.get(0) : null;
                    to = pair.size() > 1 ? pair.get(1) : null;
                }
            }
            case COMPUTED -> {
                if (param.getComputed() == ReportComputedValue.NOW) {
                    from = context.now();
                    to = context.now();
                } else {
                    out.error("Параметр :" + param.getName()
                        + ": COMPUTED для PERIOD поддерживает только NOW (момент из контекста)");
                    return;
                }
            }
            case CONTEXT -> {
                out.error("Параметр :" + param.getName()
                    + ": источник CONTEXT для PERIOD не поддерживается");
                return;
            }
        }

        if (from == null && to == null) {
            if (param.isRequired()) {
                out.error("Параметр :" + param.getName() + " — обязательный (период не задан)");
            }
            return;
        }
        if (from != null) {
            out.bind(fromName, from);
        }
        if (to != null) {
            out.bind(toName, to);
        }
    }

    // === ENTITY / ENTITY_LIST: перезапрос по id с RLS ===

    private void resolveEntity(ReportParam param, ReportContext context,
                               Map<String, Object> formValues, ResolvedParams.Builder out) {
        Class<?> entityClass = entityClassOf(param, out);
        if (entityClass == null) {
            return;
        }
        if (!isJpaEntity(entityClass)) {
            out.error("Параметр :" + param.getName() + ": класс " + entityClass.getName()
                + " не является JPA-сущностью");
            return;
        }

        List<Object> ids = null;
        switch (param.getValueSource()) {
            case FORM -> ids = formIds(param, formValues, out);
            case CONTEXT -> {
                if (!context.matches(entityClass)) {
                    if (param.isRequired()) {
                        out.error("Параметр :" + param.getName() + " — контекст запуска не предоставляет "
                            + entityClass.getSimpleName() + " (ожидался контекст совместимого класса)");
                    }
                    return;
                }
                ids = contextIds(param, context, out);
            }
            case DEFAULT -> {
                if (param.getKind() == ReportParamKind.ENTITY_LIST) {
                    out.error("Параметр :" + param.getName()
                        + ": DEFAULT для ENTITY_LIST не поддерживается — укажите список в контексте или на форме");
                    return;
                }
                Object id = decodeJson(param.getName(), param.getDefaultValue(), out);
                ids = id == null ? List.of() : List.of(id);
            }
            case COMPUTED -> {
                out.error("Параметр :" + param.getName()
                    + ": COMPUTED для сущностных параметров не поддерживается (только SCALAR/PERIOD)");
                return;
            }
        }

        if (ids == null || ids.isEmpty()) {
            if (param.isRequired()) {
                requireFilled(param, out);
            }
            return;
        }

        if (param.getKind() == ReportParamKind.ENTITY) {
            Object entity = refresher.refresh(entityClass, ids.get(0));
            if (entity == null) {
                out.error("Параметр :" + param.getName() + " — сущность " + entityClass.getSimpleName()
                    + " по id=" + ids.get(0) + " не найдена или недоступна (RLS)");
                return;
            }
            out.bind(param.getName(), entity);
        } else {
            List<Object> entities = new ArrayList<>(ids.size());
            for (Object id : ids) {
                Object entity = refresher.refresh(entityClass, id);
                if (entity == null) {
                    out.error("Параметр :" + param.getName() + " — элемент списка " + entityClass.getSimpleName()
                        + " по id=" + id + " не найден или недоступен (RLS)");
                    return;
                }
                entities.add(entity);
            }
            out.bind(param.getName(), entities);
        }
    }

    // === Вспомогательные ===

    private void requireFilled(ReportParam param, ResolvedParams.Builder out) {
        if (param.isRequired()) {
            out.error("Параметр :" + param.getName() + " — обязательный параметр не заполнен");
        }
    }

    private static Object rawFormValue(Map<String, Object> formValues, String name) {
        if (formValues == null) {
            return null;
        }
        return formValues.get(name);
    }

    private static boolean isEmpty(Object value) {
        return value == null
            || (value instanceof String s && s.isBlank());
    }

    /** id сущностного параметра с формы: Number/String или коллекция/массив для ENTITY_LIST. */
    private List<Object> formIds(ReportParam param, Map<String, Object> formValues,
                                 ResolvedParams.Builder out) {
        Object raw = rawFormValue(formValues, param.getName());
        if (raw == null) {
            return List.of();
        }
        if (param.getKind() == ReportParamKind.ENTITY) {
            Object id = parseId(param, raw, out);
            return id == null ? List.of() : List.of(id);
        }
        List<Object> ids = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                Object id = parseId(param, item, out);
                if (id != null) {
                    ids.add(id);
                }
            }
        } else if (raw instanceof Object[] array) {
            for (Object item : array) {
                Object id = parseId(param, item, out);
                if (id != null) {
                    ids.add(id);
                }
            }
        } else {
            Object id = parseId(param, raw, out);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Object parseId(ReportParam param, Object raw, ResolvedParams.Builder out) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException notAnId) {
                out.error("Параметр :" + param.getName() + " — id «" + text
                    + "» не является числом");
                return null;
            }
        }
        Object id = persistenceUnitUtil.getIdentifier(raw);
        if (id != null) {
            return id instanceof Number number ? number.longValue() : id;
        }
        out.error("Параметр :" + param.getName() + " — значение id должно быть числом, получено: "
            + raw.getClass().getSimpleName());
        return null;
    }

    private static List<Object> contextIds(ReportParam param, ReportContext context,
                                           ResolvedParams.Builder out) {
        if (param.getKind() == ReportParamKind.ENTITY) {
            return context.entityId() == null ? List.of() : List.of(context.entityId());
        }
        if (context.selectedIds() != null && !context.selectedIds().isEmpty()) {
            return new ArrayList<>(context.selectedIds());
        }
        return context.entityId() == null ? List.of() : List.of(context.entityId());
    }

    private Object computedValue(ReportParam param, ReportContext context,
                                 ResolvedParams.Builder out) {
        switch (param.getComputed()) {
            case NOW -> {
                return context.now();
            }
            case CURRENT_USER -> {
                String user = context.user() != null ? context.user() : currentUser.username();
                if (user == null || user.isBlank()) {
                    out.error("Параметр :" + param.getName() + " — нет текущего пользователя");
                    return null;
                }
                return user;
            }
            case RLS_ORG -> {
                String user = context.user() != null ? context.user() : currentUser.username();
                List<Long> readableIds = accessService.getReadableIds(rlsOrgDimension, user);
                if (readableIds == null) {
                    out.error("Параметр :" + param.getName() + " — доступ по измерению «"
                        + rlsOrgDimension + "» без ограничений, организация неоднозначна");
                    return null;
                }
                if (readableIds.size() == 1
                    && readableIds.get(0) != AccessService.NO_ACCESS_SENTINEL) {
                    return readableIds.get(0);
                }
                out.error("Параметр :" + param.getName() + " — по измерению «" + rlsOrgDimension
                    + "» у пользователя нет единственной организации (доступных значений: "
                    + readableIds.size() + ")");
                return null;
            }
            default -> {
                out.error("Параметр :" + param.getName() + " — COMPUTED без заданного значения (computed=NONE)");
                return null;
            }
        }
    }

    private Class<?> entityClassOf(ReportParam param, ResolvedParams.Builder out) {
        String className = param.getEntityClass();
        if (className == null || className.isBlank()) {
            out.error("Параметр :" + param.getName() + " — не задан entityClass");
            return null;
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException notFound) {
            out.error("Параметр :" + param.getName() + " — класс " + className + " не найден");
            return null;
        }
    }

    private boolean isJpaEntity(Class<?> entityClass) {
        try {
            return sessionFactory.getMappingMetamodel().getEntityDescriptor(entityClass) != null;
        } catch (Exception notAnEntity) {
            return false;
        }
    }

    /**
     * Декодирование JSON-константы defaultValue: число → Long/Double, булево →
     * Boolean, строка в кавычках → String, массив — список значений, null —
     * null. Не-JSON строка без кавычек трактуется как String.
     */
    private Object decodeJson(String paramName, String json, ResolvedParams.Builder out) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return toPlain(node);
        } catch (Exception notJson) {
            if (notJson instanceof com.fasterxml.jackson.core.JsonProcessingException
                && !looksLikeJson(json)) {
                return json.trim();
            }
            out.error("Параметр :" + paramName + " — невалидный defaultValue (JSON): " + json);
            return null;
        }
    }

    private static boolean looksLikeJson(String text) {
        String trimmed = text.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[")
            || trimmed.startsWith("\"") || trimmed.startsWith("t")
            || trimmed.startsWith("f") || trimmed.startsWith("n");
    }

    private List<Object> decodeJsonArray(String paramName, String json, ResolvedParams.Builder out) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                out.error("Параметр :" + paramName + " — defaultValue для PERIOD должен быть JSON-массивом "
                    + "[from, to]: " + json);
                return null;
            }
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(toPlain(item)));
            return values;
        } catch (Exception notJson) {
            out.error("Параметр :" + paramName + " — невалидный defaultValue (JSON): " + json);
            return null;
        }
    }

    private static Object toPlain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(toPlain(item)));
            return values;
        }
        return node.asText();
    }
}