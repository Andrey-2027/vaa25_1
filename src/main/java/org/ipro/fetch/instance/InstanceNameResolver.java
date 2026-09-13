package org.ipro.fetch.instance;

import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Единый источник отображаемого имени сущности (C3.1/C3.2).
 *
 * <p>Резолвер строится при старте приложения из набора управляемых сущностей
 * ({@code ManagedEntityCatalog}) и metadata. Для каждой сущности с
 * {@link InstanceName} он один раз собирает и валидирует состав имени: явные
 * attribute paths либо metadata-derived {@code displaySortFields}. Ошибка конфигурации —
 * неизвестный путь, пустой источник при пустом {@code value()} — завершает старт, а не
 * превращается в тихий runtime fallback.</p>
 *
 * <p>Резолвер ничего не запрашивает из repository и не инициирует lazy load: значения
 * читаются напрямую по resolved paths, а неинициализированный Hibernate-прокси
 * представляется идентификатором, а не вызовом {@code getDisplayName()}.</p>
 *
 * <p>Приоритет зафиксирован одной лестницей ({@link #resolve(Object)}):
 * {@code null} → пустая строка; неинициализированный proxy → безопасный
 * {@code Type#id}; объявленный {@code @InstanceName} → вычисленное имя (пустое
 * объявленное имя тоже даёт {@code Type#id}, а не откат на legacy); нет декларации, но
 * есть {@link HasDisplayName} → legacy-имя; иначе → {@code Type#id}. Последняя ступень
 * намеренно не {@code toString()}: {@code Class@hash} ничего не говорит пользователю.
 * {@link #declaredName(Object)} отвечает только за мигрированные сущности (для
 * потребителей, обязанных сохранить legacy-поведение для остальных), а
 * {@link #compatibleDisplayName(Object)} — это та же лестница без уровня декларации,
 * вызываемая вне Spring-контекста.</p>
 */
public final class InstanceNameResolver implements InstanceNameProvider {

    /** Глубина разворота ссылочной части имени — защита от циклов A → B → A. */
    private static final int MAX_REFERENCE_DEPTH = 2;

    private final Map<Class<?>, Definition> definitions;

    public InstanceNameResolver(Collection<Class<?>> managedEntityTypes,
                                MetadataResolver metadataResolver) {
        Objects.requireNonNull(managedEntityTypes, "managedEntityTypes must not be null");
        Objects.requireNonNull(metadataResolver, "metadataResolver must not be null");
        Map<Class<?>, Definition> byClass = new HashMap<>();
        for (Class<?> type : managedEntityTypes) {
            if (type == null || declarationOf(type) == null) {
                continue;
            }
            byClass.put(type, build(type, declarationOf(type), metadataResolver));
        }
        this.definitions = Map.copyOf(byClass);
    }

    /** true — сущность объявила InstanceName и потому мигрирована на единый источник. */
    public boolean hasDeclaration(Class<?> entityClass) {
        return entityClass != null && declarationOf(entityClass) != null;
    }

    /**
     * Знает ли состав имени для типа ИМЕННО этот резолвер, то есть входил ли тип в его
     * набор управляемых сущностей. Отличается от {@link #hasDeclaration(Class)}: тот про
     * наличие декларации у класса (чистая рефлексия), а этот — про способность посчитать
     * имя. Нужен там, где выбирается авторитетный резолвер среди нескольких живых
     * контекстов с разными наборами metadata ({@link InstanceNameBridge}).
     */
    public boolean canResolve(Class<?> entityClass) {
        return entityClass != null && definitions.containsKey(entityClass);
    }

    /**
     * Состав имени (attribute paths) объявившей {@link InstanceName} сущности; пустой
     * список — декларации нет. Это контракт C3.1: потребители (FetchPlan, углубление
     * fetch-графов) узнают, из чего складывается имя, не дублируя формат.
     */
    public List<String> instanceNamePaths(Class<?> entityClass) {
        Definition definition = entityClass == null ? null : definitions.get(entityClass);
        if (definition == null) {
            return List.of();
        }
        return definition.paths().stream().map(ColumnPath::getKey).toList();
    }

    /**
     * Пути ассоциаций, которые нужно инициализировать, чтобы имя мигрированной сущности
     * вычислялось без lazy load (например, {@code "nomenclature.name"} →
     * {@code "nomenclature"}). Зависимости instance name для LOOKUP/DETAIL планов (C3.1).
     */
    public List<String> instanceNameFetchPaths(Class<?> entityClass) {
        Definition definition = entityClass == null ? null : definitions.get(entityClass);
        if (definition == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (ColumnPath path : definition.paths()) {
            for (String fetchPath : path.getFetchPaths()) {
                if (!result.contains(fetchPath)) {
                    result.add(fetchPath);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Имя объявленной (мигрированной) сущности; {@code null} — только если у типа вообще
     * нет декларации. Потребители в этом случае остаются на своём прежнем источнике.
     *
     * <p>Если декларация есть, но её части не заполнены, возвращается безопасная ссылка
     * {@code Type#id}, а НЕ {@code null}: иначе мигрированная сущность молча вернулась бы
     * на вторую (legacy) лестницу, и один и тот же объект показывался бы по-разному в UI
     * и в аудите.</p>
     */
    public String declaredName(Object entity) {
        if (entity == null) {
            return null;
        }
        Definition definition = definitions.get(persistentClass(entity));
        if (definition == null) {
            return null;
        }
        if (!Hibernate.isInitialized(entity)) {
            return referenceLabel(entity);
        }
        String rendered = definition.render(entity, definitions);
        return rendered.isBlank() ? referenceLabel(entity) : rendered;
    }

    /**
     * Единая лестница разрешения имени — контракт {@link InstanceNameProvider}. Никогда не
     * инициирует lazy load и не обращается к repository.
     */
    @Override
    public String resolve(Object entity) {
        if (entity == null) {
            return "";
        }
        if (!Hibernate.isInitialized(entity)) {
            // Прокси не разворачиваем ни на одном уровне: сам факт обращения к нему внутри
            // открытой сессии дал бы лишний SQL, а вне сессии — LazyInitializationException.
            return referenceLabel(entity);
        }
        Definition definition = definitions.get(persistentClass(entity));
        if (definition != null) {
            String rendered = definition.render(entity, definitions);
            return rendered.isBlank() ? referenceLabel(entity) : rendered;
        }
        return compatibleDisplayName(entity);
    }

    // ------------------------------------------------------------------ build

    private static Definition build(Class<?> entityClass, InstanceName declaration,
                                    MetadataResolver metadataResolver) {
        List<String> paths;
        if (declaration.value().length > 0) {
            paths = List.of(declaration.value());
        } else {
            paths = derivedPaths(entityClass, metadataResolver);
        }
        List<ColumnPath> resolved = new ArrayList<>(paths.size());
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("@InstanceName on " + entityClass.getName()
                    + " declares a blank attribute path");
            }
            try {
                resolved.add(ColumnPath.resolve(entityClass, path));
            } catch (IllegalArgumentException invalidPath) {
                throw new IllegalStateException("@InstanceName on " + entityClass.getName()
                    + " declares invalid attribute path '" + path + "'", invalidPath);
            }
        }
        if (resolved.isEmpty()) {
            throw new IllegalStateException("@InstanceName on " + entityClass.getName()
                + " resolves to an empty name composition");
        }
        return new Definition(List.copyOf(resolved), declaration.separator());
    }

    private static List<String> derivedPaths(Class<?> entityClass, MetadataResolver metadataResolver) {
        EntityMetadataInfo metadata;
        try {
            metadata = metadataResolver.resolve(entityClass);
        } catch (IllegalArgumentException notMetadataDriven) {
            throw new IllegalStateException("@InstanceName on " + entityClass.getName()
                + " declares no explicit paths and the entity has no @EntityMetadata to derive "
                + "them from; declare value() explicitly", notMetadataDriven);
        }
        List<String> displaySortFields = metadata.getDisplaySortFields();
        if (displaySortFields.isEmpty()) {
            throw new IllegalStateException("@InstanceName on " + entityClass.getName()
                + " declares no explicit paths and @EntityMetadata.displaySortFields is empty; "
                + "declare value() explicitly or add displaySortFields");
        }
        return displaySortFields;
    }

    /**
     * Есть ли у класса объявленный {@code @InstanceName} (вверх по иерархии).
     * Не требует построенного резолвера — используется там, где доступна только рефлексия
     * (например, startup-валидация глобального поиска), без зависимости от порядка установки
     * бинов.
     */
    public static boolean declares(Class<?> entityClass) {
        return entityClass != null && declarationOf(entityClass) != null;
    }

    /** Декларация ищется вверх по иерархии: mapped superclass тоже может её несть. */
    private static InstanceName declarationOf(Class<?> entityClass) {
        for (Class<?> current = entityClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            InstanceName declaration = current.getAnnotation(InstanceName.class);
            if (declaration != null) {
                return declaration;
            }
        }
        return null;
    }

    // --------------------------------------------------------- compatibility

    /**
     * Та же лестница без уровня декларации {@code @InstanceName} — для значений
     * немигрированных типов, скаляров и вызовов вне Spring-контекста
     * ({@code InstanceNameBridge}). Единственная реализация: вызывается и из
     * {@link #resolve(Object)}, и из bridge, поэтому второй лестницы не существует.
     */
    public static String compatibleDisplayName(Object value) {
        if (value == null) {
            return "";
        }
        if (!isEntityReference(persistentClass(value))) {
            // Скаляр (код, дата, число) — не сущность, рендерится как есть.
            return String.valueOf(value);
        }
        if (!Hibernate.isInitialized(value)) {
            return referenceLabel(value);
        }
        if (value instanceof HasDisplayName named) {
            String name = named.getDisplayName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        String reflective = reflectiveDisplayName(value);
        if (reflective != null && !reflective.isBlank()) {
            return reflective;
        }
        return referenceLabel(value);
    }

    private static String reflectiveDisplayName(Object entity) {
        try {
            Method method = persistentClass(entity).getMethod("getDisplayName");
            Object name = method.invoke(entity);
            return name == null ? null : String.valueOf(name);
        } catch (ReflectiveOperationException | RuntimeException noDisplayName) {
            return null;
        }
    }

    // ------------------------------------------------------------- rendering

    private static String renderPart(Object value, int depth, Map<Class<?>, Definition> definitions) {
        if (value == null) {
            return "";
        }
        Class<?> valueClass = persistentClass(value);
        if (!isEntityReference(valueClass)) {
            return String.valueOf(value);
        }
        if (!Hibernate.isInitialized(value)) {
            // Контракт «резолвер не инициирует lazy load»: прокси представляется ссылкой.
            return referenceLabel(value);
        }
        if (depth <= MAX_REFERENCE_DEPTH) {
            Definition nested = definitions.get(valueClass);
            if (nested != null) {
                String name = nested.render(value, depth + 1, definitions);
                if (!name.isBlank()) {
                    return name;
                }
            }
            if (value instanceof HasDisplayName named) {
                String name = named.getDisplayName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        return referenceLabel(value);
    }

    private static boolean isEntityReference(Class<?> type) {
        if (type.isPrimitive() || type.isEnum() || type.isArray()) {
            return false;
        }
        if (CharSequence.class.isAssignableFrom(type) || Number.class.isAssignableFrom(type)
                || Boolean.class == type || Character.class == type) {
            return false;
        }
        if (java.time.temporal.TemporalAccessor.class.isAssignableFrom(type)) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jakarta.")
                || name.startsWith("org.springframework.") || name.startsWith("org.hibernate.")
                || name.startsWith("com.vaadin.") || name.startsWith("org.vaadin.")) {
            return false;
        }
        try {
            type.getMethod("getId");
            return true;
        } catch (NoSuchMethodException notAnEntity) {
            return false;
        }
    }

    /**
     * Безопасный fallback {@code Class#id}. Идентификатор неинициализированного прокси
     * читается через {@link HibernateProxy#getHibernateLazyInitializer()}, а не через
     * {@code getId()}: вызов геттера на прокси может инициализировать его (и упасть на
     * отсутствующей строке), а {@code LazyInitializer.getIdentifier()} — не может.
     */
    private static String referenceLabel(Object entity) {
        String type = persistentClass(entity).getSimpleName();
        Object id = identifierOf(entity);
        return id == null ? type : type + "#" + id;
    }

    private static Object identifierOf(Object entity) {
        if (entity instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getIdentifier();
        }
        try {
            return persistentClass(entity).getMethod("getId").invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException noId) {
            return null;
        }
    }

    /**
     * Класс сущности без инициализации прокси. {@code Hibernate.getClass()} для
     * неинициализированного прокси обращается к его реализации и тем самым загружает её,
     * поэтому persistent class берётся из lazy initializer.
     *
     * <p>Package-private намеренно: тем же прокси-безопасным способом пользуется
     * статический {@link InstanceNameBridge}, выбирая регистрацию по типу значения, —
     * иначе второй такой же расчёт пришлось бы держать там (это уже один раз привело
     * к развороту прокси через {@code Hibernate.getClass}).</p>
     */
    static Class<?> persistentClass(Object entity) {
        if (entity instanceof HibernateProxy proxy) {
            Class<?> persistent = proxy.getHibernateLazyInitializer().getPersistentClass();
            if (persistent != null) {
                return persistent;
            }
        }
        return entity.getClass();
    }

    /** Разрешённый состав имени одной сущности — immutable, кэшируется на старте. */
    private record Definition(List<org.ipro.metadata.ColumnPath> paths, String separator) {

        String render(Object entity, Map<Class<?>, Definition> definitions) {
            return render(entity, 1, definitions);
        }

        String render(Object entity, int depth, Map<Class<?>, Definition> definitions) {
            List<String> parts = new ArrayList<>(paths.size());
            for (ColumnPath path : paths) {
                String text = renderPart(path.getValue(entity), depth, definitions);
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
            return String.join(separator, parts);
        }
    }
}
