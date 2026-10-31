package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.Lookup;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.springframework.beans.factory.InitializingBean;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Индекс обратных ссылок на сущности.
 *
 * <p>D2 → D3: production wiring supplies the PU-scoped managed-type snapshot and
 * reverse-reference contributors. The legacy package constructors remain for standalone
 * tools/tests; production discovery does not perform an entity classpath scan.</p>
 */
public class ReferenceIndex implements InitializingBean {

    /**
     * Дополнительный источник обратных ссылок от платформенных подсистем (направление
     * зависимости: {@code settings → metadata}, поэтому источники живут в подсистемах и
     * отдают ссылки здесь). Пример: ссылки из {@code @Setting(ENTITY_REFERENCE)} — настройка
     * хранит id сущности в {@code SettingValue.entityRefId}, а не ассоциацию.
     */
    public interface ReverseReferenceSource {
        /** Пересобрать набор ссылок (всегда вызывается из ReferenceIndex.rebuild). */
        void rebuild();

        List<ReverseReference> references();
    }

    private final String basePackage;
    private final ManagedEntityTypes managedEntityTypes;
    private final List<ReverseReferenceSource> sources;
    private final java.util.function.Function<Class<?>, List<ReverseReference>> resolvedReferences;
    private final EntityMetadataApplicability applicability;
    private Map<Class<?>, List<ReverseReference>> index = Map.of();

    /** Standalone compatibility path; production wiring supplies the PU snapshot. */
    @Deprecated(forRemoval = false)
    public ReferenceIndex(String basePackage) {
        this(basePackage, List.of());
    }

    /** Standalone compatibility path; production wiring supplies the PU snapshot. */
    @Deprecated(forRemoval = false)
    public ReferenceIndex(String basePackage, List<ReverseReferenceSource> sources) {
        this.basePackage = Objects.requireNonNull(basePackage, "basePackage must not be null");
        this.managedEntityTypes = null;
        this.resolvedReferences = null;
        this.applicability = type -> type.isAnnotationPresent(EntityMetadata.class);
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
    }

    public ReferenceIndex(ManagedEntityTypes managedEntityTypes,
            List<ReverseReferenceSource> sources) {
        this(managedEntityTypes, sources, null);
    }

    /** Core supplies effective field references without a metadata -> core dependency. */
    public ReferenceIndex(ManagedEntityTypes managedEntityTypes,
            List<ReverseReferenceSource> sources,
            java.util.function.Function<Class<?>, List<ReverseReference>> resolvedReferences) {
        this(managedEntityTypes, sources, resolvedReferences,
            type -> type.isAnnotationPresent(EntityMetadata.class));
    }

    public ReferenceIndex(ManagedEntityTypes managedEntityTypes,
            List<ReverseReferenceSource> sources,
            java.util.function.Function<Class<?>, List<ReverseReference>> resolvedReferences,
            EntityMetadataApplicability applicability) {
        this.basePackage = null;
        this.resolvedReferences = resolvedReferences;
        this.applicability = Objects.requireNonNull(applicability);
        this.managedEntityTypes = Objects.requireNonNull(
            managedEntityTypes, "managedEntityTypes must not be null");
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources must not be null"));
    }

    public ReferenceIndex(ManagedEntityTypes managedEntityTypes) {
        this(managedEntityTypes, List.of());
    }

    @Override
    public void afterPropertiesSet() {
        rebuild();
    }

    public void rebuild() {
        Map<Class<?>, List<ReverseReference>> result = new HashMap<>();
        for (Class<?> owner : annotatedOwners()) {
            if (resolvedReferences == null) {
                collectLookupFields(owner, result);
            } else {
                for (ReverseReference reference : resolvedReferences.apply(owner)) {
                    if (managedEntityTypes.contains(reference.targetClass())) {
                        result.computeIfAbsent(reference.targetClass(), k -> new ArrayList<>())
                            .add(reference);
                    }
                }
            }
        }
        for (ReverseReferenceSource source : sources) {
            source.rebuild();
            for (ReverseReference reference : source.references()) {
                if (managedEntityTypes != null
                        && !managedEntityTypes.contains(reference.targetClass())) {
                    continue;
                }
                result.computeIfAbsent(reference.targetClass(), k -> new ArrayList<>())
                    .add(reference);
            }
        }
        this.index = result;
    }

    public List<ReverseReference> getReverseReferences(Class<?> targetClass) {
        return index.getOrDefault(targetClass, List.of());
    }

    private List<Class<?>> annotatedOwners() {
        if (managedEntityTypes == null) {
            Set<Class<?>> owners = new TreeSet<>(
                Comparator.comparing((Class<?> owner) -> owner.getName()));
            owners.addAll(AnnotationClassScanner.scanAnnotated(basePackage, EntityMetadata.class));
            owners.addAll(AnnotationClassScanner.scanAnnotated(basePackage, TableSectionMetadata.class));
            return List.copyOf(owners);
        }
        return managedEntityTypes.all().stream()
            .filter(owner -> applicability.isApplicable(owner)
                || owner.isAnnotationPresent(TableSectionMetadata.class))
            .toList();
    }

    private void collectLookupFields(Class<?> owner, Map<Class<?>, List<ReverseReference>> result) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                fields.putIfAbsent(field.getName(), field);
            }
        }
        for (Field field : fields.values()) {
            FieldMetadata fieldMetadata = field.getAnnotation(FieldMetadata.class);
            if (fieldMetadata == null) continue;

            Lookup lookup = fieldMetadata.lookup();
            Class<?> targetEntity = lookup.entity();
            if (targetEntity == Void.class && (field.isAnnotationPresent(jakarta.persistence.ManyToOne.class)
                    || field.isAnnotationPresent(jakarta.persistence.OneToOne.class))) {
                targetEntity = field.getType();
            }
            if (targetEntity == Void.class
                    || (managedEntityTypes != null && !managedEntityTypes.contains(targetEntity))) {
                continue;
            }

            result.computeIfAbsent(targetEntity, k -> new ArrayList<>())
                .add(new ReverseReference(targetEntity, owner, field.getName(), false));
        }
    }

    /**
     * Обратная ссылка: запись {@code referencingClass}, у которой поле {@code fieldName}
     * ссылается на целевую сущность {@code targetClass}. {@code columnRef = true} — ссылка не
     * ассоциацией, а колонкой-идентификатором (например {@code SettingValue.entityRefId}):
     * проверка считает совпадение по самому полю, а не по {@code field.id}.
     */
    public record ReverseReference(Class<?> targetClass, Class<?> referencingClass,
                                   String fieldName, boolean columnRef) {

        public ReverseReference(Class<?> referencingClass, String fieldName) {
            this(referencingClass, referencingClass, fieldName, false);
        }

        public String describe(long count) {
            return referencingClass.getSimpleName() + " (поле \"" + fieldName + "\"): " + count +
                (count == 1 ? " запись" : " записи/записей");
        }
    }
}
