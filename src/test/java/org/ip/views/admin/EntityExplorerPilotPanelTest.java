package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityExplorerPilotPanelTest {
    @Test void ownerPagePresentsReadyCapabilityAndDoesNotDescribeItAsHierarchy() {
        var s=summary();var structure=new EntitySummary.StructureFacts(s.structure().available(),s.structure().fields(),s.structure().sections(),s.structure().presentation(),
            new org.ipro.metadata.CatalogOwnerDescriptor("counterparty","example.Counterparty"));
        var ownerSummary=new EntitySummary(s.entityClass(),s.simpleName(),s.displayName(),s.overview(),s.fieldsForm(),s.fieldsGrid(),s.listColumns(),s.selectColumns(),s.tableSections(),
            s.forms(),s.contextFilters(),s.selections(),s.references(),s.numbering(),s.lifecycle(),s.diagnostics(),s.actions(),s.readPlans(),s.accessRows(),s.lookupTargets(),s.readPlanInspectionAvailable(),structure);
        var panel=new EntityExplorerPilotPanel(ownerSummary,new ExplorerUiState(),()->{},anchor->{});
        panel.chooseTab(EntityExplorerPilotPlace.Tab.OWNERS);
        assertThat(text(panel)).contains("counterparty → example.Counterparty","@CatalogOwner", "Права не наследуются");
        var unknown=new EntitySummary(s.entityClass(),s.simpleName(),s.displayName(),s.overview(),s.fieldsForm(),s.fieldsGrid(),s.listColumns(),s.selectColumns(),s.tableSections(),
            s.forms(),s.contextFilters(),s.selections(),s.references(),s.numbering(),s.lifecycle(),s.diagnostics(),s.actions(),s.readPlans(),s.accessRows(),s.lookupTargets(),s.readPlanInspectionAvailable(),EntitySummary.StructureFacts.unavailable());
        var unknownPanel=new EntityExplorerPilotPanel(unknown,new ExplorerUiState(),()->{},anchor->{});
        unknownPanel.chooseTab(EntityExplorerPilotPlace.Tab.OWNERS);
        assertThat(text(unknownPanel)).contains("Сведения о владельце недоступны");
    }
    @Test
    void simplePagesUseDeclarationFactsAndRestoreTheirOwnPlaceForBothKinds() {
        var numbering = new NumberingMetadataRegistry("org.ip.model");
        numbering.rebuild();
        var assembler = new EntitySummaryAssembler("org.ip.model", new MetadataResolver(), new FormRegistry(),
            new ReferenceIndex("org.ip.model"), numbering, new SubsystemRegistry("org.ip", new org.ipro.metadata.MetadataResolver()), FacetResolver.none());
        for (Class<?> type : List.of(Nomenclature.class, ReceivingDocument.class)) {
            var summary = assembler.summarize(type);
            var state = new ExplorerUiState();
            var panel = new EntityExplorerPilotPanel(summary, state, () -> {}, anchor -> {});
            panel.chooseTab(EntityExplorerPilotPlace.Tab.PRESENTATION);
            var facts = summary.structure().presentation();
            assertThat(facts.icon()).isNotBlank();
            if (type == Nomenclature.class) {
                assertThat(facts.methodSymbol()).isEqualTo(Nomenclature.class.getName() + "#getDisplayName");
                assertThat(facts.sortFields()).containsExactly("code", "name");
                button(panel, "Код getDisplayName()").click();
                assertThat(text(panel)).contains("Исходник недоступен");
                button(panel, "Назад к представлению").click();
            } else {
                assertThat(facts.strategy()).contains("@InstanceName", "number, date", " от ");
                assertThat(facts.methodSymbol()).isEmpty();
            }
            panel.chooseTab(EntityExplorerPilotPlace.Tab.NUMBERING);
            assertThat(state.pilotPlaceOf(type).tab()).isEqualTo(EntityExplorerPilotPlace.Tab.NUMBERING);
            assertThat(summary.numbering()).isNotEmpty().allSatisfy(row -> {
                assertThat(row.definition()).isNotNull();
                assertThat(row.policySource()).contains("@Numbered");
            });
            assertThat(text(panel)).contains("Операционный режим не предоставлен", "Операционные переопределения");
            if (type == ReceivingDocument.class) {
                var policy = summary.numbering().getFirst().definition();
                assertThat(policy.scope()).containsExactly("JOURNAL");
                assertThat(policy.period()).isEqualTo(org.ipro.numbering.NumberingPeriod.YEAR);
                assertThat(policy.dateField()).isEqualTo("date");
                assertThat(policy.prefix()).isEqualTo("РН-");
                assertThat(policy.pattern()).isEqualTo("{prefix}{yyyy}-{seq:000000}");
            }
            var restored = new EntityExplorerPilotPanel(summary, state, () -> {}, anchor -> {});
            assertThat(descendants(restored).filter(c -> "numbering".equals(c.getElement().getAttribute("data-tab"))))
                .singleElement().satisfies(c -> assertThat(c.getElement().getAttribute("aria-pressed")).isEqualTo("true"));
        }
    }

    @Test
    void sequentialReturnPreservesSelectionExpansionAndSupportsASecondSelection() {
        var panel = new EntityExplorerPilotPanel(summary(), new ExplorerUiState(), () -> {}, anchor -> {});
        panel.chooseTab(EntityExplorerPilotPlace.Tab.DATA);
        SplitLayout split = descendants(panel).filter(SplitLayout.class::isInstance).map(SplitLayout.class::cast).findFirst().orElseThrow();
        assertThat(split.getElement().hasAttribute("data-detail")).isFalse();
        panel.focusField("name");
        assertThat(split.getElement().hasAttribute("data-detail")).isTrue();
        button(panel, "Назад к списку").click();
        assertThat(split.getElement().hasAttribute("data-detail")).isFalse();
        assertThat(descendants(panel).filter(c -> "true".equals(c.getElement().getAttribute("aria-selected"))))
            .singleElement().satisfies(c -> assertThat(c.getElement().getAttribute("data-field")).isEqualTo("name"));
        panel.focusField("code");
        assertThat(split.getElement().hasAttribute("data-detail")).isTrue();
    }

    @Test
    void nonPilotKindsAndDisabledFlagAlwaysKeepTheLegacyRenderer() {
        var access = mock(EntityExplorerAccess.class);
        when(access.allows()).thenReturn(true);
        for (boolean enabled : List.of(true, false)) {
            var view = new EntityExplorerView(ExplorerTreeModel.CatalogView.of(assembler().explorerSnapshot()),
                List.of(), mock(FormNavigator.class), access, enabled);
            var host = (com.vaadin.flow.component.orderedlayout.VerticalLayout) ReflectionTestUtils.getField(view, "cardHost");
            for (Class<?> type : List.of(PrdSpec.class, org.ip.model.NomenclaturePrice.class)) {
                view.init(type);
                assertThat(host.getComponentAt(0)).isInstanceOf(EntitySummaryPanel.class);
            }
            view.init(Nomenclature.class);
            assertThat(host.getComponentAt(0)).isInstanceOf(enabled ? EntityExplorerPilotPanel.class : EntitySummaryPanel.class);
        }
    }

    @Test
    void formFactsShowTheTwoDeclaredClassesWithoutInvokingTheirFactories() {
        var registry = new FormRegistry();
        registry.registerItemForm(Nomenclature.class, (String) null,
            context -> { throw new AssertionError("Metadata must not run a form factory"); },
            "org.ip.views.forms.NomenclatureItemFormConfig", org.ip.views.forms.NomenclatureItemForm.class);
        registry.registerItemForm(ReceivingDocument.class, (String) null,
            context -> { throw new AssertionError("Metadata must not run a form factory"); },
            "org.ip.views.forms.ReceivingDocumentFormConfig", org.ipro.form.builtin.ItemForm.class);
        registry.registerItemForm(Nomenclature.class, "unknown", context -> { throw new AssertionError("Factory invoked"); });
        var assembler = new EntitySummaryAssembler("org.ip.model", new MetadataResolver(), registry,
            new ReferenceIndex("org.ip.model"), new NumberingMetadataRegistry("org.ip.model"),
            new SubsystemRegistry("org.ip", new org.ipro.metadata.MetadataResolver()), FacetResolver.none());
        var nom = assembler.summarize(Nomenclature.class);
        var doc = assembler.summarize(ReceivingDocument.class);
        assertThat(nom.forms()).filteredOn(f -> f.formType().name().equals("ITEM") && f.variant() == null)
            .singleElement().satisfies(f -> {
                assertThat(f.formClass()).isEqualTo(org.ip.views.forms.NomenclatureItemForm.class);
                assertThat(f.baseClass()).isEqualTo("ItemForm<Nomenclature>");
                assertThat(f.registrationClasses()).containsExactly("org.ip.views.forms.NomenclatureItemFormConfig");
            });
        assertThat(doc.forms()).filteredOn(f -> f.formType().name().equals("ITEM"))
            .singleElement().satisfies(f -> assertThat(f.formClass()).isEqualTo(org.ipro.form.builtin.ItemForm.class));
        var state = new ExplorerUiState();
        var panel = new EntityExplorerPilotPanel(nom, state, () -> {}, anchor -> {});
        panel.chooseTab(EntityExplorerPilotPlace.Tab.FORMS);
        assertThat(text(panel)).contains("NomenclatureItemForm", "Рецепт / шаблон", "Генератор не подключён");
        button(panel, "Код класса").click();
        assertThat(text(panel)).contains("Исходник недоступен");
        button(panel, "Назад к формам").click();
        assertThat(state.behaviorSelection(Nomenclature.class, EntityExplorerPilotPlace.Tab.FORMS)).isEqualTo("ITEM/default");
        var forms = descendants(panel).filter(ExplorerBehaviorPanel.class::isInstance).findFirst().orElseThrow();
        for (String standard : List.of("LIST/default", "SELECTION/default")) {
            ReflectionTestUtils.invokeMethod(forms, "choose", standard, false);
            assertThat(descendants(forms).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.getText().equals("Код класса"))).isEmpty();
        }
        var document = new EntityExplorerPilotPanel(doc, new ExplorerUiState(), () -> {}, anchor -> {});
        document.chooseTab(EntityExplorerPilotPlace.Tab.FORMS);
        assertThat(descendants(document).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(b -> b.getText().equals("Код класса"))).isEmpty();
        assertThat(button(document, "Код регистрации")).isNotNull();
    }

    @Test
    void lifecycleSelectionChangesTheConfirmedSymbolAndUnavailableStatusWithoutCallingHooks() {
        var summary = mock(EntitySummary.class);
        org.mockito.Mockito.doReturn(Nomenclature.class).when(summary).entityClass();
        when(summary.simpleName()).thenReturn("Nomenclature");
        var before = new EntitySummary.LifecycleRow(org.ipro.metadata.facet.FacetKey.of(org.ipro.metadata.facet.FacetKind.ENTITY_LIFECYCLE_HOOK, Nomenclature.class, "beforeAggregateSave"),
            "beforeAggregateSave", true, org.ipro.metadata.facet.ResolvedValue.fact("есть", org.ipro.metadata.FactOrigin.EXPLICIT,
                "org.ip.application.catalog.NomenclatureLifecycle#beforeAggregateSave"), "");
        var after = new EntitySummary.LifecycleRow(org.ipro.metadata.facet.FacetKey.of(org.ipro.metadata.facet.FacetKind.ENTITY_LIFECYCLE_HOOK, Nomenclature.class, "afterCommit"),
            "afterCommit", false, org.ipro.metadata.facet.ResolvedValue.fact("default", org.ipro.metadata.FactOrigin.PLATFORM_DEFAULT,
                "org.ipro.lifecycle.EntityLifecycle#afterCommit"), "");
        when(summary.lifecycle()).thenReturn(List.of(before, after));
        var state = new ExplorerUiState();
        var panel = new ExplorerBehaviorPanel(summary, state, EntityExplorerPilotPlace.Tab.LIFECYCLE,
            ExplorerSourceReader.disabled(new EntityExplorerAccess() { @Override public boolean allows() { return true; } }), text -> {});
        assertThat(text(panel)).contains("NomenclatureLifecycle#beforeAggregateSave", "Исходник недоступен");
        ReflectionTestUtils.invokeMethod(panel, "choose", "afterCommit", false);
        assertThat(text(panel)).contains("EntityLifecycle#afterCommit", "После успешного commit");
        // A reconstructed panel restores the UI-local hook independently of form selection.
        var restored = new ExplorerBehaviorPanel(summary, state, EntityExplorerPilotPlace.Tab.LIFECYCLE,
            ExplorerSourceReader.disabled(new EntityExplorerAccess() { @Override public boolean allows() { return true; } }), text -> {});
        assertThat(text(restored)).contains("EntityLifecycle#afterCommit", "После успешного commit", "Default контракта");
    }
    @Test
    void equalRootAndRowNamesSelectOnlyTheirOwnDefinition() {
        var original = summary();
        var section = original.structure().sections().getFirst();
        var root = original.structure().fields().getFirst();
        var row = section.fields().getFirst();
        var sameName = new EntitySummary.StructuralFieldRow(
            new EntitySummary.FieldIdentity(Nomenclature.class, section.identity(), root.identity().fieldName()),
            row.key(), row.declaringType(), row.javaType(), row.platformType(), row.value(), row.order(),
            row.hidden(), row.readOnly(), row.uiRequired(), row.serverRequired(), row.valueConstraints(),
            row.storage(), row.applications(), row.lookup(), row.published(),
            row.persistentKind(), row.accessMode(), row.technicalPurpose());
        var modifiedSection = new EntitySummary.OwnedSectionRow(section.identity(), section.key(), section.value(),
            section.order(), section.minRows(), section.lineNumberField(), section.persistence(), section.rlsPolicy(), List.of(sameName));
        var fixture = new EntitySummary(original.entityClass(), original.simpleName(), original.displayName(),
            original.overview(), original.fieldsForm(), original.fieldsGrid(), original.listColumns(), original.selectColumns(),
            original.tableSections(), original.forms(), original.contextFilters(), original.selections(), original.references(),
            original.numbering(), original.lifecycle(), original.diagnostics(), original.actions(), original.readPlans(),
            original.accessRows(), original.lookupTargets(), original.readPlanInspectionAvailable(),
            new EntitySummary.StructureFacts(true, original.structure().fields(), List.of(modifiedSection)));
        var state = new ExplorerUiState();
        var panel = new EntityExplorerPilotPanel(fixture, state, () -> { }, anchor -> { });
        var target = new EntityExplorerPilotPlace.OwnedSection(section.identity().ownerField(), section.identity().rowType());
        panel.focusField("code");
        assertThat(text(panel)).contains("Код (code)");
        panel.focusSectionField(target, "code");
        assertThat(text(panel)).contains("Номенклатура (code)");
        assertThat(descendants(panel).filter(c -> "true".equals(c.getElement().getAttribute("aria-selected"))))
            .singleElement().satisfies(c -> assertThat(c.getElement().getAttribute("data-context")).isEqualTo(section.identity().id()));
        panel.focusField("code");
        assertThat(state.pilotPlaceOf(Nomenclature.class).target()).isEqualTo(new EntityExplorerPilotPlace.RootField("code"));
    }

    @Test
    void ownedSearchHitOpensItsFieldInsideThePilotWithoutChangingAddressGrammar() {
        var assembler = assembler();
        var access = mock(EntityExplorerAccess.class);
        when(access.allows()).thenReturn(true);
        var view = new EntityExplorerView(ExplorerTreeModel.CatalogView.of(assembler.explorerSnapshot()),
            List.of(), mock(FormNavigator.class), access, true);
        view.init(Nomenclature.class);
        var section = summary().structure().sections().getFirst();
        var node = new ExplorerTreeModel.Node(ExplorerTreeModel.NodeKind.OWNED_SECTION, "owned-search-hit",
            "Search result", Nomenclature.class, "fields", section.identity().id(), "NomAttributeValue", List.of(), List.of("attrType"));
        ReflectionTestUtils.invokeMethod(view, "onTreeSelection", node);
        var host = (com.vaadin.flow.component.orderedlayout.VerticalLayout) ReflectionTestUtils.getField(view, "cardHost");
        assertThat(host.getComponentAt(0)).isInstanceOf(EntityExplorerPilotPanel.class);
        assertThat(text(host)).contains("Тип атрибута (attrType)", "Nomenclature / Атрибуты номенклатуры / attrType");
    }
    @Test
    void sectionCollapseRestoresVisibleSelectionAndReopeningRecoversLastField() {
        var summary = summary();
        var state = new ExplorerUiState();
        var panel = new EntityExplorerPilotPanel(summary, state, () -> { }, anchor -> { });
        var section = summary.structure().sections().getFirst();
        var target = new EntityExplorerPilotPlace.OwnedSection(section.identity().ownerField(), section.identity().rowType());
        panel.focusSectionField(target, "attrType");
        assertThat(text(panel)).contains("Тип атрибута (attrType)", "Nomenclature / " + section.value().value() + " / attrType");
        assertThat(state.sectionOpened(Nomenclature.class, target)).isTrue();
        Button toggle = descendants(panel).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(b -> b.getElement().hasAttribute("data-section-toggle")).findFirst().orElseThrow();
        toggle.click();
        assertThat(state.sectionOpened(Nomenclature.class, target)).isFalse();
        assertThat(state.pilotPlaceOf(Nomenclature.class).target()).isEqualTo(target);
        assertThat(text(panel)).contains("Табчасть:", "Класс строки", "Поле связи", "NomAttributeValue");
        toggle.click();
        assertThat(state.pilotPlaceOf(Nomenclature.class).target())
            .isEqualTo(new EntityExplorerPilotPlace.SectionField(target, "attrType"));
        var rebuilt = new EntityExplorerPilotPanel(summary, state, () -> { }, anchor -> { });
        assertThat(text(rebuilt)).contains("Тип атрибута (attrType)");
        assertThat(state.sectionOpened(Nomenclature.class, target)).isTrue();
    }

    @Test
    void rootAndRowFieldsOwnersAndContextsRemainDistinctAndClearTogether() {
        var state = new ExplorerUiState();
        var nom = summary();
        var doc = assembler().summarize(ReceivingDocument.class);
        var panel = new EntityExplorerPilotPanel(nom, state, () -> { }, anchor -> { });
        var other = new EntityExplorerPilotPanel(doc, state, () -> { }, anchor -> { });
        var section = nom.structure().sections().getFirst();
        var target = new EntityExplorerPilotPlace.OwnedSection(section.identity().ownerField(), section.identity().rowType());
        panel.focusSectionField(target, "nomenclature");
        panel.chooseTab(EntityExplorerPilotPlace.Tab.OWNERS);
        assertThat(text(panel)).contains("Явный контракт не подключён", "parentField = \"nomenclature\"");
        button(panel, "NomAttributeValue").click();
        assertThat(state.pilotPlaceOf(Nomenclature.class).target()).isEqualTo(target);
        button(panel, "Родитель / Владельцы").click();
        button(panel, "nomenclature → Nomenclature").click();
        assertThat(state.pilotPlaceOf(Nomenclature.class).target())
            .isEqualTo(new EntityExplorerPilotPlace.SectionField(target, "nomenclature"));
        var docSection = doc.structure().sections().getFirst();
        var docTarget = new EntityExplorerPilotPlace.OwnedSection(docSection.identity().ownerField(), docSection.identity().rowType());
        other.focusSectionField(docTarget, "nomenclature");
        assertThat(text(other)).contains("ReceivingDocument / Позиции / nomenclature");
        other.chooseTab(EntityExplorerPilotPlace.Tab.OWNERS);
        assertThat(text(other)).contains("document → ReceivingDocument · не размечено как реквизит");
        assertThat(state.pilotPlaceOf(Nomenclature.class).target()).isInstanceOf(EntityExplorerPilotPlace.SectionField.class);
        var sameRowOtherRoot = new EntityExplorerPilotPlace.OwnedSection(target.ownerField(), target.rowType());
        assertThat(state.sectionOpened(ReceivingDocument.class, sameRowOtherRoot)).isFalse();
        state.clear();
        assertThat(state.sectionOpened(Nomenclature.class, target)).isFalse();
        assertThat(state.sectionField(Nomenclature.class, target)).isNull();
        assertThat(state.pilotPlaceOf(Nomenclature.class).target()).isNull();
    }

    @Test
    void missingSavedChildFallsBackToItsSectionWithAnExplanation() {
        var state = new ExplorerUiState();
        var section = summary().structure().sections().getFirst();
        var target = new EntityExplorerPilotPlace.OwnedSection(section.identity().ownerField(), section.identity().rowType());
        state.rememberPilotPlace(new EntityExplorerPilotPlace(Nomenclature.class, EntityExplorerPilotPlace.Tab.DATA,
            new EntityExplorerPilotPlace.SectionField(target, "removedField")));
        var panel = new EntityExplorerPilotPanel(summary(), state, () -> { }, anchor -> { });
        assertThat(text(panel)).contains("Сохранённый элемент недоступен", "Табчасть:");
        assertThat(state.pilotPlaceOf(Nomenclature.class).target()).isEqualTo(target);
    }

    @Test
    void selectingNameShowsItsPropertiesAndKeepsThePropertyPresentersSeparate() {
        var state = new ExplorerUiState();
        var panel = new EntityExplorerPilotPanel(summary(), state, () -> { }, anchor -> { });
        panel.focusField("name");
        assertThat(text(panel)).contains("Наименование (name)", "До 200 (@Size)", "Значение обязательно",
            "По метаданным", "Nomenclature / Реквизиты / name");
        assertThat(state.pilotPlaceOf(Nomenclature.class).target())
            .isEqualTo(new EntityExplorerPilotPlace.RootField("name"));
        assertThat(descendants(panel).filter(c -> "size".equals(c.getElement().getAttribute("data-property"))))
            .hasSize(1);
        assertThat(descendants(panel).filter(c -> "name".equals(c.getElement().getAttribute("data-field"))))
            .singleElement().satisfies(c -> assertThat(c.getElement().getAttribute("aria-selected")).isEqualTo("true"));
    }

    @Test
    void localTabsSelectionSourcesAndSplitterSurviveReturningFromTechnicalSummary() {
        var state = new ExplorerUiState();
        List<String> anchors = new ArrayList<>();
        AtomicInteger catalogs = new AtomicInteger();
        var panel = new EntityExplorerPilotPanel(summary(), state, catalogs::incrementAndGet, anchors::add);
        panel.focusField("name");
        descendants(panel).filter(Details.class::isInstance).map(Details.class::cast).findFirst().orElseThrow().setOpened(true);
        var split = descendants(panel).filter(SplitLayout.class::isInstance).map(SplitLayout.class::cast).findFirst().orElseThrow();
        split.setSplitterPosition(55);
        panel.focusField("code");
        assertThat(descendants(panel).filter(Details.class::isInstance).map(Details.class::cast).findFirst().orElseThrow().isOpened()).isTrue();
        button(panel, "Техническая сводка").click();
        assertThat(anchors).containsExactly("overview");
        button(panel, "Сущности ▾").click();
        assertThat(catalogs.get()).isEqualTo(1);
        assertThat(split.getSplitterPosition()).isEqualTo(55);
        assertThat(state.pilotPlaceOf(Nomenclature.class).target()).isEqualTo(new EntityExplorerPilotPlace.RootField("code"));
    }

    @Test
    void pilotAndAnchoredTechnicalSummaryShareOneHostAndRestoreTheLocalField() {
        var assembler = assembler();
        var access = mock(EntityExplorerAccess.class);
        when(access.allows()).thenReturn(true);
        var view = new EntityExplorerView(ExplorerTreeModel.CatalogView.of(assembler.explorerSnapshot()),
            List.of(), mock(FormNavigator.class), access, true);
        var host = (com.vaadin.flow.component.orderedlayout.VerticalLayout) ReflectionTestUtils.getField(view, "cardHost");
        List<String> addresses = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> addresses.add(type.getSimpleName() + ":" + anchor));
        view.init(Nomenclature.class);
        var panel = (EntityExplorerPilotPanel) host.getComponentAt(0);
        panel.focusField("name");
        button(panel, "Техническая сводка").click();
        assertThat(host.getComponentAt(0)).isInstanceOf(EntitySummaryPanel.class);
        assertThat(addresses).containsExactly("Nomenclature:overview");
        button(view, "К компактной карточке").click();
        assertThat(host.getComponentCount()).isEqualTo(1);
        assertThat(host.getComponentAt(0)).isSameAs(panel);
        assertThat(text(panel)).contains("Наименование (name)");
        assertThat(addresses).containsExactly("Nomenclature:overview", "Nomenclature:null");
        view.init(Nomenclature.class, "fields/form");
        assertThat(host.getComponentAt(0)).isInstanceOf(EntitySummaryPanel.class);
        view.init(PrdSpec.class);
        assertThat(host.getComponentAt(0)).isInstanceOf(EntitySummaryPanel.class);
    }

    @Test
    void theModifyActionAppearsOnlyForAClassTheServiceAcceptsAndOpensThatClass() {
        var access = mock(EntityExplorerAccess.class);
        when(access.allows()).thenReturn(true);
        var view = new EntityExplorerView(ExplorerTreeModel.CatalogView.of(assembler().explorerSnapshot()),
            List.of(), mock(FormNavigator.class), access, true);
        var opened = new ArrayList<Class<?>>();
        view.setModifyOpener(opened::add, type -> type == Nomenclature.class);
        view.init(PrdSpec.class);
        Button modify = descendants(view).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(b -> b.hasClassName("explorer-open-modify")).findFirst().orElseThrow();
        assertThat(modify.isVisible()).isFalse();
        view.init(Nomenclature.class);
        assertThat(modify.isVisible()).isTrue();
        modify.click();
        assertThat(opened).containsExactly(Nomenclature.class);
        when(access.allows()).thenReturn(false);
        modify.click();
        assertThat(opened).as("access is asked again when the action runs").hasSize(1);
    }

    private static EntitySummary summary() { return assembler().summarize(Nomenclature.class); }

    private static EntitySummaryAssembler assembler() {
        return Facts.ASSEMBLER;
    }

    private static final class Facts {
        private static final EntitySummaryAssembler ASSEMBLER = new EntitySummaryAssembler("org.ip.model", new MetadataResolver(), new FormRegistry(),
            new ReferenceIndex("org.ip.model"), new NumberingMetadataRegistry("org.ip.model"),
            new SubsystemRegistry("org.ip", new org.ipro.metadata.MetadataResolver()), FacetResolver.none());
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root.getChildren().flatMap(EntityExplorerPilotPanelTest::descendants));
    }

    private static String text(Component root) {
        return descendants(root).filter(HasText.class::isInstance).map(c -> ((HasText) c).getText()).collect(java.util.stream.Collectors.joining(" "));
    }

    private static Button button(Component root, String text) {
        return descendants(root).filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
    }
}
