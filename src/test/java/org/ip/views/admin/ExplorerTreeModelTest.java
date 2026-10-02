package org.ip.views.admin;

import org.ip.model.Nomenclature;
import org.ip.model.NomAttributeValue;
import org.ip.model.ReceivingDocument;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityExposure;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E3.2.2 §4.2–4.4: проекция снимка в дерево — группы по kind и подсистемам, стабильные id,
 * непустые аспекты и разделы, owned-секции у своего root'а, явный отказ вместо пустого места,
 * фильтры пересечением, поиск по индексу снимка и счётчики диагностики снизу вверх.
 */
class ExplorerTreeModelTest {

    @Test
    void anOrphanRemainsVisibleAsASearchableDiagnosticWithoutAFakeRoot() {
        ExplorerTreeModel.CatalogView view = mock(ExplorerTreeModel.CatalogView.class);
        when(view.roots()).thenReturn(List.of());
        EntitySummary.DiagnosticRow row = new EntitySummary.DiagnosticRow(
            MetadataDiagnostic.Severity.ERROR, "EXPLORER_OWNER_MISSING", "example.Row", "", "",
            null, ResolvedValue.code("Строка example.Row: подтверждённая секция-владелец не найдена"), "");
        when(view.unassignedDiagnostics()).thenReturn(List.of(row));
        List<ExplorerTreeModel.Node> roots = ExplorerTreeModel.roots(view, ExplorerTreeModel.GroupMode.KIND);
        assertThat(roots).singleElement().satisfies(group -> {
            assertThat(group.label()).isEqualTo("Проблемы владельцев");
            assertThat(group.children()).singleElement().satisfies(node -> {
                assertThat(node.kind()).isEqualTo(ExplorerTreeModel.NodeKind.DIAGNOSTIC);
                assertThat(node.type()).isNull();
            });
        });
        assertThat(ExplorerTreeModel.withQuery(roots, List.of(), "example.Row")).hasSize(1);
        assertThat(ExplorerTreeModel.withQuery(roots, List.of(), "other.Row")).isEmpty();
    }

    /** Служебный тип: показывает переключатель служебных типов и фильтр экспозиции. */
    static class ServiceStore {
    }

    @Test
    void kindModeGroupsTypesUnderTheReviewedTitles() {
        ExplorerTreeModel.CatalogView view = view(
            List.of(entry(Nomenclature.class, "Номенклатура", EntityKind.CATALOG,
                    EntityExposure.STANDARD_ROOT),
                entry(ReceivingDocument.class, "Приём документов", EntityKind.DOCUMENT,
                    EntityExposure.STANDARD_ROOT)),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(), List.of()),
                ReceivingDocument.class, summary(ReceivingDocument.class, List.of(), List.of())),
            Map.of());

        List<ExplorerTreeModel.Node> groups = ExplorerTreeModel.roots(
            view, ExplorerTreeModel.GroupMode.KIND);

        assertThat(groups).extracting(ExplorerTreeModel.Node::label)
            .containsExactly("Справочники · ошибок: 0, предупреждений: 0",
                "Документы · ошибок: 0, предупреждений: 0");
        assertThat(groups).allSatisfy(group -> {
            assertThat(group.kind()).isEqualTo(ExplorerTreeModel.NodeKind.GROUP);
            assertThat(group.type())
                .as("группа не несёт фиктивный тип: выбор группы карточку не открывает")
                .isNull();
        });

        ExplorerTreeModel.Node catalog = groups.get(0).children().get(0);
        assertThat(catalog.id()).isEqualTo("type:" + Nomenclature.class.getName());
        assertThat(catalog.label())
            .isEqualTo("Номенклатура  (Nomenclature)  "
                + "[CATALOG · STANDARD_ROOT · ошибок: 0, предупреждений: 0]");
    }

    @Test
    void subsystemModeGroupsTheSameCatalogAndKeepsTheUnassignedGroupExplicit() {
        ExplorerSnapshot.Entry assigned = entry(Nomenclature.class, "Номенклатура",
            EntityKind.CATALOG, EntityExposure.STANDARD_ROOT,
            Optional.of(new ExplorerSnapshot.SubsystemRef("subsystems.Directories",
                ResolvedValue.code("Справочники"))));
        ExplorerSnapshot.Entry unassigned = entry(ReceivingDocument.class, "Приём документов",
            EntityKind.DOCUMENT, EntityExposure.STANDARD_ROOT, Optional.empty());
        ExplorerTreeModel.CatalogView view = view(List.of(assigned, unassigned),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(), List.of()),
                ReceivingDocument.class, summary(ReceivingDocument.class, List.of(), List.of())),
            Map.of());

        List<ExplorerTreeModel.Node> groups = ExplorerTreeModel.roots(
            view, ExplorerTreeModel.GroupMode.SUBSYSTEM);

        assertThat(groups).extracting(ExplorerTreeModel.Node::label)
            .containsExactly("Справочники · ошибок: 0, предупреждений: 0",
                "Без подсистемы · ошибок: 0, предупреждений: 0");
        assertThat(groups.get(0).id()).isEqualTo("subsystem:subsystems.Directories");
        assertThat(groups.get(1).id()).isEqualTo("subsystem:none");
    }

    @Test
    void ownedSectionsLiveUnderTheirRootAndUseTheConfirmedSectionId() {
        ExplorerSnapshot.OwnedSection owned = new ExplorerSnapshot.OwnedSection(
            Nomenclature.class, Nomenclature.class.getName(), "nomenclature",
            NomAttributeValue.class, NomAttributeValue.class.getName(), "NomAttributeValue",
            ResolvedValue.code("Атрибуты номенклатуры"), 1);
        ExplorerTreeModel.CatalogView view = view(
            List.of(entry(Nomenclature.class, "Номенклатура", EntityKind.CATALOG,
                EntityExposure.STANDARD_ROOT, List.of(owned))),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(),
                List.of(sectionRow(Nomenclature.class, "NomAttributeValue", "Атрибуты"))),
                NomAttributeValue.class, summary(NomAttributeValue.class, List.of(), List.of())),
            Map.of(Nomenclature.class, List.of(owned),
                NomAttributeValue.class, List.of(owned)));

        List<ExplorerTreeModel.Node> groups = ExplorerTreeModel.roots(
            view, ExplorerTreeModel.GroupMode.KIND);
        ExplorerTreeModel.Node type = groups.get(0).children().get(0);

        ExplorerTreeModel.Node fields = type.children().stream()
            .filter(node -> node.kind() == ExplorerTreeModel.NodeKind.ASPECT)
            .filter(node -> node.label().equals("Поля и колонки"))
            .findFirst().orElseThrow();
        ExplorerTreeModel.Node tableSections = fields.children().stream()
            .filter(node -> node.label().equals("Табличные части"))
            .findFirst().orElseThrow();
        assertThat(tableSections.children()).singleElement().satisfies(section -> {
            assertThat(section.kind()).isEqualTo(ExplorerTreeModel.NodeKind.OWNED_SECTION);
            assertThat(section.label()).isEqualTo("Атрибуты номенклатуры (NomAttributeValue)");
            assertThat(section.id())
                .as("id ведёт к подтверждённой секции снимка, а не к простому имени строки")
                .endsWith("#owned:" + owned.id());
        });
        assertThat(view.roots())
            .as("owned-строка не становится самостоятельным root'ом")
            .extracting(ExplorerSnapshot.Entry::type)
            .doesNotContain(NomAttributeValue.class);
    }

    @Test
    void aFailedSummaryIsShownAsAnExplicitDiagnosticInsteadOfAnEmptyType() {
        ExplorerTreeModel.CatalogView view = view(
            List.of(entry(Nomenclature.class, "Номенклатура", EntityKind.CATALOG,
                EntityExposure.STANDARD_ROOT, List.of(),
                ExplorerSnapshot.EntryState.BUILD_FAILED, "RLS-скан упал")),
            Map.of(), Map.of());

        ExplorerTreeModel.Node type = ExplorerTreeModel.roots(
            view, ExplorerTreeModel.GroupMode.KIND).get(0).children().get(0);

        assertThat(type.label()).endsWith("— сводка недоступна");
        assertThat(type.label())
            .as("отказ сборки — не «ошибок нет»: счётчики называются недоступными")
            .contains("диагностика недоступна");
        assertThat(type.children()).singleElement().satisfies(child -> {
            assertThat(child.kind()).isEqualTo(ExplorerTreeModel.NodeKind.DIAGNOSTIC);
            assertThat(child.label()).contains("RLS-скан упал");
        });
        assertThat(ExplorerTreeModel.roots(view, ExplorerTreeModel.GroupMode.KIND,
            new ExplorerTreeModel.Filter(null, null, null, true, false)))
            .as("отказ сборки остаётся виден при фильтре ошибок, даже с неизвестными счётчиками")
            .hasSize(1);
    }

    @Test
    void anUnavailableDescriptorIsNamedExplicitly() {
        ExplorerTreeModel.CatalogView view = view(
            List.of(entry(Nomenclature.class, "Номенклатура", EntityKind.CATALOG, null)),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(), List.of())),
            Map.of());

        ExplorerTreeModel.Node type = ExplorerTreeModel.roots(
            view, ExplorerTreeModel.GroupMode.KIND).get(0).children().get(0);

        assertThat(type.label())
            .contains("[CATALOG · descriptor неизвестен · ошибок: 0, предупреждений: 0]");
    }

    @Test
    void emptyAspectsAndSectionsAreNotDrawnAndFieldsCarryStableIds() {
        EntitySummary.FieldRow code = new EntitySummary.FieldRow(
            FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, "code"), "code", "String",
            ResolvedValue.code("Код"), false, false, FactOrigin.EXPLICIT, FactOrigin.EXPLICIT);
        ExplorerSnapshot.Entry entry = entry(Nomenclature.class, "Номенклатура", EntityKind.CATALOG,
            EntityExposure.STANDARD_ROOT);

        List<ExplorerTreeModel.Node> withField = ExplorerTreeModel.roots(
            view(List.of(entry), Map.of(Nomenclature.class,
                summary(Nomenclature.class, List.of(code), List.of())), Map.of()),
            ExplorerTreeModel.GroupMode.KIND);
        ExplorerTreeModel.Node type = withField.get(0).children().get(0);
        assertThat(type.children()).singleElement().satisfies(aspect -> {
            assertThat(aspect.label()).isEqualTo("Поля и колонки");
            assertThat(aspect.children()).singleElement().satisfies(section -> {
                assertThat(section.label()).isEqualTo("Поля — форма");
                assertThat(section.children()).singleElement().satisfies(field -> {
                    assertThat(field.kind()).isEqualTo(ExplorerTreeModel.NodeKind.FIELD);
                    assertThat(field.fieldName()).isEqualTo("code");
                    assertThat(field.label()).isEqualTo("Код (code)");
                    assertThat(field.id()).endsWith("#field:code");
                    assertThat(field.tabId()).isEqualTo("fields");
                    assertThat(field.sectionId())
                        .as("узел называет своё место у словаря карточки — виду есть куда вести")
                        .isEqualTo("form");
                });
            });
        });

        List<ExplorerTreeModel.Node> empty = ExplorerTreeModel.roots(
            view(List.of(entry), Map.of(Nomenclature.class,
                summary(Nomenclature.class, List.of(), List.of())), Map.of()),
            ExplorerTreeModel.GroupMode.KIND);
        assertThat(empty.get(0).children().get(0).children())
            .as("пустая сводка не рисует ни одного аспекта")
            .isEmpty();
    }

    @Test
    void theSameFactsProduceTheSameNodes() {
        ExplorerSnapshot.Entry entry = entry(Nomenclature.class, "Номенклатура", EntityKind.CATALOG,
            EntityExposure.STANDARD_ROOT);
        ExplorerTreeModel.CatalogView view = view(List.of(entry),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(), List.of())),
            Map.of());

        assertThat(ExplorerTreeModel.roots(view, ExplorerTreeModel.GroupMode.KIND))
            .isEqualTo(ExplorerTreeModel.roots(view, ExplorerTreeModel.GroupMode.KIND));
    }

    // ---------------------------------------------------------------- фильтры (§4.3)

    @Test
    void filtersCombineByIntersectionAndKeepOwnedSectionsWithTheirOwner() {
        ExplorerSnapshot.OwnedSection owned = ownedSection(Nomenclature.class,
            NomAttributeValue.class, "Атрибуты номенклатуры");
        ExplorerSnapshot.Entry catalog = entry(Nomenclature.class, "Номенклатура",
            EntityKind.CATALOG, EntityExposure.STANDARD_ROOT, List.of(owned),
            Optional.of(new ExplorerSnapshot.SubsystemRef("subsystems.Directories",
                ResolvedValue.code("Справочники"))),
            ExplorerSnapshot.EntryState.READY, "");
        ExplorerSnapshot.Entry document = entry(ReceivingDocument.class, "Приём документов",
            EntityKind.DOCUMENT, EntityExposure.STANDARD_ROOT);
        ExplorerSnapshot.Entry service = entry(ServiceStore.class, "Хранилище",
            EntityKind.PLAIN, EntityExposure.INTERNAL_STORE);
        ExplorerTreeModel.CatalogView view = view(List.of(catalog, document, service),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(),
                    List.of(sectionRow(Nomenclature.class, "NomAttributeValue", "Атрибуты"))),
                ReceivingDocument.class, summary(ReceivingDocument.class, List.of(), List.of()),
                ServiceStore.class, summary(ServiceStore.class, List.of(), List.of())),
            Map.of(Nomenclature.class, List.of(owned)));

        assertThat(ExplorerTreeModel.hasServiceTypes(view)).isTrue();

        List<ExplorerTreeModel.Node> all = ExplorerTreeModel.roots(
            view, ExplorerTreeModel.GroupMode.KIND, ExplorerTreeModel.Filter.all());
        assertThat(all).extracting(ExplorerTreeModel.Node::label)
            .as("нейтральный фильтр ничего не скрывает: root без ключа и служебный тип видны")
            .hasSize(3)
            .anySatisfy(label -> assertThat(label).contains("Справочники"))
            .anySatisfy(label -> assertThat(label).contains("Документы"))
            .anySatisfy(label -> assertThat(label).contains("Прочие сущности"));

        ExplorerTreeModel.Filter byKindAndSubsystem = new ExplorerTreeModel.Filter(
            EntityKind.CATALOG, "subsystems.Directories", null, false, true);
        List<ExplorerTreeModel.Node> onlyCatalog = ExplorerTreeModel.roots(
            view, ExplorerTreeModel.GroupMode.KIND, byKindAndSubsystem);
        assertThat(onlyCatalog).singleElement().satisfies(group -> {
            assertThat(group.label()).contains("Справочники");
            assertThat(group.children()).extracting(ExplorerTreeModel.Node::type)
                .containsExactly(Nomenclature.class);
            assertThat(group.children().get(0).children())
                .as("owned-секция остаётся у владельца, а не сравнивает свой OWNED_ROW с фильтром")
                .isNotEmpty();
        });

        ExplorerTreeModel.Filter serviceExposure = new ExplorerTreeModel.Filter(
            null, null, "INTERNAL_STORE", false, true);
        assertThat(ExplorerTreeModel.roots(view, ExplorerTreeModel.GroupMode.KIND, serviceExposure))
            .flatExtracting(ExplorerTreeModel.Node::children)
            .extracting(ExplorerTreeModel.Node::type)
            .containsExactly(ServiceStore.class);

        ExplorerTreeModel.Filter hiddenService = new ExplorerTreeModel.Filter(
            null, null, null, false, false);
        assertThat(ExplorerTreeModel.roots(view, ExplorerTreeModel.GroupMode.KIND, hiddenService))
            .flatExtracting(ExplorerTreeModel.Node::children)
            .extracting(ExplorerTreeModel.Node::type)
            .as("переключатель служебных типов скрывает INTERNAL_STORE/UNCLASSIFIED по умолчанию")
            .doesNotContain(ServiceStore.class)
            .contains(Nomenclature.class, ReceivingDocument.class);
    }

    @Test
    void onlyErrorsKeepsKnownErrorsAndDropsUnknownCounters() {
        ExplorerSnapshot.Entry failing = withCounts(entry(Nomenclature.class, "Номенклатура",
            EntityKind.CATALOG, EntityExposure.STANDARD_ROOT), 2, 1, true);
        ExplorerSnapshot.Entry clean = entry(ReceivingDocument.class, "Приём документов",
            EntityKind.DOCUMENT, EntityExposure.STANDARD_ROOT);
        ExplorerSnapshot.Entry unknown = withCounts(entry(ServiceStore.class, "Хранилище",
            EntityKind.PLAIN, EntityExposure.INTERNAL_STORE), 0, 0, false);
        ExplorerTreeModel.CatalogView view = view(List.of(failing, clean, unknown),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(), List.of()),
                ReceivingDocument.class, summary(ReceivingDocument.class, List.of(), List.of()),
                ServiceStore.class, summary(ServiceStore.class, List.of(), List.of())),
            Map.of());

        ExplorerTreeModel.Filter onlyErrors = new ExplorerTreeModel.Filter(
            null, null, null, true, true);

        assertThat(ExplorerTreeModel.roots(view, ExplorerTreeModel.GroupMode.KIND, onlyErrors))
            .flatExtracting(ExplorerTreeModel.Node::children)
            .extracting(ExplorerTreeModel.Node::type)
            .as("неизвестные счётчики не выдаются за известный ноль и за известную ошибку")
            .containsExactly(Nomenclature.class);
    }

    // ---------------------------------------------------------------- поиск (§4.3)

    @Test
    void searchFindsTypesFieldsSectionsAndOwnedFieldsThroughTheSnapshotIndex() {
        ExplorerSnapshot.OwnedSection owned = ownedSection(Nomenclature.class,
            NomAttributeValue.class, "Атрибуты номенклатуры");
        EntitySummary.FieldRow code = new EntitySummary.FieldRow(
            FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, "code"), "code", "String",
            ResolvedValue.code("Код"), false, false, FactOrigin.EXPLICIT, FactOrigin.EXPLICIT);
        EntitySummary.FieldRow article = new EntitySummary.FieldRow(
            FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, "article"), "article", "String",
            ResolvedValue.code("Артикул"), false, false, FactOrigin.EXPLICIT, FactOrigin.EXPLICIT);
        ExplorerSnapshot.Entry nomenclature = entry(Nomenclature.class, "Номенклатура",
            EntityKind.CATALOG, EntityExposure.STANDARD_ROOT, List.of(owned));
        ExplorerSnapshot.Entry document = entry(ReceivingDocument.class, "Приём документов",
            EntityKind.DOCUMENT, EntityExposure.STANDARD_ROOT);
        List<ExplorerSnapshot.SearchTerm> terms = List.of(
            new ExplorerSnapshot.SearchTerm(Nomenclature.class,
                ExplorerSnapshot.SearchKind.TYPE, "номенклатура", "", ""),
            new ExplorerSnapshot.SearchTerm(Nomenclature.class,
                ExplorerSnapshot.SearchKind.FIELD, "код", "", "code"),
            new ExplorerSnapshot.SearchTerm(Nomenclature.class,
                ExplorerSnapshot.SearchKind.SECTION, "атрибуты", owned.id(), ""),
            new ExplorerSnapshot.SearchTerm(Nomenclature.class,
                ExplorerSnapshot.SearchKind.FIELD, "ставка", owned.id(), "rate"));
        ExplorerTreeModel.CatalogView view = view(List.of(nomenclature, document),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(code, article),
                    List.of(sectionRow(Nomenclature.class, "NomAttributeValue", "Атрибуты"))),
                ReceivingDocument.class, summary(ReceivingDocument.class, List.of(), List.of())),
            Map.of(Nomenclature.class, List.of(owned)),
            terms);
        List<ExplorerTreeModel.Node> roots = ExplorerTreeModel.roots(view,
            ExplorerTreeModel.GroupMode.KIND, ExplorerTreeModel.Filter.all());

        List<ExplorerTreeModel.Node> byType = ExplorerTreeModel.withQuery(roots, terms,
            "номенклатура");
        assertThat(byType).singleElement().satisfies(group -> {
            assertThat(group.children()).extracting(ExplorerTreeModel.Node::type)
                .containsExactly(Nomenclature.class);
            assertThat(group.children().get(0).children())
                .as("совпавший тип показывает доступную структуру, а не только подпись")
                .isNotEmpty();
        });

        List<ExplorerTreeModel.Node> byField = ExplorerTreeModel.withQuery(roots, terms, "код");
        assertThat(byField).singleElement().satisfies(group -> {
            List<ExplorerTreeModel.Node> flat = flatten(group);
            assertThat(flat).anySatisfy(node -> {
                assertThat(node.kind()).isEqualTo(ExplorerTreeModel.NodeKind.FIELD);
                assertThat(node.fieldName()).isEqualTo("code");
            });
            assertThat(flat).extracting(ExplorerTreeModel.Node::fieldName)
                .as("соседнее поле не попадает в результат")
                .doesNotContain("article");
        });

        List<ExplorerTreeModel.Node> bySection = ExplorerTreeModel.withQuery(roots, terms,
            "атрибуты");
        assertThat(bySection).singleElement().satisfies(group ->
            assertThat(flatten(group)).anySatisfy(node -> {
                assertThat(node.kind()).isEqualTo(ExplorerTreeModel.NodeKind.OWNED_SECTION);
                assertThat(node.sectionId()).isEqualTo(owned.id());
            }));

        List<ExplorerTreeModel.Node> byOwnedField = ExplorerTreeModel.withQuery(roots, terms,
            "ставка");
        assertThat(flatten(byOwnedField.get(0)))
            .filteredOn(node -> node.kind() == ExplorerTreeModel.NodeKind.OWNED_SECTION)
            .singleElement()
            .satisfies(node -> assertThat(node.label())
                .as("найденное owned-поле остаётся подсказкой у секции владельца")
                .contains("— найдено поле: rate"));

        assertThat(ExplorerTreeModel.withQuery(roots, terms, "справочники"))
            .as("совпадение с названием группы не превращает все её типы в результаты")
            .isEmpty();
    }

    // ---------------------------------------------------------------- счётчики (§4.3)

    @Test
    void countersAggregateBottomUpAndCountProjectionsOnce() {
        EntitySummary.FieldRow form = new EntitySummary.FieldRow(
            FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, "code"), "code", "String",
            ResolvedValue.code("Код"), false, false, FactOrigin.EXPLICIT, FactOrigin.EXPLICIT);
        EntitySummary.FieldRow grid = new EntitySummary.FieldRow(
            FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, "code"), "code", "String",
            ResolvedValue.code("Код"), false, false, FactOrigin.EXPLICIT, FactOrigin.EXPLICIT);
        EntitySummary.DiagnosticRow row = new EntitySummary.DiagnosticRow(
            MetadataDiagnostic.Severity.ERROR, "FIELD_STRUCTURE", Nomenclature.class.getName(),
            "code", "ERROR [FIELD_STRUCTURE]", null,
            ResolvedValue.fact("поле объявлено дважды", FactOrigin.DERIVED, ""), "");
        ExplorerSnapshot.Entry first = withCounts(new ExplorerSnapshot.Entry(
            Nomenclature.class, Nomenclature.class.getName(), "Nomenclature",
            ResolvedValue.code("Номенклатура"), EntityKind.CATALOG,
            mockDescriptor(EntityExposure.STANDARD_ROOT), Optional.empty(), Optional.empty(),
            List.of(), ExplorerSnapshot.EntryState.READY, "", List.of(row), 1, 0, true), 1, 0, true);
        ExplorerSnapshot.Entry second = withCounts(entry(ReceivingDocument.class,
            "Приём документов", EntityKind.CATALOG, EntityExposure.STANDARD_ROOT), 2, 3, true);
        ExplorerSnapshot.Entry unknown = withCounts(entry(ServiceStore.class, "Хранилище",
            EntityKind.PLAIN, EntityExposure.INTERNAL_STORE), 0, 0, false);
        ExplorerTreeModel.CatalogView view = view(List.of(first, second, unknown),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(form), List.of(grid),
                    List.of()),
                ReceivingDocument.class, summary(ReceivingDocument.class, List.of(), List.of()),
                ServiceStore.class, summary(ServiceStore.class, List.of(), List.of())),
            Map.of());

        List<ExplorerTreeModel.Node> groups = ExplorerTreeModel.roots(view,
            ExplorerTreeModel.GroupMode.KIND, ExplorerTreeModel.Filter.all());
        ExplorerTreeModel.Node catalog = groups.get(0).children().get(0);

        assertThat(catalog.children().stream().flatMap(aspect -> aspect.children().stream())
            .flatMap(section -> section.children().stream()).toList())
            .as("одна запись диагностики видна у обеих проекций поля, но считается один раз")
            .filteredOn(node -> node.kind() == ExplorerTreeModel.NodeKind.FIELD
                && "code".equals(node.fieldName()))
            .hasSize(2);
        assertThat(catalog.label()).contains("ошибок: 1, предупреждений: 0");
        assertThat(groups.get(0).label())
            .as("счётчик группы — сумма записей её типов: 1 + 2 ошибки, 3 предупреждения")
            .contains("ошибок: 3, предупреждений: 3");
        assertThat(groups.get(1).label())
            .as("недоступная диагностика одного типа делает группу явно неизвестной")
            .contains("диагностика недоступна");
        ExplorerTreeModel.CatalogView searchable = view(view.roots(),
            Map.of(Nomenclature.class, summary(Nomenclature.class, List.of(form), List.of(grid), List.of())),
            Map.of(), List.of(new ExplorerSnapshot.SearchTerm(Nomenclature.class,
                ExplorerSnapshot.SearchKind.TYPE, "номенклатура", "", "")));
        assertThat(ExplorerTreeModel.withQuery(groups, searchable, "номенклатура"))
            .as("после поиска счётчик группы включает только оставшиеся типы")
            .singleElement().satisfies(group -> {
                assertThat(group.children()).hasSize(1);
                assertThat(group.label()).contains("ошибок: 1, предупреждений: 0");
            });
    }

    // ---------------------------------------------------------------- фикстуры

    private static ExplorerTreeModel.CatalogView view(
            List<ExplorerSnapshot.Entry> roots,
            Map<Class<?>, EntitySummary> summaries,
            Map<Class<?>, List<ExplorerSnapshot.OwnedSection>> sections) {
        return view(roots, summaries, sections, List.of());
    }

    private static ExplorerTreeModel.CatalogView view(
            List<ExplorerSnapshot.Entry> roots,
            Map<Class<?>, EntitySummary> summaries,
            Map<Class<?>, List<ExplorerSnapshot.OwnedSection>> sections,
            List<ExplorerSnapshot.SearchTerm> terms) {
        return new ExplorerTreeModel.CatalogView() {
            @Override
            public List<ExplorerSnapshot.Entry> roots() {
                return roots;
            }

            @Override
            public Optional<EntitySummary> summaryOf(Class<?> type) {
                return Optional.ofNullable(summaries.get(type));
            }

            @Override
            public List<ExplorerSnapshot.OwnedSection> sectionsOf(Class<?> type) {
                return sections.getOrDefault(type, List.of());
            }

            @Override
            public List<ExplorerSnapshot.SearchTerm> searchTerms() {
                return terms;
            }
        };
    }

    private static List<ExplorerTreeModel.Node> flatten(ExplorerTreeModel.Node node) {
        List<ExplorerTreeModel.Node> all = new java.util.ArrayList<>();
        all.add(node);
        node.children().forEach(child -> all.addAll(flatten(child)));
        return all;
    }

    private static ExplorerSnapshot.Entry entry(Class<?> type, String display, EntityKind kind,
                                                EntityExposure exposure) {
        return entry(type, display, kind, exposure, List.of(), Optional.empty(),
            ExplorerSnapshot.EntryState.READY, "");
    }

    private static ExplorerSnapshot.Entry entry(Class<?> type, String display, EntityKind kind,
                                                EntityExposure exposure,
                                                List<ExplorerSnapshot.OwnedSection> owned) {
        return entry(type, display, kind, exposure, owned, Optional.empty(),
            ExplorerSnapshot.EntryState.READY, "");
    }

    private static ExplorerSnapshot.Entry entry(Class<?> type, String display, EntityKind kind,
                                                EntityExposure exposure,
                                                Optional<ExplorerSnapshot.SubsystemRef> subsystem) {
        return entry(type, display, kind, exposure, List.of(), subsystem,
            ExplorerSnapshot.EntryState.READY, "");
    }

    private static ExplorerSnapshot.Entry entry(Class<?> type, String display, EntityKind kind,
                                                EntityExposure exposure,
                                                List<ExplorerSnapshot.OwnedSection> owned,
                                                ExplorerSnapshot.EntryState state,
                                                String failureReason) {
        return entry(type, display, kind, exposure, owned, Optional.empty(), state, failureReason);
    }

    private static ExplorerSnapshot.Entry entry(Class<?> type, String display, EntityKind kind,
                                                EntityExposure exposure,
                                                List<ExplorerSnapshot.OwnedSection> owned,
                                                Optional<ExplorerSnapshot.SubsystemRef> subsystem,
                                                ExplorerSnapshot.EntryState state,
                                                String failureReason) {
        return new ExplorerSnapshot.Entry(type, type.getName(), type.getSimpleName(),
            ResolvedValue.code(display), kind, descriptor(exposure), subsystem, Optional.empty(),
            owned, state, failureReason, List.of(), 0, 0, true);
    }

    private static ExplorerSnapshot.Entry withCounts(ExplorerSnapshot.Entry entry,
                                                     int errors, int warnings, boolean known) {
        return new ExplorerSnapshot.Entry(entry.type(), entry.typeFqn(), entry.simpleName(),
            entry.displayName(), entry.kind(), entry.descriptor(), entry.subsystem(),
            entry.publishedKey(), entry.ownedSections(), entry.state(), entry.failureReason(),
            entry.diagnostics(), errors, warnings, known);
    }

    private static EntityDescriptor descriptor(EntityExposure exposure) {
        if (exposure == null) {
            return null;
        }
        return mockDescriptor(exposure);
    }

    private static EntityDescriptor mockDescriptor(EntityExposure exposure) {
        EntityDescriptor descriptor = mock(EntityDescriptor.class);
        when(descriptor.exposure()).thenReturn(exposure);
        return descriptor;
    }

    private static ExplorerSnapshot.OwnedSection ownedSection(Class<?> owner, Class<?> row,
                                                              String label) {
        return new ExplorerSnapshot.OwnedSection(owner, owner.getName(), "rows", row,
            row.getName(), row.getSimpleName(), ResolvedValue.code(label), 1);
    }

    private static EntitySummary summary(Class<?> type, List<EntitySummary.FieldRow> formFields,
                                         List<EntitySummary.SectionRow> tableSections) {
        return summary(type, formFields, List.of(), tableSections);
    }

    private static EntitySummary summary(Class<?> type, List<EntitySummary.FieldRow> formFields,
                                         List<EntitySummary.FieldRow> gridFields,
                                         List<EntitySummary.SectionRow> tableSections) {
        return new EntitySummary(type, type.getSimpleName(), ResolvedValue.code(type.getSimpleName()),
            List.of(), formFields, gridFields, List.of(), List.of(), tableSections,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static EntitySummary.SectionRow sectionRow(Class<?> type, String rowClass,
                                                       String label) {
        return new EntitySummary.SectionRow(
            FacetKey.of(FacetKind.TABLE_SECTION, type, rowClass),
            ResolvedValue.code(label), rowClass, 1, 0, 2, 1);
    }
}
