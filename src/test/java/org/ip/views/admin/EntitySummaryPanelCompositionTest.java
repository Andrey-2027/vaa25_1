package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import org.ip.model.Nomenclature;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.vaadin.explorer.EntitySummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.2.1 шаг 6.1: композиция карточки. Вкладки и разделы объявлены один раз в {@link CardSection};
 * здесь закреплено, что словарь и экран совпадают: каждый раздел словаря нарисован, каждый раздел
 * показывает ровно строки своего источника, пустая вкладка и пустая сводка не рисуют ничего.
 *
 * <p>Числа строк берутся у нарисованных таблиц, а не у словаря: забор состава обязан проверять то,
 * что видит пользователь.</p>
 */
class EntitySummaryPanelCompositionTest {

    // ---------------------------------------------------------------- словарь

    @Test
    void theDictionaryDeclaresTwentySectionsInSevenTabsInScreenOrder() {
        assertThat(CardSection.ALL).hasSize(20);
        assertThat(java.util.Arrays.stream(CardTab.values()).map(CardTab::title))
            .containsExactly("Обзор", "Поля и колонки", "Формы и действия", "Чтение",
                "Доступ", "Связи", "Поведение");

        assertThat(CardSection.ALL).extracting(CardSection::title).containsExactly(
            "Обзор", "Диагностика",
            "Поля — форма", "Поля — грид", "Колонки списка по умолчанию",
            "Колонки выбора (selectColumns)", "Табличные части",
            "Формы и варианты", "Контекст-фильтры", "Наборы выбора (Selection)",
            "Действия", "Действия — исполнители",
            "Сценарии чтения", "Сценарии чтения — пути",
            "Доступ", "Доступ — правила значений",
            "Связи", "Обратные ссылки («где используется»)",
            "Нумерация", "Lifecycle");

        List<String> ids = CardSection.ALL.stream().map(CardSection::id).toList();
        assertThat(ids)
            .as("идентификатор раздела — часть будущего якоря адреса, поэтому он один и латиницей")
            .doesNotHaveDuplicates()
            .allMatch(id -> id.matches("[a-z]+(-[a-z]+)*"));
        assertThat(CardSection.ALL).allMatch(section -> !section.title().isBlank());
    }

    @Test
    void everyTabOwnsAtLeastOneSectionAndTheyFollowTheTabOrder() {
        for (CardTab tab : CardTab.values()) {
            assertThat(CardSection.ALL.stream().filter(section -> section.tab() == tab))
                .as("вкладка «%s» без разделов не рисуется и не должна существовать в словаре",
                    tab.title())
                .isNotEmpty();
        }
        // Порядок словаря — порядок экрана: разделы одной вкладки идут подряд, вкладки — по
        // объявлению. Так вкладка не может «прошить» чужой раздел между своими.
        List<CardTab> tabSequence = CardSection.ALL.stream().map(CardSection::tab).toList();
        for (int i = 1; i < tabSequence.size(); i++) {
            assertThat(tabSequence.get(i).ordinal())
                .as("разделы вкладки не перемешаны")
                .isGreaterThanOrEqualTo(tabSequence.get(i - 1).ordinal());
        }
    }

    /** Разрыв аспекта на два раздела — это деление строк, а не их повтор: объявления и исполнители. */
    @Test
    void theActionSectionsSplitTheSingleActionListWithoutOverlap() {
        EntitySummary summary = fullSummary();
        CardSection<?> declarations = section("actions");
        CardSection<?> executors = section("executors");

        List<?> declared = declarations.rows(summary);
        List<?> handled = executors.rows(summary);

        assertThat(declared).as("объявление").hasSize(1);
        assertThat(handled).as("исполнитель").hasSize(1);
        assertThat(java.util.Collections.disjoint(declared, handled))
            .as("ни одна строка не попадает в оба раздела")
            .isTrue();
        assertThat(declared.size() + handled.size())
            .as("вместе разделы показывают весь список сводки, ничего не теряя и не повторяя")
            .isEqualTo(summary.actions().size());
    }

    // ---------------------------------------------------------------- экран

    @Test
    void theFullSummaryDrawsExactlyTheDictionaryOnScreen() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(fullSummary());

        assertThat(tabTitles(panel)).containsExactly("Обзор", "Поля и колонки",
            "Формы и действия", "Чтение", "Доступ", "Связи", "Поведение");
        assertThat(sectionTitles(panel))
            .as("порядок и состав экрана — это порядок словаря")
            .containsExactlyElementsOf(CardSection.ALL.stream().map(CardSection::title).toList());
    }

    @Test
    void eachSectionDrawsExactlyTheRowsOfItsDeclaredSource() {
        EntitySummary summary = fullSummary();
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summary);

        for (CardSection<?> section : CardSection.ALL) {
            long expected = section.rows(summary).size();
            assertThat(renderedRowCount(panel, section.title()))
                .as("раздел «%s» показывает строки своего источника (%d)",
                    section.title(), expected)
                .isEqualTo(expected);
        }
    }

    @Test
    void aSummaryWithoutRowsDrawsNoTabsAndNoSections() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(emptySummary());

        assertThat(CardSections.tabs(panel))
            .as("пустая сводка не рисует даже заголовков вкладок")
            .isNull();
        assertThat(sectionTitles(panel)).isEmpty();
    }

    /** Вкладка появляется только со своим разделом: пустая вкладка — это заголовок без факта. */
    @Test
    void onlyTheTabsWithDataAreDrawn() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summaryWithLifecycleOnly());

        assertThat(tabTitles(panel)).containsExactly("Поведение");
        assertThat(sectionTitles(panel)).containsExactly("Lifecycle");
    }

    @Test
    void switchingTabsDoesNotRedrawTheSections() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(fullSummary());
        TabSheet tabs = CardSections.tabs(panel);
        List<Component> before = sectionGrids(panel);

        tabs.setSelectedIndex(1);

        assertThat(sectionGrids(panel))
            .as("переключение вкладки показывает уже прочитанную сводку, а не читает её заново")
            .containsExactlyElementsOf(before);
    }

    // ------------------------------------------------------------ «где» записи диагностики (6.2)

    /**
     * E3.2.1 шаг 6.2: колонка «Где» стоит между адресом и текстом — адрес говорит, где запись
     * родилась (владелец метаданных), а «Где» — куда ведёт в самой карточке.
     */
    @Test
    void thePlaceColumnStandsBetweenTheAddressAndTheText() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summaryWithAnAddressedDiagnosticOnly());

        Grid<?> grid = findGrid(CardSections.details(panel, "Диагностика"));
        assertThat(grid.getColumns().stream().map(column -> column.getKey()))
            .containsExactly("severity", "code", "address", "where", "value", "source");
        // Заголовок колонки лежит в ячейке заголовочной строки: `Column.getHeaderText()` его не
        // видит, когда текст поставлен через заголовочную ячейку.
        assertThat(headerTextOf(grid, "where")).isEqualTo("Где");
    }

    /**
     * Переход из обзора: «Где» называет место и ведёт в него, только когда вкладка и раздел
     * нарисованы этой же сводкой. Нажатие на кнопку ячейки — тот же путь, что у пользователя.
     */
    @Test
    void thePlaceCellLeadsToTheSectionThatTheCardHasDrawn() {
        EntitySummary summary = summaryWithPlacedDiagnostics();
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summary);
        TabSheet tabs = CardSections.tabs(panel);

        EntitySummary.DiagnosticRow fieldRow = summary.diagnostics().get(0);
        Details form = CardSections.details(panel, "Поля — форма");
        Details grid = CardSections.details(panel, "Поля — грид");
        form.setOpened(false);
        grid.setOpened(false);

        Component cell = panel.diagnosticWhereCell(summary, fieldRow);
        assertThat(CardSections.textIn(cell)).isEqualTo("Поля и колонки · Поля — форма");
        Button open = CardSections.buttonIn(cell);
        assertThat(open).as("кнопка перехода появляется там, где вести есть куда").isNotNull();
        assertThat(open.getElement().getAttribute("aria-label"))
            .isEqualTo("Открыть Поля и колонки · Поля — форма");

        open.click();

        assertThat(tabs.getSelectedTab().getLabel()).isEqualTo("Поля и колонки");
        assertThat(form.isOpened()).as("переход раскрывает раздел, к которому ведёт").isTrue();
        assertThat(grid.isOpened()).as("переход раскрывает только раздел адреса").isFalse();
    }

    /**
     * Запись уровня сущности остаётся текстом: вести некуда, и кнопка обещала бы переход. Запись,
     * чей раздел не нарисован, тоже теряет кнопку — но не место: адрес у неё есть.
     */
    @Test
    void aDiagnosticWithoutADrawnPlaceOffersNoTransition() {
        EntitySummary summary = summaryWithAnAddressedDiagnosticOnly();
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summary);

        EntitySummary.DiagnosticRow addressed = summary.diagnostics().get(0);
        CardSection.Location addressedPlace = CardSection.locate(summary, addressed);
        assertThat(EntitySummaryPanel.placeText(addressedPlace)).isEqualTo("Связи");
        assertThat(panel.canFocus(addressedPlace))
            .as("раздела «Связи» в этой сводке нет — вести некуда")
            .isFalse();
        assertThat(CardSections.buttonIn(panel.diagnosticWhereCell(summary, addressed))).isNull();

        EntitySummary keyless = fullSummary();
        EntitySummaryPanel keylessPanel = new EntitySummaryPanel(null);
        keylessPanel.show(keyless);
        TabSheet keylessTabs = CardSections.tabs(keylessPanel);
        Tab selected = keylessTabs.getSelectedTab();
        EntitySummary.DiagnosticRow row = keyless.diagnostics().get(0);
        CardSection.Location entityLevel = CardSection.locate(keyless, row);
        assertThat(entityLevel.addressed()).isFalse();
        assertThat(EntitySummaryPanel.placeText(entityLevel)).isEqualTo("уровень сущности");
        Component cell = keylessPanel.diagnosticWhereCell(keyless, row);
        assertThat(CardSections.buttonIn(cell)).as("без места кнопка обещала бы переход").isNull();
        assertThat(CardSections.textIn(cell)).isEqualTo("уровень сущности");

        keylessPanel.focus(entityLevel);
        assertThat(keylessTabs.getSelectedTab())
            .as("переход без места не меняет вкладку")
            .isSameAs(selected);
        assertThat(selected.getLabel()).isEqualTo("Обзор");
    }

    /**
     * Счётчики по вкладкам берутся из словаря: каждая запись обзора адресована ровно одному месту —
     * вкладке, уровню сущности или виду без раздела, — и обзор не теряет ни одной записи.
     */
    @Test
    void everyDiagnosticRowIsAddressedToOnePlaceAndTheOverviewLosesNone() {
        EntitySummary summary = summaryWithPlacedDiagnostics();
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summary);

        Map<CardTab, Long> byTab = new EnumMap<>(CardTab.class);
        long entityLevel = 0;
        long unplaced = 0;
        for (EntitySummary.DiagnosticRow row : summary.diagnostics()) {
            CardSection.Location location = CardSection.locate(summary, row);
            if (location.section() != null) {
                assertThat(location.section().tab())
                    .as("раздел адреса принадлежит той же вкладке, что назвал адрес")
                    .isEqualTo(location.tab());
            }
            if (location.tab() != null) {
                byTab.merge(location.tab(), 1L, Long::sum);
            } else if (location.addressed()) {
                unplaced++;
            } else {
                entityLevel++;
            }
        }

        assertThat(byTab.keySet())
            .as("счётчики по вкладкам — из словаря, а не «сколько получилось»")
            .containsExactlyInAnyOrder(CardTab.FIELDS, CardTab.LINKS, CardTab.ACCESS);
        assertThat(byTab.get(CardTab.FIELDS)).isEqualTo(1);
        assertThat(byTab.get(CardTab.LINKS)).isEqualTo(1);
        assertThat(byTab.get(CardTab.ACCESS)).isEqualTo(1);
        assertThat(entityLevel).isEqualTo(1);
        assertThat(unplaced).isEqualTo(1);
        assertThat(byTab.values().stream().mapToLong(Long::longValue).sum() + entityLevel + unplaced)
            .as("число записей обзора равно сумме по вкладкам плюс записи без вкладки")
            .isEqualTo(renderedRowCount(panel, "Диагностика"));
    }

    // ------------------------------------------------------------ факт табличной части (7.1)

    /**
     * E3.2.1 шаг 7.1: факт табличной части адресуется тому разделу, который его показывает, — узел
     * дерева знает только имя класса строки, и второго списка «узел → раздел» не заводится.
     */
    @Test
    void theTableSectionFactIsAddressedToTheSectionShowingIt() {
        EntitySummary summary = fullSummary();

        CardSection.Location found = CardSection.locateTableSection(summary, "NomAttributeValue");
        assertThat(found.tab()).isEqualTo(CardTab.FIELDS);
        assertThat(found.section()).isEqualTo(section("table-sections"));
        assertThat(EntitySummaryPanel.placeText(found))
            .isEqualTo("Поля и колонки · Табличные части");

        CardSection.Location unknown = CardSection.locateTableSection(summary, "NoSuchRowClass");
        assertThat(unknown.tab()).isNull();
        assertThat(unknown.addressed())
            .as("факт у владельца есть, но раздела, который его показывает, не нашлось")
            .isTrue();
        assertThat(EntitySummaryPanel.placeText(unknown)).isEqualTo("вид не размещён");
    }

    // ------------------------------------------------------------- якорь адреса (8.2)

    /**
     * E3.2.1 §8.2: свежая карточка открывает место, названное якорем адреса, — вкладку и (или)
     * раздел. Первая вкладка без якоря — «Обзор», так что выбор вкладки виден без особой настройки.
     */
    @Test
    void theAddressAnchorOpensTheNamedTabOnAFreshCard() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(fullSummary(), "access/rules");

        assertThat(CardSections.tabs(panel).getSelectedTab().getLabel()).isEqualTo("Доступ");
    }

    @Test
    void theSectionAnchorOpensExactlyTheNamedSection() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(fullSummary());
        Details dimensions = CardSections.details(panel, "Доступ");
        Details rules = CardSections.details(panel, "Доступ — правила значений");
        dimensions.setOpened(false);
        rules.setOpened(false);

        panel.focusAnchor("access/rules");

        assertThat(CardSections.tabs(panel).getSelectedTab().getLabel()).isEqualTo("Доступ");
        assertThat(rules.isOpened()).as("названный раздел раскрыт").isTrue();
        assertThat(dimensions.isOpened())
            .as("соседний раздел той же вкладки якорь не трогает: он назвал одно место")
            .isFalse();
    }

    /** Якорь вкладки называет её первую секцию (§2.2), а не «место, где сохранилось состояние». */
    @Test
    void theTabAnchorOpensTheFirstSectionOfThatTab() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(fullSummary());
        Details targets = CardSections.details(panel, "Связи");
        Details references = CardSections.details(panel, "Обратные ссылки («где используется»)");
        targets.setOpened(false);
        references.setOpened(false);

        panel.focusAnchor("links");

        assertThat(CardSections.tabs(panel).getSelectedTab().getLabel()).isEqualTo("Связи");
        assertThat(targets.isOpened()).isTrue();
        assertThat(references.isOpened()).isFalse();
    }

    /**
     * Место, которого в этой карточке нет, открывать нечего: вкладка без строк не рисуется, а
     * неизвестный якорь до панели вообще не доходит — отказ по словарю выдаёт host. Панель не
     * отказывает и ничего не выдумывает: карточка остаётся как без якоря.
     */
    @Test
    void anAnchorWithoutADrawnPlaceOpensNothingAndIsNotARefusal() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(summaryWithLifecycleOnly(), "access");

        assertThat(tabTitles(panel)).containsExactly("Поведение");
        assertThat(CardSections.tabs(panel).getSelectedTab().getLabel()).isEqualTo("Поведение");

        panel.show(summaryWithLifecycleOnly(), "nope");

        assertThat(tabTitles(panel)).containsExactly("Поведение");
    }

    /**
     * Смена вкладки пользователем сообщается host'у якорем места, а применение якоря — нет: адрес
     * уже стоит в окне, и повторная запись была бы ложным шагом истории (E3.2.1 §8.2).
     */
    @Test
    void aChosenTabIsReportedAsItsOwnAnchorAndTheAnchorApplicationIsSilent() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        List<String> places = new ArrayList<>();
        panel.setPlaceListener(places::add);

        panel.show(fullSummary(), "access/rules");

        assertThat(places).as("место из адреса — не выбор пользователя").isEmpty();

        TabSheet tabs = CardSections.tabs(panel);
        tabs.setSelectedTab(tabs.getTabAt(0));

        assertThat(places).containsExactly("overview");
    }

    /**
     * Переход из обзора — выбор места пользователем: host'у сообщается место целиком, вместе с
     * разделом, и один раз. Отдельного сообщения о смене вкладки нет: адрес не мигал бы
     * «вкладка → вкладка/раздел», а Back вёл бы на полшага (E3.2.1 §8.3).
     */
    @Test
    void aPlaceOpenedFromTheOverviewIsReportedWithItsSection() {
        EntitySummary summary = summaryWithPlacedDiagnostics();
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        List<String> places = new ArrayList<>();
        panel.setPlaceListener(places::add);
        panel.show(summary);

        Component cell = panel.diagnosticWhereCell(summary, summary.diagnostics().get(0));
        CardSections.buttonIn(cell).click();

        assertThat(places).containsExactly("fields/form");
    }

    // ---------------------------------------------------------------- чтение экрана

    private static CardSection<?> section(String id) {
        return CardSection.ALL.stream()
            .filter(candidate -> candidate.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("нет раздела с id " + id));
    }

    private static List<String> tabTitles(EntitySummaryPanel panel) {
        TabSheet tabs = CardSections.tabs(panel);
        if (tabs == null) {
            return List.of();
        }
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            titles.add(tabs.getTabAt(i).getLabel());
        }
        return titles;
    }

    private static List<String> sectionTitles(EntitySummaryPanel panel) {
        TabSheet tabs = CardSections.tabs(panel);
        if (tabs == null) {
            return List.of();
        }
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            collectHeadings(tabs.getComponent(tabs.getTabAt(i)), titles);
        }
        return titles;
    }

    private static void collectHeadings(Component component, List<String> titles) {
        if (component instanceof H4 heading) {
            titles.add(heading.getText());
        }
        component.getChildren().forEach(child -> collectHeadings(child, titles));
    }

    private static List<Component> sectionGrids(EntitySummaryPanel panel) {
        List<Component> grids = new ArrayList<>();
        TabSheet tabs = CardSections.tabs(panel);
        for (int i = 0; i < tabs.getTabCount(); i++) {
            collectGrids(tabs.getComponent(tabs.getTabAt(i)), grids);
        }
        return grids;
    }

    private static void collectGrids(Component component, List<Component> grids) {
        if (component instanceof Grid<?>) {
            grids.add(component);
        }
        component.getChildren().forEach(child -> collectGrids(child, grids));
    }

    private static long renderedRowCount(EntitySummaryPanel panel, String title) {
        Details details = CardSections.details(panel, title);
        assertThat(details).as("раздел «%s» нарисован", title).isNotNull();
        Grid<?> grid = findGrid(details);
        assertThat(grid).as("у раздела «%s» есть таблица", title).isNotNull();
        // Провайдер таблицы — back-end (фильтры FilterGrid), поэтому строки читаются generic-
        // представлением: list-представление такому провайдеру не годится.
        return grid.getGenericDataView().getItems().count();
    }

    /** Текст заголовка колонки — то, что видит пользователь над столбцом. */
    private static String headerTextOf(Grid<?> grid, String columnKey) {
        HeaderRow.HeaderCell cell = (HeaderRow.HeaderCell) grid.getHeaderRows().get(0)
            .getCell(grid.getColumnByKey(columnKey));
        return cell.getText();
    }

    private static Grid<?> findGrid(Component component) {
        if (component instanceof Grid<?> grid) {
            return grid;
        }
        return component.getChildren()
            .map(child -> findGrid(child))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * E3.2.2 §9.3: кнопка-иконка перехода к структуре цели названа текстом — у иконки нет
     * подписи, и цель перехода обязана называться словами.
     */
    @Test
    void theIconOnlyStructureActionNamesItsTarget() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.setStructureNavigator(type -> {});
        panel.show(fullSummary());
        Grid<?> grid = findGrid(CardSections.details(panel, "Связи"));
        @SuppressWarnings("unchecked")
        com.vaadin.flow.data.renderer.ComponentRenderer<Component, EntitySummary.LookupRow> renderer =
            (com.vaadin.flow.data.renderer.ComponentRenderer<Component, EntitySummary.LookupRow>)
                grid.getColumnByKey("target").getRenderer();
        Component cell = renderer.createComponent(lookupRow());
        Button action = buttonWithAriaLabelPrefix(cell, "Открыть структуру");

        assertThat(action).as("кнопка структуры цели нарисована").isNotNull();
        assertThat(action.getElement().getAttribute("aria-label"))
            .isEqualTo("Открыть структуру Nomenclature");
    }

    private static Button buttonWithAriaLabelPrefix(Component root, String prefix) {
        if (root instanceof Button button) {
            String label = button.getElement().getAttribute("aria-label");
            if (label != null && label.startsWith(prefix)) {
                return button;
            }
        }
        return root.getChildren()
            .map(child -> buttonWithAriaLabelPrefix(child, prefix))
            .filter(java.util.Objects::nonNull)
            .findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- фикстуры

    /** Сводка, где каждый список аспекта непуст: так видно все двадцать разделов сразу. */
    private static EntitySummary fullSummary() {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(overviewRow()),
            List.of(fieldRow()), List.of(fieldRow()),
            List.of(columnRow()), List.of(columnRow()),
            List.of(sectionRow()),
            List.of(formRow()),
            List.of(filterRow()),
            List.of(selectionRow()),
            List.of(referenceRow()),
            List.of(numberingRow()),
            List.of(lifecycleRow()),
            List.of(diagnosticRow()),
            List.of(actionRow(FacetKind.ACTION), actionRow(FacetKind.ACTION_HANDLER)),
            List.of(readPlanRow()),
            List.of(accessRow()),
            List.of(lookupRow()));
    }

    private static EntitySummary emptySummary() {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of());
    }

    /** Живёт только один аспект — так видно, что рисуется ровно его вкладка. */
    private static EntitySummary summaryWithLifecycleOnly() {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(),
            List.of(lifecycleRow()),
            List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static EntitySummary.OverviewRow overviewRow() {
        return new EntitySummary.OverviewRow("Сущность",
            FacetKey.of(FacetKind.ENTITY_KIND, Nomenclature.class),
            ResolvedValue.fact("Справочник", FactOrigin.EXPLICIT,
                "org.ip.model.Nomenclature"));
    }

    private static EntitySummary.FieldRow fieldRow() {
        return new EntitySummary.FieldRow(
            FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, "code"),
            "code", "Строка",
            ResolvedValue.fact("Код", FactOrigin.EXPLICIT, "org.ip.model.Nomenclature#code"),
            false, false, FactOrigin.PLATFORM_DEFAULT, FactOrigin.JPA_MAPPING);
    }

    private static EntitySummary.ColumnRow columnRow() {
        return new EntitySummary.ColumnRow(
            FacetKey.of(FacetKind.GRID_COLUMN_HEADER, Nomenclature.class, "code"),
            "code", ResolvedValue.fact("Код", FactOrigin.EXPLICIT,
                "org.ip.model.Nomenclature#code"),
            "Строка", false, "");
    }

    private static EntitySummary.SectionRow sectionRow() {
        return new EntitySummary.SectionRow(
            FacetKey.of(FacetKind.TABLE_SECTION, Nomenclature.class, "attributes"),
            ResolvedValue.fact("Атрибуты", FactOrigin.EXPLICIT, "org.ip.model.Nomenclature"),
            "NomAttributeValue", 1, 0, 2, 1);
    }

    private static EntitySummary.FormRow formRow() {
        return new EntitySummary.FormRow(FormType.ITEM, null, "кастомная фабрика", false, "");
    }

    private static EntitySummary.FilterRow filterRow() {
        return new EntitySummary.FilterRow(
            FacetKey.of(FacetKind.CONTEXT_FILTER_LABEL, Nomenclature.class, "code"),
            "code", ResolvedValue.fact("Код", FactOrigin.EXPLICIT, "org.ip.model.Nomenclature"),
            "text", false, false, "список", "");
    }

    private static EntitySummary.SelectionRow selectionRow() {
        return new EntitySummary.SelectionRow(null,
            ResolvedValue.fact("Номенклатура", FactOrigin.EXPLICIT, "org.ip.model.Nomenclature"),
            List.of("code", "name"), true);
    }

    private static EntitySummary.ReferenceRow referenceRow() {
        return new EntitySummary.ReferenceRow(
            FacetKey.of(FacetKind.REVERSE_REFERENCE, Nomenclature.class, "nom"),
            Nomenclature.class, "nom", false,
            ResolvedValue.fact("Nomenclature", FactOrigin.DERIVED, ""));
    }

    private static EntitySummary.NumberingRow numberingRow() {
        return new EntitySummary.NumberingRow(
            FacetKey.of(FacetKind.NUMBERING_DECL, Nomenclature.class, "code"),
            "code", ResolvedValue.fact("NUM-", FactOrigin.EXPLICIT, "org.ip.model.Nomenclature"),
            "глобально", "год", false);
    }

    private static EntitySummary.LifecycleRow lifecycleRow() {
        return new EntitySummary.LifecycleRow(
            FacetKey.of(FacetKind.ENTITY_LIFECYCLE_HANDLER, Nomenclature.class), null, true,
            ResolvedValue.fact("NomenclatureLifecycle", FactOrigin.REGISTRATION,
                "org.ip.application.catalog.NomenclatureLifecycle"),
            "");
    }

    private static EntitySummary.DiagnosticRow diagnosticRow() {
        return new EntitySummary.DiagnosticRow(MetadataDiagnostic.Severity.WARNING,
            "UI_OPTIONAL_SERVER_REQUIRED", Nomenclature.class.getName(), "code",
            "WARNING [UI_OPTIONAL_SERVER_REQUIRED]", null,
            ResolvedValue.fact("поле необязательно в UI и обязательно на сервере",
                FactOrigin.DERIVED, ""), "");
    }

    private static EntitySummary.ActionRow actionRow(FacetKind kind) {
        boolean handler = kind == FacetKind.ACTION_HANDLER;
        return new EntitySummary.ActionRow(
            FacetKey.of(kind, Nomenclature.class, "LIST_TOOLBAR/crud.create", null),
            ActionSurface.LIST_TOOLBAR, "crud.create", handler ? "" : "Создать",
            handler ? 0 : 10, true, false, handler,
            ResolvedValue.fact(handler ? "найден" : "Создать", FactOrigin.REGISTRATION,
                "org.ip.config.ActionPolicyConfig"),
            "");
    }

    private static EntitySummary.ReadPlanRow readPlanRow() {
        return new EntitySummary.ReadPlanRow(
            FacetKey.of(FacetKind.FETCH_PLAN, Nomenclature.class, "LIST"),
            "LIST", true, 1,
            ResolvedValue.fact("допущен", FactOrigin.REGISTRATION,
                "org.ip.config.EntityClassificationConfig"),
            "набор сценариев объявлен приложением",
            List.of(new EntitySummary.PathRow(
                FacetKey.of(FacetKind.FETCH_PLAN_PATH, Nomenclature.class, "LIST/id"),
                "LIST", "id", "metadata:LIST",
                ResolvedValue.fact("id", FactOrigin.DERIVED, ""))));
    }

    private static EntitySummary.AccessRow accessRow() {
        return new EntitySummary.AccessRow(
            FacetKey.of(FacetKind.RLS_DIMENSION, Nomenclature.class, "ENTITY:Nomenclature"),
            "ENTITY:Nomenclature", RlsDimensionKind.CHECK_ONLY, false,
            ResolvedValue.fact("проверяемое", FactOrigin.EXPLICIT, "org.ip.model.Nomenclature"),
            "ворота типа: измерение объявлено, значения — данные пользователя",
            List.of(new EntitySummary.AccessRuleRow(
                FacetKey.of(FacetKind.RLS_VALUE_RULE, Nomenclature.class, "id"),
                "ENTITY:Nomenclature", "id", false,
                ResolvedValue.fact("id", FactOrigin.EXPLICIT, "org.ip.model.Nomenclature"))));
    }

    private static EntitySummary.LookupRow lookupRow() {
        return new EntitySummary.LookupRow(
            FacetKey.of(FacetKind.LOOKUP_TARGET, Nomenclature.class, "nomenclature"),
            "nomenclature",
            ResolvedValue.fact("Nomenclature", FactOrigin.EXPLICIT,
                "org.ip.model.Nomenclature#nomenclature"),
            Nomenclature.class, "", "");
    }

    /** Поле с заданным именем: место строения поля ищется по имени в обеих проекциях. */
    private static EntitySummary.FieldRow namedField(String name) {
        return new EntitySummary.FieldRow(FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, name),
            name, "Строка",
            ResolvedValue.fact(name, FactOrigin.EXPLICIT, "org.ip.model.Nomenclature#" + name),
            false, false, FactOrigin.PLATFORM_DEFAULT, FactOrigin.JPA_MAPPING);
    }

    private static EntitySummary.DiagnosticRow diagnosticRowWith(FacetKind kind, String fieldName,
                                                                String code) {
        return new EntitySummary.DiagnosticRow(MetadataDiagnostic.Severity.WARNING, code,
            Nomenclature.class.getName(), fieldName,
            "WARNING [" + code + "]",
            FacetKey.of(kind, Nomenclature.class, fieldName),
            ResolvedValue.fact("запись владельца метаданных", FactOrigin.DERIVED, ""), "");
    }

    /** Ключ без поля: так приходит отказ сканирования измерений RLS. */
    private static EntitySummary.DiagnosticRow rlsScanRow() {
        return new EntitySummary.DiagnosticRow(MetadataDiagnostic.Severity.WARNING, "RLS_SCAN",
            Nomenclature.class.getName(), "",
            "Измерение RLS объявлено, но не зарегистрировано",
            FacetKey.of(FacetKind.RLS_DIMENSION, Nomenclature.class),
            ResolvedValue.fact("политика не зарегистрирована", FactOrigin.PLATFORM_DEFAULT, ""), "");
    }

    /** Сводка, где живёт одна адресованная диагностика: раздел «Связи» этой сводкой не рисуется. */
    private static EntitySummary summaryWithAnAddressedDiagnosticOnly() {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(diagnosticRowWith(FacetKind.LOOKUP_TARGET, "nomenclature",
                "REFERENCE_TARGET_NOT_METADATA")),
            List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Сводка с записями всех мест: поле одной проекции, цель выбора, измерение RLS, ключ без
     * раздела и запись уровня сущности. Так видны и счётчики по вкладкам, и все переходы.
     */
    private static EntitySummary summaryWithPlacedDiagnostics() {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(),
            List.of(namedField("code")), List.of(namedField("name")),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(),
            List.of(
                diagnosticRowWith(FacetKind.FIELD_STRUCTURE, "code", "REDUNDANT_REQUIRED"),
                diagnosticRowWith(FacetKind.LOOKUP_TARGET, "nomenclature",
                    "REFERENCE_TARGET_NOT_METADATA"),
                rlsScanRow(),
                diagnosticRowWith(FacetKind.GRID_COLUMN_HEADER, "code", "FUTURE_DIAGNOSTIC_CODE"),
                diagnosticRow()),
            List.of(), List.of(), List.of(accessRow()), List.of(lookupRow()));
    }
}
