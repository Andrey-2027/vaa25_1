package org.ipro.query.param;

import org.ipro.query.ReportContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ipro.query.model.QueryComputedValue;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
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
     * @param params      параметры запроса (порядок не важен)
     * @param context     контекст запуска (момент, пользователь, документ/выборка)
     * @param formValues  значения, введённые на форме (ключи — имена параметров;
     *                    для PERIOD — {@code nameFrom}/{@code nameTo})
     */
    public ResolvedParams resolve(List<QueryParameter> params, ReportContext context,
                                  Map<String, Object> formValues) {
        ResolvedParams.Builder out = ResolvedParams.builder();
        if (params != null) {
            for (QueryParameter param : params) {
                resolveParam(param, context, formValues, out);
            }
        }
        return out.build();
    }

    private void resolveParam(QueryParameter param, ReportContext context,
                              Map<String, Object> formValues, ResolvedParams.Builder out) {
        switch (param.kind()) {
            case SCALAR -> resolveScalar(param, context, formValues, out);
            case RANGE -> resolvePeriod(param, context, formValues, out);
            case ENTITY, ENTITY_LIST -> resolveEntity(param, context, formValues, out);
            default -> out.error("Параметр :" + param.name() + ": неизвестный вид "
                + param.kind());
        }
    }

    // === SCALAR ===

    private void resolveScalar(QueryParameter param, ReportContext context,
                               Map<String, Object> formValues, ResolvedParams.Builder out) {
        switch (param.valueSource()) {
            case FORM -> {
                Object value = rawFormValue(formValues, param.name());
                if (isEmpty(value)) {
                    requireFilled(param, out);
                    return;
                }
                out.bind(param.name(), value);
            }
            case FIXED -> {
                Object value = decodeJson(param.name(), param.defaultValue(), out);
                if (isEmpty(value)) {
                    requireFilled(param, out);
                    return;
                }
                out.bind(param.name(), value);
            }
            case COMPUTED -> {
                Object value = computedValue(param, context, out);
                if (value == null) {
                    requireFilled(param, out);
                    return;
                }
                out.bind(param.name(), value);
            }
            case CONTEXT -> out.error("Параметр :" + param.name()
                + ": источник CONTEXT применим только к сущностным параметрам (kind=ENTITY/ENTITY_LIST)");
        }
    }

    // === PERIOD: два бинд-имени nameFrom/nameTo ===

    private void resolvePeriod(QueryParameter param, ReportContext context,
                               Map<String, Object> formValues, ResolvedParams.Builder out) {
        String fromName = param.name() + "From";
        String toName = param.name() + "To";
        Object from = null;
        Object to = null;

        switch (param.valueSource()) {
            case FORM -> {
                from = rawFormValue(formValues, fromName);
                to = rawFormValue(formValues, toName);
            }
            case FIXED -> {
                List<Object> pair = decodeJsonArray(param.name(), param.defaultValue(), out);
                if (pair != null && !pair.isEmpty()) {
                    from = pair.size() > 0 ? pair.get(0) : null;
                    to = pair.size() > 1 ? pair.get(1) : null;
                }
            }
            case COMPUTED -> {
                if (param.computed() == QueryComputedValue.NOW) {
                    from = context.now();
                    to = context.now();
                } else {
                    out.error("Параметр :" + param.name()
                        + ": COMPUTED для PERIOD поддерживает только NOW (момент из контекста)");
                    return;
                }
            }
            case CONTEXT -> {
                out.error("Параметр :" + param.name()
                    + ": источник CONTEXT для PERIOD не поддерживается");
                return;
            }
        }

        if (from == null && to == null) {
            if (param.required()) {
                out.error("Параметр :" + param.name() + " — обязательный (период не задан)");
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

    private void resolveEntity(QueryParameter param, ReportContext context,
                               Map<String, Object> formValues, ResolvedParams.Builder out) {
        Class<?> entityClass = entityClassOf(param, out);
        if (entityClass == null) {
            return;
        }
        if (!isJpaEntity(entityClass)) {
            out.error("Параметр :" + param.name() + ": класс " + entityClass.getName()
                + " не является JPA-сущностью");
            return;
        }

        List<Object> ids = null;
        switch (param.valueSource()) {
            case FORM -> ids = formIds(param, formValues, out);
            case CONTEXT -> {
                if (!context.matches(entityClass)) {
                    if (param.required()) {
                        out.error("Параметр :" + param.name() + " — контекст запуска не предоставляет "
                            + entityClass.getSimpleName() + " (ожидался контекст совместимого класса)");
                    }
                    return;
                }
                ids = contextIds(param, context, out);
            }
            case FIXED -> {
                if (param.kind() == QueryParameterKind.ENTITY_LIST) {
                    out.error("Параметр :" + param.name()
                        + ": DEFAULT для ENTITY_LIST не поддерживается — укажите список в контексте или на форме");
                    return;
                }
                Object id = decodeJson(param.name(), param.defaultValue(), out);
                ids = id == null ? List.of() : List.of(id);
            }
            case COMPUTED -> {
                out.error("Параметр :" + param.name()
                    + ": COMPUTED для сущностных параметров не поддерживается (только SCALAR/PERIOD)");
                return;
            }
        }

        if (ids == null || ids.isEmpty()) {
            if (param.required()) {
                requireFilled(param, out);
            }
            return;
        }

        if (param.kind() == QueryParameterKind.ENTITY) {
            Object entity = refresher.refresh(entityClass, ids.get(0));
            if (entity == null) {
                out.error("Параметр :" + param.name() + " — сущность " + entityClass.getSimpleName()
                    + " по id=" + ids.get(0) + " не найдена или недоступна (RLS)");
                return;
            }
            out.bind(param.name(), entity);
        } else {
            List<Object> entities = new ArrayList<>(ids.size());
            for (Object id : ids) {
                Object entity = refresher.refresh(entityClass, id);
                if (entity == null) {
                    out.error("Параметр :" + param.name() + " — элемент списка " + entityClass.getSimpleName()
                        + " по id=" + id + " не найден или недоступен (RLS)");
                    return;
                }
                entities.add(entity);
            }
            out.bind(param.name(), entities);
        }
    }

    // === Вспомогательные ===

    private void requireFilled(QueryParameter param, ResolvedParams.Builder out) {
        if (param.required()) {
            out.error("Параметр :" + param.name() + " — обязательный параметр не заполнен");
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
    private List<Object> formIds(QueryParameter param, Map<String, Object> formValues,
                                 ResolvedParams.Builder out) {
        Object raw = rawFormValue(formValues, param.name());
        if (raw == null) {
            return List.of();
        }
        if (param.kind() == QueryParameterKind.ENTITY) {
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

    private Object parseId(QueryParameter param, Object raw, ResolvedParams.Builder out) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException notAnId) {
                out.error("Параметр :" + param.name() + " — id «" + text
                    + "» не является числом");
                return null;
            }
        }
        Object id = persistenceUnitUtil.getIdentifier(raw);
        if (id != null) {
            return id instanceof Number number ? number.longValue() : id;
        }
        out.error("Параметр :" + param.name() + " — значение id должно быть числом, получено: "
            + raw.getClass().getSimpleName());
        return null;
    }

    private static List<Object> contextIds(QueryParameter param, ReportContext context,
                                           ResolvedParams.Builder out) {
        if (param.kind() == QueryParameterKind.ENTITY) {
            return context.entityId() == null ? List.of() : List.of(context.entityId());
        }
        if (context.selectedIds() != null && !context.selectedIds().isEmpty()) {
            return new ArrayList<>(context.selectedIds());
        }
        return context.entityId() == null ? List.of() : List.of(context.entityId());
    }

    private Object computedValue(QueryParameter param, ReportContext context,
                                 ResolvedParams.Builder out) {
        switch (param.computed()) {
            case NOW -> {
                return context.now();
            }
            case CURRENT_USER -> {
                String user = context.user() != null
                    ? context.user() : currentUser.requireAuthenticatedUsername();
                if (user == null || user.isBlank()) {
                    out.error("Параметр :" + param.name() + " — нет текущего пользователя");
                    return null;
                }
                return user;
            }
            case RLS_ORG -> {
                String user = context.user() != null
                    ? context.user() : currentUser.requireAuthenticatedUsername();
                List<Long> readableIds = accessService.getReadableIds(rlsOrgDimension, user);
                if (readableIds == null) {
                    out.error("Параметр :" + param.name() + " — доступ по измерению «"
                        + rlsOrgDimension + "» без ограничений, организация неоднозначна");
                    return null;
                }
                if (readableIds.size() == 1
                    && readableIds.get(0) != AccessService.NO_ACCESS_SENTINEL) {
                    return readableIds.get(0);
                }
                out.error("Параметр :" + param.name() + " — по измерению «" + rlsOrgDimension
                    + "» у пользователя нет единственной организации (доступных значений: "
                    + readableIds.size() + ")");
                return null;
            }
            default -> {
                out.error("Параметр :" + param.name() + " — COMPUTED без заданного значения (computed=NONE)");
                return null;
            }
        }
    }

    private Class<?> entityClassOf(QueryParameter param, ResolvedParams.Builder out) {
        String className = param.entityClass();
        if (className == null || className.isBlank()) {
            out.error("Параметр :" + param.name() + " — не задан entityClass");
            return null;
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException notFound) {
            out.error("Параметр :" + param.name() + " — класс " + className + " не найден");
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
