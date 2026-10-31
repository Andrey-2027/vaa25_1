package org.ipro.data;

import org.ipro.crud.InternedEntity;
import org.ipro.metadata.CatalogOwnerDescriptor;
import org.ipro.metadata.CatalogOwnerResolver;
import org.ipro.metadata.MetadataDiagnosticCodes;
import org.ipro.metadata.annotation.EntityKindCandidate;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.FactOrigin;
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
 * <p><b>Происхождение набора сценариев (E3.2.0 шаг 2).</b> Оба решения классификации несут
 * своё происхождение: экспозиция — {@code exposureOrigin}/{@code exposureSymbol} (E3.1),
 * набор read-сценариев и write-намерений — {@code capabilitiesOrigin}/{@code
 * capabilitiesSymbol}. Прикладной override даёт {@link FactOrigin#REGISTRATION}, правило
 * экспозиции — {@link FactOrigin#PLATFORM_DEFAULT}; совпадение набора с правилом
 * происхождение <b>не</b> меняет, потому что override заменяет выведенное, а не дополняет
 * его. Символ у обоих пуст — см. {@link EntityDescriptor}.</p>
 *
 * <p>Компонент immutable после построения и потокобезопасен.</p>
 */
public final class EntityDescriptorCatalog {

    private final Map<Class<?>, EntityDescriptor> byType;
    private final List<Class<?>> managedTypes;
    private final Map<Class<?>, EntityClassification> classifications;

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
        this(managedEntityCatalog, sectionRegistry, metadataResolver, overrides, capabilityOverrides, List.of());
    }

    /** Final aggregation follows metadata, sections and validated backend descriptors. */
    public EntityDescriptorCatalog(ManagedEntityCatalog managedEntityCatalog,
                                   SectionMetadataRegistry sectionRegistry,
                                   MetadataResolver metadataResolver,
                                   List<EntityExposureOverride> overrides,
                                   List<EntityCapabilityOverride> capabilityOverrides,
                                   List<EntityClassificationContributor> contributors) {
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

        CatalogOwnerResolver catalogOwners = new CatalogOwnerResolver();
        Map<Class<?>, EntityDescriptor> descriptors = new LinkedHashMap<>();
        for (Class<?> type : types) {
            descriptors.put(type, classify(type, sectionRegistry, metadataResolver,
                overrideByType.get(type), capabilityByType.get(type),
                catalogOwners.ownerOf(type).orElse(null)));
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
        Map<Class<?>, EntityClassification> facts = new LinkedHashMap<>();
        List<EntityClassificationContributor> sources = contributors == null ? List.of() : List.copyOf(contributors);
        for (Class<?> type : types) facts.put(type, classifySemantics(type, metadataResolver, sources));
        this.classifications = Map.copyOf(facts);
        this.byType = Map.copyOf(descriptors);
        this.managedTypes = List.copyOf(types);
    }

    /** Effective facts only for this persistence unit; unknown types have no fallback. */
    public EntityClassification classificationOf(Class<?> type) {
        EntityClassification result = classifications.get(Objects.requireNonNull(type));
        if (result == null) throw new IllegalArgumentException("Type is not managed: " + type.getName());
        return result;
    }

    /** Standalone adapter for legacy constructors; module candidates still require validated sources. */
    public static EntityClassification classifySemantics(Class<?> type, MetadataResolver resolver,
            List<EntityClassificationContributor> contributors) {
        var metadata = resolver.find(type);
        var kind = metadata.map(EntityMetadataInfo::getEntityKind)
            .orElse(EntityKind.PLAIN);
        var origin = metadata.map(EntityMetadataInfo::getEntityKindOrigin)
            .orElse(FactOrigin.PLATFORM_DEFAULT);
        var symbol = metadata.map(EntityMetadataInfo::getEntityKindSymbol).orElse("");
        EntityClassificationContributor.Contribution selected = null;
        for (var contributor : contributors) {
            Optional<EntityClassificationContributor.Contribution> contribution;
            try {
                contribution = Objects.requireNonNull(contributor.contribute(type),
                    "Classification source returned null: " + contributor.getClass().getName());
            } catch (RuntimeException failure) {
                throw classificationError(MetadataDiagnosticCodes.CLASSIFICATION_SOURCE_FAILED, type, contributor.getClass().getName(),
                    "Classification source failed for " + type.getName() + ": " + failure.getMessage(), failure);
            }
            if (contribution.isEmpty()) continue;
            if (selected != null) throw classificationError(MetadataDiagnosticCodes.CLASSIFICATION_SOURCE_DUPLICATE, type, "EntityClassificationContributor",
                "Duplicate classification contributions for " + type.getName() + ": " + selected.symbol() + " / " + contribution.get().symbol(), null);
            selected = contribution.get();
        }
        for (var annotation : type.getAnnotations()) {
            var hint = annotation.annotationType().getAnnotation(
                EntityKindCandidate.class);
            if (hint != null && (selected == null || selected.kind() != hint.value()))
                throw classificationError(MetadataDiagnosticCodes.CLASSIFICATION_SOURCE_MISSING, type, annotation.annotationType().getName(),
                    "Missing validated classification source for " + type.getName() + " requires " + hint.value(), null);
        }
        if (kind == EntityKind.INFORMATION_REGISTER
                && (selected == null || selected.kind() != kind))
            throw classificationError(MetadataDiagnosticCodes.CLASSIFICATION_SOURCE_MISSING, type, "EntityMetadata.kind",
                "Missing validated INFORMATION_REGISTER descriptor for " + type.getName(), null);
        if (selected != null) {
            if (kind != EntityKind.PLAIN && kind != selected.kind())
                throw classificationError(MetadataDiagnosticCodes.ENTITY_CLASSIFICATION_CONFLICT, type, "EntityMetadata / EntityClassificationContributor",
                    "Entity kind conflict for " + type.getName() + ": " + kind + " / " + selected.kind(), null);
            kind = selected.kind(); origin = selected.origin(); symbol = selected.symbol();
        }
        boolean interned = InternedEntity.class.isAssignableFrom(type);
        return new EntityClassification(kind, origin, symbol, interned,
            interned ? FactOrigin.DERIVED : FactOrigin.PLATFORM_DEFAULT,
            interned ? InternedEntity.class.getName() : "",
            selected != null && selected.recorderOwned());
    }

    private static IllegalStateException classificationError(String code, Class<?> type, String source,
            String message, Throwable cause) {
        var diagnostic = new org.ipro.metadata.MetadataDiagnostic(
            org.ipro.metadata.MetadataDiagnostic.Severity.ERROR, code, type.getName(), "", source, message);
        return new IllegalStateException(diagnostic.render(), cause);
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
            "not present in ManagedEntityCatalog", FactOrigin.PLATFORM_DEFAULT, "",
            FactOrigin.PLATFORM_DEFAULT, "");
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
                                             EntityCapabilityOverride capabilityOverride,
                                             CatalogOwnerDescriptor owner) {
        boolean metadataDriven = isMetadataDriven(type, metadataResolver);
        Optional<TableSectionMetadataInfo> section = sectionRegistry.findByRow(type);

        EntityExposure exposure;
        String reason;
        FactOrigin exposureOrigin;
        String exposureSymbol;
        if (exposureOverride != null) {
            exposure = exposureOverride.exposure();
            reason = exposureOverride.reason();
            exposureOrigin = FactOrigin.REGISTRATION;
            exposureSymbol = "";
        } else if (section.isPresent()) {
            exposure = EntityExposure.OWNED_ROW;
            reason = "declared owned section " + section.get().getKey();
            exposureOrigin = FactOrigin.DERIVED;
            exposureSymbol = section.get().getRowClass().getName();
        } else if (metadataDriven) {
            exposure = EntityExposure.STANDARD_ROOT;
            reason = "metadata-driven root";
            exposureOrigin = FactOrigin.DERIVED;
            exposureSymbol = type.getName();
        } else {
            exposure = EntityExposure.INTERNAL_STORE;
            reason = "no @EntityMetadata and not an owned section";
            exposureOrigin = FactOrigin.DERIVED;
            exposureSymbol = type.getName();
        }

        // Происхождение набора сценариев — отдельная ось (E3.2.0 шаг 2): платформенное правило
        // экспозиции против прикладной policy типа. Символ пуст у обоих по решению: у правила
        // места объявления нет (правило названо причиной), а прикладной override собирается
        // списком бинов, и класса-объявления ядро не знает (та же граница, что у exposureSymbol).
        EntityCapabilities capabilities;
        FactOrigin capabilitiesOrigin;
        var interning = org.ipro.crud.InterningContracts.find(type);
        if (capabilityOverride == null) {
            capabilities = capabilities(exposure);
            capabilitiesOrigin = FactOrigin.PLATFORM_DEFAULT;
            if (interning.isPresent()) {
                capabilities = new EntityCapabilities(capabilities.readScenarios(),
                    exposure == EntityExposure.STANDARD_ROOT && interning.get().genericCreate()
                        ? Set.of(DataOperation.CREATE) : Set.of(),
                    "@InternedContract: " + interning.get().creation());
                capabilitiesOrigin = FactOrigin.DERIVED;
            }
        } else {
            // Набор передаётся целиком и заменяет выведенный, поэтому origin — REGISTRATION
            // даже когда набор совпадает с правилом: объявление читается решением, а не
            // сравнением значений.
            capabilities = new EntityCapabilities(capabilityOverride.reads(), capabilityOverride.writes(),
                capabilityOverride.reason());
            capabilitiesOrigin = FactOrigin.REGISTRATION;
        }
        if (interning.isPresent() && (capabilities.allows(DataOperation.UPDATE)
                || capabilities.allows(DataOperation.DELETE)
                || (capabilities.allows(DataOperation.CREATE) && !interning.get().genericCreate()))) {
            throw new IllegalStateException("INTERNED_GENERIC_WRITES " + type.getName()
                + ": capabilities contradict @InternedContract creation channel");
        }
        return new EntityDescriptor(type, exposure, true, metadataDriven, capabilities, reason,
            exposureOrigin, exposureSymbol, capabilitiesOrigin,
            interning.isPresent() && capabilityOverride == null ? org.ipro.crud.InternedContract.class.getName() : "",
            owner);
    }

    private static boolean isMetadataDriven(Class<?> type, MetadataResolver metadataResolver) {
        return metadataResolver.find(type).isPresent();
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
