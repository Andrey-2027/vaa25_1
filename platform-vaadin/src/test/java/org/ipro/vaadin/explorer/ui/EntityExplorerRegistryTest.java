package org.ipro.vaadin.explorer.ui;

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.vaadin.explorer.RegistryFixture;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class EntityExplorerRegistryTest {
    private static final ExplorerSnapshot SNAPSHOT = RegistryFixture.snapshot();
    static Stream<Component> all(Component component) { return Stream.concat(Stream.of(component), component.getChildren().flatMap(EntityExplorerRegistryTest::all)); }
    static String text(Component component) { return all(component).map(child -> child.getElement().getText()).collect(java.util.stream.Collectors.joining(" ")); }
    static Component css(Component component, String style) { return all(component).filter(child -> child.getElement().getClassList().contains(style)).findFirst().orElseThrow(); }
    static void select(EntityExplorerRegistryPanel panel, Class<?> type) {
        var row = all(panel).filter(child -> type.getName().equals(child.getElement().getAttribute("data-type"))).findFirst().orElseThrow();
        ComponentUtil.fireEvent(row, new ClickEvent<>(row));
    }
    static TextField search(EntityExplorerRegistryPanel panel) { return all(panel).filter(TextField.class::isInstance).map(TextField.class::cast).findFirst().orElseThrow(); }
    static List<Class<?>> result(EntityExplorerRegistryState state) { return EntityExplorerRegistryModel.project(SNAPSHOT,state).entries().stream().map(ExplorerSnapshot.Entry::type).toList(); }

    @Test void rootsServiceUnknownAndFailedAreClassifiedFromFacts() {
        var state = new EntityExplorerRegistryState();
        assertThat(result(state)).contains(RegistryFixture.Unknown.class,RegistryFixture.Broken.class).doesNotContain(RegistryFixture.Service.class,RegistryFixture.Owned.class);
        assertThat(EntityExplorerRegistryModel.project(SNAPSHOT,state).groups()).anyMatch(group -> group.id().equals("kind:UNKNOWN"));
        state.setShowServiceTypes(true); assertThat(result(state)).contains(RegistryFixture.Service.class).doesNotContain(RegistryFixture.Owned.class);
        assertThat(EntityExplorerRegistryModel.project(SNAPSHOT,state).groups()).noneMatch(group -> group.id().contains("service"));
        assertThatThrownBy(() -> state.setKind(EntityKind.AUTO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(EntityExplorerLabels.kind(EntityKind.PLAIN,false)).isEqualTo("Простая сущность");
    }
    @Test void searchUsesOnlyCaptionAndSimpleNameWithIntersection() {
        var state = new EntityExplorerRegistryState(); state.setQuery("  GROUPnom ");
        assertThat(result(state)).containsExactly(RegistryFixture.GroupNom.class);
        state.setQuery("группы НОМЕНКЛАТУРЫ"); assertThat(result(state)).containsExactly(RegistryFixture.GroupNom.class);
        state.setQuery("Артикул"); assertThat(result(state)).isEmpty();
        state.setQuery("NOMENCLATURE"); state.setKind(EntityKind.INFORMATION_REGISTER); state.setSubsystem(RegistryFixture.Production.class.getName());
        assertThat(result(state)).containsExactly(RegistryFixture.NomenclaturePrice.class);
        state.setSubsystem(RegistryFixture.Directories.class.getName()); assertThat(result(state)).isEmpty();
    }
    @Test void noSubsystemAndErrorsHaveTheirOwnSemantics() {
        var state = new EntityExplorerRegistryState(); state.setSubsystem(EntityExplorerRegistryState.NO_SUBSYSTEM);
        assertThat(result(state)).containsExactlyInAnyOrder(RegistryFixture.NoSubsystem.class,RegistryFixture.Unknown.class);
        state.resetFilters(); state.setOnlyErrors(true);
        assertThat(result(state)).containsExactly(RegistryFixture.Broken.class).doesNotContain(RegistryFixture.GroupNom.class);
        assertThat(EntityExplorerRegistryModel.project(RegistryFixture.snapshot(false),state).entries()).extracting(ExplorerSnapshot.Entry::type)
            .containsExactly(RegistryFixture.Broken.class);
    }
    @Test void technicalExposureRetainsUnknownAndDoesNotBypassServiceVisibility() {
        var state = new EntityExplorerRegistryState(); state.setExposure(EntityExplorerRegistryState.UNKNOWN_EXPOSURE);
        assertThat(result(state)).containsExactly(RegistryFixture.Unknown.class);
        state.setExposure("INTERNAL_STORE"); assertThat(result(state)).isEmpty();
        state.setShowServiceTypes(true); assertThat(result(state)).containsExactly(RegistryFixture.Service.class);
    }
    @Test void resetKeepsGroupingAndStableExpansionsAndClearsEveryFilter() {
        var state = new EntityExplorerRegistryState(); state.setGrouping(EntityExplorerRegistryState.Grouping.SUBSYSTEM);
        state.setCollapsed("subsystem:"+RegistryFixture.Directories.class.getName(),true); state.setQuery("a"); state.setKind(EntityKind.CATALOG);
        state.setSubsystem("other"); state.setExposure("INTERNAL_STORE"); state.setOnlyErrors(true);state.setShowServiceTypes(true);
        state.resetFilters(); assertThat(state.hasFilters()).isFalse(); assertThat(state.grouping()).isEqualTo(EntityExplorerRegistryState.Grouping.SUBSYSTEM);
        assertThat(state.collapsedGroups()).containsExactly("subsystem:"+RegistryFixture.Directories.class.getName());
    }
    @Test void firstEntryInvitesSelectionAndFilteringReconcilesTheOverview() {
        var state = new EntityExplorerRegistryState(); var panel = new EntityExplorerRegistryPanel(SNAPSHOT,state,EntityExplorerRegistryActions.none());
        assertThat(state.selectedType()).isNull(); assertThat(text(css(panel,"er-empty-overview"))).contains("Выберите сущность");
        select(panel,RegistryFixture.GroupNom.class); assertThat(state.selectedType()).isEqualTo(RegistryFixture.GroupNom.class.getName());
        search(panel).setValue("ReceivingDocument"); assertThat(state.selectedType()).isEqualTo(RegistryFixture.ReceivingDocument.class.getName());
        search(panel).setValue("no-match-at-all"); assertThat(state.selectedType()).isNull();
        assertThat(text(css(panel,"er-empty-list"))).contains("Сбросить фильтры");
        assertThat(all(panel).filter(child -> child.getElement().getClassList().contains("er-title"))).isEmpty();
        assertThat(search(panel).getParent()).isPresent();
    }
    @Test void panelRecreationAndSnapshotRefreshPreserveCallerState() {
        var state = new EntityExplorerRegistryState(); state.setQuery("nom"); state.select(RegistryFixture.Nomenclature.class.getName());
        state.setCollapsed("kind:CATALOG",true);
        var panel = new EntityExplorerRegistryPanel(SNAPSHOT,state,EntityExplorerRegistryActions.none());
        panel.setSnapshot(RegistryFixture.snapshot(false));
        assertThat(state.selectedType()).isEqualTo(RegistryFixture.Nomenclature.class.getName());
        var restored = new EntityExplorerRegistryPanel(SNAPSHOT,state,EntityExplorerRegistryActions.none());
        assertThat(search(restored).getValue()).isEqualTo("nom"); assertThat(state.collapsed("kind:CATALOG")).isTrue();
        assertThat(text(css(panel,"er-properties"))).doesNotContain("Вид сущности","Подсистема");
    }
    @Test void userActionsAreRecheckedAndNeverIssuedBySelectionOrRefresh() {
        var allowed = new AtomicBoolean(true); var opened = new AtomicInteger();
        var actions = new EntityExplorerRegistryActions(type -> allowed.get() ? ExplorerActionAvailability.ready() : ExplorerActionAvailability.unavailable("Нет допуска"),
            type -> opened.incrementAndGet(), type -> ExplorerActionAvailability.unavailable("Запись выключена"), type -> fail("No modify"),
            kind -> ExplorerActionAvailability.unavailable("Нет рецепта"), kind -> fail("No create"), ExplorerActionAvailability.ready(), () -> {});
        var state = new EntityExplorerRegistryState(); var panel = new EntityExplorerRegistryPanel(SNAPSHOT,state,actions);
        select(panel,RegistryFixture.GroupNom.class); panel.refresh(); assertThat(opened).hasValue(0);
        var open = all(panel).filter(child -> child instanceof Button button && button.getText().equals("Открыть карточку")).map(Button.class::cast).findFirst().orElseThrow();
        allowed.set(false); open.click(); assertThat(opened).hasValue(0);
        assertThat(css(panel,"er-action-status").getElement().getText()).isEqualTo("Нет допуска");
        allowed.set(true); open.click(); assertThat(opened).hasValue(1);
    }
    @Test void unknownDiagnosticsAndPlainExplanationAreVisibleText() {
        var state = new EntityExplorerRegistryState(); state.select(RegistryFixture.GroupNom.class.getName());
        var panel = new EntityExplorerRegistryPanel(RegistryFixture.snapshot(false),state,EntityExplorerRegistryActions.none());
        assertThat(text(css(panel,"er-overview"))).contains("Диагностика недоступна");
        var menu = all(panel).filter(MenuBar.class::isInstance).map(MenuBar.class::cast).findFirst().orElseThrow();
        var plain = menu.getItems().getFirst().getSubMenu().getItems().get(3);
        assertThat(text(plain)).contains("Простая сущность","пока не поддерживается");
        assertThat(plain.getElement().getAttribute("aria-disabled")).isEqualTo("true");
    }
    @Test void detailedPlacesAreNeutralAndProgrammaticApplicationIsSilent() {
        var state = new EntityExplorerCardState(); var callbacks = new ArrayList<String>();
        var none = EntityExplorerCardPanel.Actions.none();
        var actions = new EntityExplorerCardPanel.Actions(none.openStructure(),none.openList(),none.listAvailable(),none.registry(),callbacks::add,none.sources(),none.preview());
        var panel = new EntityExplorerCardPanel(SNAPSHOT.summaryOf(RegistryFixture.GroupNom.class).orElseThrow(),state,EntityExplorerCardPanel.Mode.DETAILED,actions);
        panel.applyPlace(new EntityExplorerCardPlace("fields/form")); assertThat(state.anchor()).isEqualTo("fields/form");assertThat(callbacks).isEmpty();
        assertThat(EntityExplorerCardPlace.parse("access/no-such-section")).isEmpty();
        assertThat(EntityExplorerCardPlace.knownAnchors()).contains("overview/summary","fields/table-sections");
        var tabs = all(panel).filter(TabSheet.class::isInstance).map(TabSheet.class::cast).findFirst().orElseThrow();
        tabs.setSelectedTab(tabs.getTabAt(0)); assertThat(callbacks).hasSize(1);
    }
    @Test void compactCardRetainsAnExplicitPlaceAcrossComponentRecreation() {
        var state = new EntityExplorerCardState(); var summary = SNAPSHOT.summaryOf(RegistryFixture.GroupNom.class).orElseThrow();
        var panel = new EntityExplorerCardPanel(summary,state,EntityExplorerCardPanel.Mode.COMPACT,EntityExplorerCardPanel.Actions.none());
        panel.chooseCompactTab("presentation");
        var restored = new EntityExplorerCardPanel(summary,state,EntityExplorerCardPanel.Mode.COMPACT,EntityExplorerCardPanel.Actions.none());
        assertThat(all(restored).filter(child -> "presentation".equals(child.getElement().getAttribute("data-tab"))))
            .singleElement().satisfies(tab -> assertThat(tab.getElement().getAttribute("aria-pressed")).isEqualTo("true"));
    }
    @Test void failedSummaryHasAReadableCardAndTechnicalSwitchDoesNotNeedAnApplication() {
        var failed = new EntityExplorerCardPanel(SNAPSHOT,RegistryFixture.Broken.class,new EntityExplorerCardState(),
            EntityExplorerCardPanel.Mode.DETAILED,EntityExplorerCardPanel.Actions.none());
        assertThat(text(failed)).contains("Сводка недоступна","Fixture:");
        var state = new EntityExplorerCardState();
        var card = new EntityExplorerCardPanel(SNAPSHOT,RegistryFixture.GroupNom.class,state,EntityExplorerCardPanel.Mode.COMPACT,EntityExplorerCardPanel.Actions.none());
        all(card).filter(component -> component instanceof Button button && button.getText().equals("Техническая сводка"))
            .map(Button.class::cast).findFirst().orElseThrow().click();
        assertThat(all(card).filter(TabSheet.class::isInstance)).hasSize(1);
        assertThat(state.anchor()).isEqualTo("overview");
    }
    @Test void optionalSourcePortReportsUnavailabilityWithoutInvokingAnyFactory() {
        var source = ExplorerSourcePort.unavailable().read(SNAPSHOT.summaryOf(RegistryFixture.GroupNom.class).orElseThrow(),
            new ExplorerSourcePort.Target(RegistryFixture.GroupNom.class.getName(),null));
        assertThat(source.available()).isFalse(); assertThat(source.lines()).isEmpty();
        assertThat(source.reason()).contains("не подключён");
    }
}
