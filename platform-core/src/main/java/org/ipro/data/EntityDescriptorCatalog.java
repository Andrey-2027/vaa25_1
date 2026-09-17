package org.ipro.data;

import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Единственный классифицированный descriptor catalog C4 (ADR-0007 §2).
 *
 * <p>Строится поверх уже существующих {@link ManagedEntityCatalog} (сырая граница
 * JPA metamodel) и {@link SectionMetadataRegistry} (факт owned-row ownership), без нового
 * classpath scan и без второго источника типов. Тип, присутствующий в persistence unit,
 * сам по себе не получает data handle: экспозиция определяется здесь.</p>
 *
 * <p>Правила классификации:</p>
 * <ol>
 * <li>явный {@link EntityExposureOverride} — высший приоритет (структурные owned-строки,
 * которые нельзя вывести из аннотаций);</li>
 * <li>тип, объявленный {@code @TableSectionMetadata}, — {@link EntityExposure#OWNED_ROW};</li>
 * <li>тип с {@code @EntityMetadata} — {@link EntityExposure#STANDARD_ROOT};</li>
 * <li>остальные (report/settings/telemetry/числовой storage) —
 * {@link EntityExposure#INTERNAL_STORE};</li>
 * <li>тип, отсутствующий в каталоге, — {@link EntityExposure#UNCLASSIFIED} без единой
 * capability, а не «permissive root»: canonical path работает fail-closed.</li>
 * </ol>
 *
 * <p>{@link EntityCapabilityOverride} применяется поверх экспозиции и заменяет выведенные
 * capabilities целиком: тип может остаться, например, {@code INTERNAL_STORE}, но владелец
 * подсистемы явно отдаёт ему {@code LIST}/{@code DETAIL} на время, пока его сервис не
 * переведён на owner-специфичную реализацию. Такое исключение должно быть названо
 * причиной, а не выведено из факта присутствия в metamodel.</p>
 *
 * <p>Компонент immutable после построения и потокобезопасен.</p>
 */
public final class EntityDescriptorCatalog {

    private final Map<Class<?>, EntityDescriptor> byType;
    private final List<Class<?>> managedTypes;

    public EntityDescriptorCatalog(ManagedEntityCatalog managedEntityCatalog,
                                   SectionMetadataRegistry sectionRegistry,
                                   MetadataResolver metadataResolver,
                                   List<EntityExposureOverride> overrides) {
        this(managedEntityCatalog, sectionRegistry, metadataResolver, overrides, List.of());
    }

    public EntityDescriptorCatalog(ManagedEntityCatalog managedEntityCatalog,
                                   SectionMetadataRegistry sectionRegistry,
                                   MetadataResolver metadataResolver,
                                   List<EntityExposureOverride> overrides,
                                   List<EntityCapabilityOverride> capabilityOverrides) {
        Objects.requireNonNull(managedEntityCatalog, "managedEntityCatalog must not be null");
        Objects.requireNonNull(sectionRegistry, "sectionRegistry must not be null");
        Objects.requireNonNull(metadataResolver, "metadataResolver must not be null");

        Map<Class<?>, EntityExposureOverride> overrideByType = new LinkedHashMap<>();
        for (EntityExposureOverride override : overrides == null ? List.<EntityExposureOverride>of() : overrides) {
            EntityExposureOverride previous = overrideByType.putIfAbsent(override.type(), override);
            if (previous != null) {
                throw new IllegalStateException("Duplicate exposure override for "
                    + override.type().getName());
            }
        }

        Map<Class<?>, EntityCapabilityOverride> capabilityByType = new LinkedHashMap<>();
        for (EntityCapabilityOverride override : capabilityOverrides == null
                ? List.<EntityCapabilityOverride>of() : capabilityOverrides) {
            EntityCapabilityOverride previous = capabilityByType.putIfAbsent(override.type(), override);
            if (previous != null) {
                throw new IllegalStateException("Duplicate capability override for "
                    + override.type().getName());
            }
        }

        List<Class<?>> types = managedEntityCatalog.managedEntityClasses().stream()
            .sorted(Comparator.comparing(Class::getName))
            .toList();

        Map<Class<?>, EntityDescriptor> descriptors = new LinkedHashMap<>();
        for (Class<?> type : types) {
            descriptors.put(type, classify(type, sectionRegistry, metadataResolver,
                overrideByType.get(type), capabilityByType.get(type)));
        }
        for (Class<?> overridden : capabilityByType.keySet()) {
            // Проверяется сам факт persistence type, а не членство в текущем persistence unit:
            // частичный/slice-контекст с урезанным набором сущностей — не ошибка конфигурации,
            // а опечатка в policy (override на не-сущность) — ошибка.
            if (!overridden.isAnnotationPresent(jakarta.persistence.Entity.class)) {
                throw new IllegalStateException("Capability override for " + overridden.getName()
                    + " is not a JPA entity: policy describes a persistence type");
            }
        }
        this.byType = Map.copyOf(descriptors);
        this.managedTypes = List.copyOf(types);
    }

    /** Descriptor для управляемого типа без fallback (для тестов и диагностики). */
    public Optional<EntityDescriptor> find(Class<?> type) {
        return Optional.ofNullable(byType.get(type));
    }

    /**
     * Descriptor для типа. Тип вне каталога получает {@link EntityExposure#UNCLASSIFIED}
     * с пустыми capabilities и причиной: canonical path отказывает до RLS и SQL, а не
     * выдаёт неизвестному классу полный read/write handle.
     */
    public EntityDescriptor descriptorOf(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        EntityDescriptor descriptor = byType.get(type);
        if (descriptor != null) {
            return descriptor;
        }
        return new EntityDescriptor(type, EntityExposure.UNCLASSIFIED, false, false,
            capabilities(EntityExposure.UNCLASSIFIED),
            "not present in ManagedEntityCatalog");
    }

    /** Все управляемые типы в стабильном (по имени класса) порядке. */
    public List<Class<?>> managedTypes() {
        return managedTypes;
    }

    /** Descriptors всех управляемых типов в стабильном порядке. */
    public List<EntityDescriptor> all() {
        return managedTypes.stream().map(byType::get).toList();
    }

    private static EntityDescriptor classify(Class<?> type,
                                             SectionMetadataRegistry sectionRegistry,
                                             MetadataResolver metadataResolver,
                                             EntityExposureOverride exposureOverride,
                                             EntityCapabilityOverride capabilityOverride) {
        boolean metadataDriven = isMetadataDriven(type, metadataResolver);
        Optional<TableSectionMetadataInfo> section = sectionRegistry.findByRow(type);

        EntityExposure exposure;
        String reason;
        if (exposureOverride != null) {
            exposure = exposureOverride.exposure();
            reason = exposureOverride.reason();
        } else if (section.isPresent()) {
            exposure = EntityExposure.OWNED_ROW;
            reason = "declared owned section " + section.get().getKey();
        } else if (metadataDriven) {
            exposure = EntityExposure.STANDARD_ROOT;
            reason = "metadata-driven root";
        } else {
            exposure = EntityExposure.INTERNAL_STORE;
            reason = "no @EntityMetadata and not an owned section";
        }

        EntityCapabilities capabilities = capabilityOverride == null
            ? capabilities(exposure)
            : new EntityCapabilities(capabilityOverride.reads(), capabilityOverride.writes(),
                capabilityOverride.reason());
        return new EntityDescriptor(type, exposure, true, metadataDriven, capabilities, reason);
    }

    private static boolean isMetadataDriven(Class<?> type, MetadataResolver metadataResolver) {
        try {
            return metadataResolver.resolve(type) != null;
        } catch (IllegalArgumentException notMetadataDriven) {
            return false;
        }
    }

    private static EntityCapabilities capabilities(EntityExposure exposure) {
        return switch (exposure) {
            case STANDARD_ROOT -> new EntityCapabilities(
                Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
                Set.of(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE),
                "standard root with canonical data handle");
            case OWNED_ROW -> new EntityCapabilities(
                Set.of(FetchScenario.ROW), Set.of(),
                "owned row: accessible only through the aggregate boundary");
            case INTERNAL_STORE -> new EntityCapabilities(
                Set.of(), Set.of(),
                "internal store: no public entity facade");
            case UNCLASSIFIED -> new EntityCapabilities(
                Set.of(), Set.of(),
                "type is not present in ManagedEntityCatalog: no canonical data handle");
        };
    }
}
