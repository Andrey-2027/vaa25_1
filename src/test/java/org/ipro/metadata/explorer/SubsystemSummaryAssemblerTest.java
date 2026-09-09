package org.ipro.metadata.explorer;

import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.metadata.facet.FactSource;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ip.model.ReceivingDocument;
import org.ip.model.User;
import org.ip.subsystem.Subsystems;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тест {@link SubsystemSummaryAssembler} (срез 2 «Структура подсистем»): дерево
 * подсистем (включая пустые) и сущности без подсистемы, грани строк (наименование,
 * нумерация, формы, фильтры, RLS-гейт) — на реальных сущностях приложения (basePackage
 * {@code org.ip}). RlsDimensionRegistry — стаб (готовые измерения без скана), чтобы тест
 * не зависел от содержимого БД/всех @RlsDimension-классов.
 */
class SubsystemSummaryAssemblerTest {

    private FormRegistry formRegistry;
    private SubsystemRegistry subsystems;
    private EntitySummaryAssembler entityAssembler;
    private SubsystemSummaryAssembler assembler;

    @BeforeEach
    void setUp() {
        MetadataResolver metadataResolver = new MetadataResolver();
        formRegistry = new FormRegistry();
        ReferenceIndex referenceIndex = new ReferenceIndex("org.ip");
        referenceIndex.afterPropertiesSet();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry("org.ip");
        numbering.afterPropertiesSet();
        subsystems = new SubsystemRegistry("org.ip");
        subsystems.afterPropertiesSet();
        entityAssembler = new EntitySummaryAssembler("org.ip", metadataResolver, formRegistry,
            referenceIndex, numbering, subsystems, FacetResolver.none());
        assembler = new SubsystemSummaryAssembler(entityAssembler, subsystems, rlsStub());
    }

    // ---------------------------------------------------------------- дерево

    @Test
    void catalogExposesTreeWithEmptyNodesInStableOrder() {
        List<SubsystemSummaryAssembler.SubsystemRef> refs = assembler.catalog().subsystems();

        int direct = indexOf(refs, Subsystems.Directories.class);
        int production = indexOf(refs, Subsystems.Production.class);
        int documents = indexOf(refs, Subsystems.Documents.class);
        assertThat(direct).isLessThan(production);
        assertThat(production).isLessThan(documents);

        // Directories и Production непусты (Номенклатура, Накладные).
        assertThat(refs.get(direct).entityCount()).isGreaterThan(0);
        assertThat(refs.get(production).entityCount()).isGreaterThan(0);

        // Дочерний узел Documents.ProductionDocuments: глубина, путь, пуст (структурная
        // диагностика: подсистема без сущностей видна и помечается «пусто» в UI).
        SubsystemSummaryAssembler.SubsystemRef prodDocs = refs.stream()
            .filter(r -> r.markerClass() == Subsystems.ProductionDocuments.class)
            .findFirst().orElseThrow();
        assertThat(prodDocs.depth()).isEqualTo(1);
        assertThat(prodDocs.path()).isEqualTo("Документы / Производство");
        assertThat(prodDocs.entityCount()).isZero();
    }

    @Test
    void catalogIsDeterministic() {
        SubsystemSummaryAssembler.Catalog first = assembler.catalog();
        SubsystemSummaryAssembler.Catalog second = assembler.catalog();

        assertThat(second).isEqualTo(first);
        // Корней столько же, сколько узлов каталога с depth == 0 (дочерние добавляются отдельно).
        assertThat(subsystems.getRoots()).hasSameSizeAs(
            second.subsystems().stream().filter(r -> r.depth() == 0).toList());
    }

    // ---------------------------------------------------------------- сущности без подсистемы

    @Test
    void unassignedListsEntitiesWithoutSubsystemDeterministically() {
        List<SubsystemSummaryAssembler.EntityFacet> unassigned = assembler.catalog().unassigned();

        assertThat(unassigned).isNotEmpty();
        assertThat(unassigned)
            .extracting(SubsystemSummaryAssembler.EntityFacet::entityClass)
            .anyMatch(c -> c == User.class);

        for (SubsystemSummaryAssembler.EntityFacet facet : unassigned) {
            EntityMetadata annotation = facet.entityClass().getAnnotation(EntityMetadata.class);
            assertThat(annotation.subsystem()).as("сущность %s должна быть без подсистемы",
                facet.simpleName()).isEqualTo(Subsystem.NoSubsystem.class);
        }

        assertThat(assembler.catalog().unassigned()).isEqualTo(unassigned);
    }

    @Test
    void unassignedGroupForNullMarker() {
        List<SubsystemSummaryAssembler.Group> groups = assembler.groupsOf(null);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).title()).isEqualTo("Без подсистемы");
        assertThat(groups.get(0).markerClass()).isNull();
        assertThat(groups.get(0).entities()).isEqualTo(assembler.catalog().unassigned());
    }

    // ---------------------------------------------------------------- грани строки

    @Test
    void productionGroupContainsReceivingDocumentWithFacets() {
        List<SubsystemSummaryAssembler.Group> groups = assembler.groupsOf(Subsystems.Production.class);

        assertThat(groups).isNotEmpty();
        SubsystemSummaryAssembler.EntityFacet facet = groups.stream()
            .flatMap(g -> g.entities().stream())
            .filter(f -> f.entityClass() == ReceivingDocument.class)
            .findFirst().orElseThrow();

        assertThat(facet.displayName().value()).isEqualTo("Приёмно-сдаточные накладные");
        assertThat(facet.displayName().source()).isEqualTo(FactSource.CODE);
        assertThat(facet.numbering()).isEqualTo("number (JOURNAL, YEAR)");
        assertThat(facet.rlsGate()).isTrue();
        // Пустой реестр форм: кастомных регистраций и фильтров нет.
        assertThat(facet.customForms()).isFalse();
        assertThat(facet.contextFilters()).isFalse();
    }

    @Test
    void emptySubsystemYieldsNoGroups() {
        assertThat(assembler.groupsOf(Subsystems.ProductionDocuments.class)).isEmpty();
    }

    @Test
    void facetsResolveDisplayNameThroughFacetResolver() {
        FacetResolver resolver = key -> {
            if (key.kind() == FacetKind.ENTITY_LIST_TITLE
                    && key.entityClass() == ReceivingDocument.class) {
                return Optional.of("Накладные (переопределено)");
            }
            return Optional.empty();
        };
        MetadataResolver metadataResolver = new MetadataResolver();
        NumberingMetadataRegistry numbering = new NumberingMetadataRegistry("org.ip");
        numbering.afterPropertiesSet();
        ReferenceIndex referenceIndex = new ReferenceIndex("org.ip");
        referenceIndex.afterPropertiesSet();
        EntitySummaryAssembler overriddenEntities = new EntitySummaryAssembler("org.ip",
            metadataResolver, formRegistry, referenceIndex, numbering, subsystems, resolver);
        SubsystemSummaryAssembler overridden =
            new SubsystemSummaryAssembler(overriddenEntities, subsystems, rlsStub());

        SubsystemSummaryAssembler.EntityFacet facet = overridden.groupsOf(Subsystems.Production.class)
            .stream()
            .flatMap(g -> g.entities().stream())
            .filter(f -> f.entityClass() == ReceivingDocument.class)
            .findFirst().orElseThrow();

        assertThat(facet.displayName().value()).isEqualTo("Накладные (переопределено)");
        assertThat(facet.displayName().source()).isEqualTo(FactSource.OVERRIDE);
    }

    @Test
    void groupsAreStableAndRegistriesUntouched() {
        List<SubsystemSummaryAssembler.Group> first =
            assembler.groupsOf(Subsystems.Production.class);
        List<SubsystemSummaryAssembler.Group> second =
            assembler.groupsOf(Subsystems.Production.class);

        assertThat(second).isEqualTo(first);
        int registrationsBefore = formRegistry.registrationsOf(ReceivingDocument.class).size();
        assembler.groupsOf(Subsystems.Production.class);
        assertThat(formRegistry.registrationsOf(ReceivingDocument.class))
            .hasSize(registrationsBefore);
    }

    // ---------------------------------------------------------------- помощники

    private static int indexOf(
            List<SubsystemSummaryAssembler.SubsystemRef> refs, Class<?> markerClass) {
        for (int i = 0; i < refs.size(); i++) {
            if (refs.get(i).markerClass() == markerClass) {
                return i;
            }
        }
        throw new AssertionError("Маркер не найден в каталоге: " + markerClass.getName());
    }

    private static RlsDimensionRegistry rlsStub() {
        return new RlsDimensionRegistry("org.ip") {
            @Override
            public Set<String> dimensions() {
                return Set.of("ENTITY:ReceivingDocument");
            }

            @Override
            public RlsDimensionKind kindOf(String dimension) {
                return RlsDimensionKind.CHECK_ONLY;
            }
        };
    }
}
