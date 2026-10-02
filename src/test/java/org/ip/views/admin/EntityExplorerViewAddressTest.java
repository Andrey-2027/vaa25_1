package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
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
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E3.2.2 §9.2–§9.3: адресный вход, одна карточка вместо вкладок сущностей, дерево от снимка,
 * поиск по индексу, фильтры пересечением и восстановление последнего выбора из меню.
 */
class EntityExplorerViewAddressTest {

    @Test
    void aRouteEntrySelectsItsTypeDespiteThePreviousSearchFilter() {
        EntityExplorerView view = newView();
        view.init(Nomenclature.class);
        TextField search = component(view, TextField.class);
        search.setValue("Nomenclature");

        view.init(ReceivingDocument.class);

        assertThat(search.getValue())
            .as("прямой вход снимает мешающие фильтры")
            .isEmpty();
        assertThat(currentCard(view))
            .as("адрес применяется к карточке того же вида, второй вид не открывается")
            .isSameAs(card(view, ReceivingDocument.class));
    }

    /**
     * E3.2.2 §4.4: повторный вход из меню восстанавливает последний выбор текущего UI, а не
     * очищает карточку. Сохранённый выбор — единственный источник и для карточки, и для адреса,
     * который регистрирует host.
     */
    @Test
    void aTypeNodeSelectionNotifiesTheHostAndMenuEntryRestoresTheSelection() {
        EntityExplorerView view = newView();
        List<String> selections = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> selections.add(place(type, anchor)));
        view.init(Nomenclature.class);

        selectTypeNode(view, ReceivingDocument.class);
        assertThat(selections).containsExactly("ReceivingDocument");
        assertThat(currentCard(view)).isSameAs(card(view, ReceivingDocument.class));

        Optional<EntityExplorerView.RestoredSelection> restored = view.applyMenuEntry();

        assertThat(restored).contains(new EntityExplorerView.RestoredSelection(
            ReceivingDocument.class, null));
        assertThat(currentCard(view))
            .as("меню восстанавливает ту же карточку, а не открывает пустую")
            .isSameAs(card(view, ReceivingDocument.class));
        assertThat(selections)
            .as("восстановление — не выбор пользователя: host адрес регистрирует сам")
            .containsExactly("ReceivingDocument");
    }

    /** Первый вход из меню: произвольный первый тип не выбирается, дерево доступно. */
    @Test
    void aFirstMenuEntryShowsTheEmptyCardAndLeavesTheTreeAvailable() {
        EntityExplorerView view = newView();

        Optional<EntityExplorerView.RestoredSelection> restored = view.applyMenuEntry();

        assertThat(restored).isEmpty();
        assertThat(cardHost(view).getComponentCount()).isZero();
        assertThat(dataOf(treeOf(view)).getRootItems()).isNotEmpty();
    }

    /**
     * E3.2.2 §9.0/§9.2: повторный вход — тот же вид, а не второй. Три входа по адресу (A, B, A с
     * якорем) оставляют одну колонку дерева, один путь выбора и одну диагностическую панель, а
     * пользовательский выбор сообщается host'у ровно один раз — и как тип, и как точное место.
     */
    @Test
    void threeAddressEntriesKeepOneTreeOneSelectionPathAndOneHostCall() {
        EntityExplorerView view = newView(true, true);
        List<String> selections = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> selections.add(place(type, anchor)));

        view.init(Nomenclature.class);
        view.init(ReceivingDocument.class);
        view.init(Nomenclature.class, "fields/table-sections");

        assertThat(treeColumnCount(view))
            .as("три входа — одна колонка дерева: повторный init() не добавляет её заново")
            .isEqualTo(1);
        assertThat(diagnosticsPanels(view))
            .as("диагностическая панель одна: вход её перерисовывает, а не накапливает")
            .isEqualTo(1L);
        assertThat(selections).as("программный вход — не выбор пользователя").isEmpty();

        selectTypeNode(view, ReceivingDocument.class);
        assertThat(selections)
            .as("выбор типа пользователем — одно итоговое сообщение host'у")
            .containsExactly("ReceivingDocument");

        selectSectionNode(view);
        assertThat(selections)
            .as("выбор узла раздела — тоже одно сообщение, и оно называет место целиком")
            .containsExactly("ReceivingDocument", "Nomenclature@fields/table-sections");
    }

    /**
     * E3.2.2 §9.2: узел раздела фокусирует место той же единственной карточки — второй карточки
     * типа не появляется.
     */
    @Test
    void aSectionNodeFocusesItsSectionWithoutOpeningASecondCard() {
        EntityExplorerView view = newViewWithTableSection();
        view.init(Nomenclature.class);

        EntitySummaryPanel panel = currentCard(view);
        TabSheet inner = CardSections.tabs(panel);
        Details section = CardSections.details(panel, "Табличные части");
        assertThat(section).as("раздел табличных частей нарисован").isNotNull();
        assertThat(inner.getSelectedTab().getLabel())
            .as("холодный вход по адресу типа аспект не выбирает")
            .isEqualTo("Обзор");
        section.setOpened(false);

        selectSectionNode(view);

        assertThat(cardHost(view).getComponentCount())
            .as("одна видимая карточка: узел раздела не открывает вторую")
            .isEqualTo(1);
        assertThat(currentCard(view)).isSameAs(panel);
        assertThat(inner.getSelectedTab().getLabel()).isEqualTo("Поля и колонки");
        assertThat(section.isOpened()).as("раздел той самой табличной части раскрыт").isTrue();
    }

    /**
     * Узел типа ведёт себя как раньше: карточка фокусируется, а выбранный в ней аспект остаётся —
     * повторный выбор узла не сбрасывает карточку в первый раздел.
     */
    @Test
    void aTypeNodeKeepsTheChosenAspect() {
        EntityExplorerView view = newViewWithTableSection();
        view.init(Nomenclature.class);

        EntitySummaryPanel panel = currentCard(view);
        TabSheet inner = CardSections.tabs(panel);
        inner.setSelectedTab(inner.getTabAt(1));

        selectTypeNode(view, Nomenclature.class);

        assertThat(inner.getSelectedTab().getLabel()).isEqualTo("Поля и колонки");
        assertThat(currentCard(view)).isSameAs(panel);
    }

    /** Группа — не место: выбор группы не открывает карточку и не меняет адрес. */
    @Test
    void groupsDoNotOpenTheCardAndDoNotChangeTheAddress() {
        EntityExplorerView view = newView();
        List<String> selections = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> selections.add(place(type, anchor)));
        view.init();

        selectNodeByPath(view, 0);
        assertThat(cardHost(view).getComponentCount()).isZero();
        assertThat(selections).isEmpty();

        selectNodeByPath(view, 0, 0);
        assertThat(selections).containsExactly("Nomenclature");
        assertThat(currentCard(view)).isSameAs(card(view, Nomenclature.class));
    }

    /**
     * Дополнительный режим — подсистемы; выбранный тип сохраняется, host'у ничего не сообщается.
     * Подписи групп несут счётчики диагностики.
     */
    @Test
    void subsystemModeRegroupsTheSameCatalogAndKeepsTheChosenType() {
        EntityExplorerView view = newView();
        List<String> selections = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> selections.add(place(type, anchor)));
        view.init(Nomenclature.class);

        Select<ExplorerTreeModel.GroupMode> mode = groupMode(view);
        mode.setValue(ExplorerTreeModel.GroupMode.SUBSYSTEM);

        assertThat(rootLabels(view)).containsExactly(
            "Справочники · ошибок: 0, предупреждений: 0",
            "Без подсистемы · ошибок: 0, предупреждений: 0");
        assertThat(selectedNodeIds(view))
            .containsExactly("type:" + Nomenclature.class.getName());
        assertThat(selections).as("смена группировки — не выбор места").isEmpty();
        assertThat(typeLabel(view, Nomenclature.class))
            .as("счётчики видны и у типа, а не только у группы")
            .contains("ошибок: 0, предупреждений: 0");
    }

    // ------------------------------------------------------------- меню и прямые входы (§4.4)

    /**
     * Меню восстанавливает не только тип, но и место, поиск и раскрытия текущего UI: всё это
     * выбирал пользователь, и новый вход их не сбрасывает.
     */
    @Test
    void menuEntryRestoresThePlaceSearchAndSelectionOfTheCurrentUi() {
        EntityExplorerView view = newViewWithTableSection();
        view.init(Nomenclature.class, "fields/table-sections");
        TextField search = component(view, TextField.class);
        search.setValue("номенклатура");
        EntitySummaryPanel before = card(view, Nomenclature.class);

        Optional<EntityExplorerView.RestoredSelection> restored = view.applyMenuEntry();

        assertThat(restored).contains(new EntityExplorerView.RestoredSelection(
            Nomenclature.class, "fields/table-sections"));
        assertThat(search.getValue()).as("поиск не сброшен").isEqualTo("номенклатура");
        assertThat(card(view, Nomenclature.class)).isSameAs(before);
        assertThat(CardSections.tabs(before).getSelectedTab().getLabel())
            .isEqualTo("Поля и колонки");
        assertThat(selectedNodeIds(view)).containsExactly("type:" + Nomenclature.class.getName());
    }

    /**
     * Прямой вход раскрывает путь к типу и сбрасывает мешающие фильтры: адрес называет место, и
     * оно не может остаться скрытым пользовательским фильтром.
     */
    @Test
    void aDirectEntryClearsInterferingFiltersAndRevealsThePathToTheType() {
        EntityExplorerView view = newViewWithTableSection();
        view.init(ReceivingDocument.class);
        TextField search = component(view, TextField.class);
        search.setValue("приём");
        kindFilter(view).setValue(EntityKind.DOCUMENT);

        view.init(Nomenclature.class);

        assertThat(search.getValue()).isEmpty();
        assertThat(selectByLabel(view, "Вид").getValue()).isNull();
        assertThat(selectedNodeIds(view)).containsExactly("type:" + Nomenclature.class.getName());
        assertThat(isExpanded(view, "type:" + Nomenclature.class.getName()))
            .as("путь к названному типу раскрыт")
            .isTrue();
        assertThat(currentCard(view)).isSameAs(card(view, Nomenclature.class));
    }

    /** Программный запрос не подменяется прошлым выбором; меню восстанавливает последний запрос. */
    @Test
    void aProgrammaticEntryWinsOverTheSavedSelectionAndMenuRestoresTheLastRequest() {
        EntityExplorerView view = newView();
        view.init(Nomenclature.class);

        view.init(ReceivingDocument.class);

        assertThat(currentCard(view)).isSameAs(card(view, ReceivingDocument.class));
        assertThat(view.applyMenuEntry()).contains(new EntityExplorerView.RestoredSelection(
            ReceivingDocument.class, null));
    }

    // ------------------------------------------------------------- поиск и фильтры (§4.3)

    /** Поиск идёт по индексу снимка: тип находится по подписи, owned-секция — по названию. */
    @Test
    void searchFindsTypesAndOwnedSectionsThroughTheSnapshotIndex() {
        EntityExplorerView view = newViewWithTableSection();
        view.init();
        TextField search = component(view, TextField.class);

        search.setValue("номенклатура");
        assertThat(findNode(view, node -> node.kind() == ExplorerTreeModel.NodeKind.TYPE
            && Nomenclature.class.equals(node.type()))).isNotNull();
        assertThat(findNode(view, node -> Nomenclature.class.equals(node.type()))).isNotNull();

        search.setValue("атрибуты номенклатуры");
        ExplorerTreeModel.Node owned = findNode(view,
            node -> node.kind() == ExplorerTreeModel.NodeKind.OWNED_SECTION);
        assertThat(owned).as("owned-секция находится по названию из индекса").isNotNull();
        assertThat(findNode(view, node -> node.kind() == ExplorerTreeModel.NodeKind.TYPE
            && Nomenclature.class.equals(node.type())))
            .as("совпавший потомок сохраняет цепочку предков")
            .isNotNull();
    }

    /**
     * Смена фильтра не закрывает открытую карточку. Если выбранный тип исчез из дерева, карточка
     * остаётся; очистка фильтра возвращает выбор по stable id.
     */
    @Test
    void aFilterDoesNotCloseTheCardAndClearingItRestoresTheSelection() {
        EntityExplorerView view = newView();
        view.init(Nomenclature.class);
        selectTypeNode(view, ReceivingDocument.class);
        EntitySummaryPanel panel = currentCard(view);

        kindFilter(view).setValue(EntityKind.CATALOG);

        assertThat(currentCard(view))
            .as("карточка не переключается и не закрывается фильтром")
            .isSameAs(panel);
        assertThat(selectedNodeIds(view)).isEmpty();

        selectByLabel(view, "Вид").clear();

        assertThat(selectedNodeIds(view))
            .as("очистка фильтра возвращает выбор по stable id, а не по объекту-копии")
            .containsExactly("type:" + ReceivingDocument.class.getName());
    }

    /** Пустая выдача поиска/фильтра называется явно, а не выглядит пустым каталогом. */
    @Test
    void anEmptySearchResultSaysNothingWasFound() {
        EntityExplorerView view = newView();
        view.init();
        TextField search = component(view, TextField.class);

        search.setValue("нет-такой-сущности");

        assertThat(nothingFound(view).isVisible()).isTrue();
        assertThat(dataOf(treeOf(view)).getRootItems()).isEmpty();

        search.clear();

        assertThat(nothingFound(view).isVisible()).isFalse();
    }

    /** Раскрытие, выбранное пользователем, сохраняется по id при смене фильтра. */
    @Test
    void aUserCollapseSurvivesAFilterChange() {
        EntityExplorerView view = newView();
        view.init();
        String groupId = "kind:CATALOG";
        assertThat(isExpanded(view, groupId)).isTrue();

        collapse(view, groupId);
        kindFilter(view).setValue(EntityKind.CATALOG);

        assertThat(isExpanded(view, groupId))
            .as("раскрытие хранится по stable id, а не по объекту-копии фильтрованного дерева")
            .isFalse();
    }

    @Test
    void choosingAnotherTypesSectionUpdatesTheMenuSelectionAndOneHostAddress() {
        EntityExplorerView view = newViewWithTableSection();
        List<String> places = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> places.add(place(type, anchor)));
        view.init(ReceivingDocument.class);
        selectSectionNode(view);
        assertThat(places).containsExactly("Nomenclature@fields/table-sections");
        assertThat(view.applyMenuEntry()).contains(new EntityExplorerView.RestoredSelection(
            Nomenclature.class, "fields/table-sections"));
        assertThat(currentCard(view)).isSameAs(card(view, Nomenclature.class));
    }

    @Test
    void anOwnedNodeFocusesTheRootSectionWithoutEncodingItsInternalIdInTheAddress() {
        EntityExplorerView view = newViewWithTableSection();
        List<String> places = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> places.add(place(type, anchor)));
        view.init(ReceivingDocument.class);
        ExplorerTreeModel.Node owned = findNode(view,
            node -> node.kind() == ExplorerTreeModel.NodeKind.OWNED_SECTION);
        treeOf(view).select(owned);
        assertThat(CardSections.tabs(currentCard(view)).getSelectedTab().getLabel())
            .isEqualTo("Поля и колонки");
        assertThat(places).containsExactly("Nomenclature@fields/table-sections");
        assertThat(CardSections.textIn(currentCard(view))).contains(owned.label());
        assertThat(view.applyMenuEntry()).contains(new EntityExplorerView.RestoredSelection(
            Nomenclature.class, "fields/table-sections"));
    }

    @Test
    void returningToACachedTypeKeepsItsCardPlaceAndAddressTogether() {
        EntityExplorerView view = newViewWithTableSection();
        List<String> places = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> places.add(place(type, anchor)));
        view.init(Nomenclature.class, "fields/table-sections");
        selectTypeNode(view, ReceivingDocument.class);
        selectTypeNode(view, Nomenclature.class);
        assertThat(places).containsExactly("ReceivingDocument", "Nomenclature@fields/table-sections");
        assertThat(CardSections.tabs(currentCard(view)).getSelectedTab().getLabel())
            .isEqualTo("Поля и колонки");
        assertThat(view.applyMenuEntry()).contains(new EntityExplorerView.RestoredSelection(
            Nomenclature.class, "fields/table-sections"));
        view.init(Nomenclature.class);
        assertThat(CardSections.tabs(currentCard(view)).getSelectedTab().getLabel()).isEqualTo("Обзор");
    }

    @Test
    void menuPreservesTheFilterValuesAndCollapsedNodes() {
        EntityExplorerView view = newViewWithTableSection();
        view.init(Nomenclature.class, "fields/table-sections");
        kindFilter(view).setValue(EntityKind.CATALOG);
        collapse(view, "kind:CATALOG");
        view.applyMenuEntry();
        assertThat(kindFilter(view).getValue()).isEqualTo(EntityKind.CATALOG);
        assertThat(isExpanded(view, "kind:CATALOG")).isFalse();
    }

    @Test
    void clearingSearchAndAnExcludingFilterRestoresHiddenCollapses() {
        EntityExplorerView view = newView();
        view.init();
        collapse(view, "kind:CATALOG");
        TextField search = component(view, TextField.class);
        search.setValue("номенклатура");
        assertThat(isExpanded(view, "kind:CATALOG")).isTrue();
        search.clear();
        assertThat(isExpanded(view, "kind:CATALOG")).isFalse();
        kindFilter(view).setValue(EntityKind.DOCUMENT);
        kindFilter(view).clear();
        assertThat(isExpanded(view, "kind:CATALOG")).isFalse();
    }

    @Test
    void aPrincipalOrLocaleChangeClearsThePreviousSelectionAndCachedPanels() {
        org.springframework.security.core.context.SecurityContext original =
            org.springframework.security.core.context.SecurityContextHolder.getContext();
        com.vaadin.flow.component.UI originalUi = com.vaadin.flow.component.UI.getCurrent();
        try {
            var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin-a", "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
            org.springframework.security.core.context.SecurityContextHolder.setContext(context);
            EntityExplorerView view = newViewWithTableSection();
            com.vaadin.flow.component.UI ui = new com.vaadin.flow.component.UI();
            com.vaadin.flow.server.VaadinSession session = mock(com.vaadin.flow.server.VaadinSession.class);
            when(session.getLocale()).thenReturn(Locale.forLanguageTag("ru"));
            ui.getInternals().setSession(session);
            ui.setLocale(Locale.forLanguageTag("ru"));
            com.vaadin.flow.component.UI.setCurrent(ui);
            ui.add(view);
            view.init(Nomenclature.class, "fields/table-sections");
            EntitySummaryPanel first = currentCard(view);
            kindFilter(view).setValue(EntityKind.CATALOG);
            context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "admin-b", "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
            assertThat(view.applyMenuEntry()).isEmpty();
            assertThat(panels(view)).isEmpty();
            assertThat(kindFilter(view).getValue()).isNull();
            view.init(Nomenclature.class);
            assertThat(currentCard(view)).isNotSameAs(first);
            ui.setLocale(Locale.ENGLISH);
            assertThat(view.applyMenuEntry()).isEmpty();
            assertThat(panels(view)).isEmpty();
        } finally {
            com.vaadin.flow.component.UI.setCurrent(originalUi);
            org.springframework.security.core.context.SecurityContextHolder.setContext(original);
        }
    }

    @Test
    void aMissingSectionWithinADrawnTabRetainsTheRequestedAnchor() {
        EntityExplorerView view = newViewWithTableSection();
        List<String> places = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> places.add(place(type, anchor)));
        view.init(Nomenclature.class, "fields/grid");
        assertThat(CardSections.textIn(currentCard(view)))
            .contains("Раздел «Поля — грид» отсутствует в карточке этого типа");
        assertThat(view.applyMenuEntry()).contains(new EntityExplorerView.RestoredSelection(
            Nomenclature.class, "fields/grid"));
        assertThat(places).isEmpty();
    }

    // ------------------------------------------------------------- якорь адреса (E3.2.1 8.2)

    /**
     * Адрес называет место карточки: карточка открывается на нём, а не на первом аспекте. Сообщать
     * host'у нечего — место выбрал адрес, и адрес уже стоит в окне.
     */
    @Test
    void anAddressAnchorOpensThatPlaceOfTheCard() {
        EntityExplorerView view = newViewWithTableSection();
        List<String> selections = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> selections.add(place(type, anchor)));

        view.init(Nomenclature.class, "fields/table-sections");

        EntitySummaryPanel panel = card(view, Nomenclature.class);
        assertThat(CardSections.tabs(panel).getSelectedTab().getLabel())
            .isEqualTo("Поля и колонки");
        assertThat(CardSections.details(panel, "Табличные части"))
            .as("раздел, названный якорем, нарисован карточкой")
            .isNotNull();
        assertThat(selections).as("место из адреса — не выбор пользователя").isEmpty();
    }

    /**
     * Повторный вход по адресу применяет якорь к той же карточке (Back/Forward, повторная ссылка):
     * второй карточки типа не появляется, а место карточки переставляется без сообщения host'у.
     */
    @Test
    void aSecondEntryAppliesTheNewAnchorToTheSameCard() {
        EntityExplorerView view = newViewWithTableSection();
        List<String> selections = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> selections.add(place(type, anchor)));
        view.init(Nomenclature.class, "fields/table-sections");
        EntitySummaryPanel before = card(view, Nomenclature.class);

        view.init(Nomenclature.class, "overview");

        assertThat(card(view, Nomenclature.class)).as("та же карточка").isSameAs(before);
        assertThat(cardHost(view).getComponentCount())
            .as("одна видимая карточка: повторный вход её не дублирует")
            .isEqualTo(1);
        assertThat(CardSections.tabs(before).getSelectedTab().getLabel()).isEqualTo("Обзор");
        assertThat(selections).isEmpty();
    }

    /** Смена вкладки карточки пользователем — это выбор места: host получает тип и якорь вкладки. */
    @Test
    void aCardTabSwitchIsReportedAsAPlaceOfThatType() {
        EntityExplorerView view = newViewWithTableSection();
        List<String> selections = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> selections.add(place(type, anchor)));
        view.init(Nomenclature.class);

        TabSheet inner = CardSections.tabs(card(view, Nomenclature.class));
        inner.setSelectedTab(inner.getTabAt(1));

        assertThat(selections).containsExactly("Nomenclature@fields");
        assertThat(inner.getSelectedTab().getLabel()).isEqualTo("Поля и колонки");
    }

    /**
     * Узел раздела — тоже выбор места пользователем: host получает место целиком, с разделом, и
     * получает его один раз. Отдельного сообщения «вкладка без раздела» нет: иначе адрес мигал бы,
     * а Back вёл бы на полшага (E3.2.1 §8.3).
     */
    @Test
    void aSectionNodeReportsTheExactPlaceWithItsSection() {
        EntityExplorerView view = newViewWithTableSection();
        List<String> selections = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> selections.add(place(type, anchor)));
        view.init(Nomenclature.class);

        selectSectionNode(view);

        assertThat(selections).containsExactly("Nomenclature@fields/table-sections");
        assertThat(CardSections.tabs(card(view, Nomenclature.class)).getSelectedTab().getLabel())
            .isEqualTo("Поля и колонки");
    }

    /** Вкладки, которой в карточке нет (пустая не рисуется), якорь открыть не может — и не отказывает. */
    @Test
    void anAnchorWithoutADrawnTabNamesTheMissingPlaceUntilTheUserOpensOverview() {
        EntityExplorerView view = newViewWithTableSection();
        List<String> places = new ArrayList<>();
        view.setSelectionListener((type, anchor) -> places.add(place(type, anchor)));

        view.init(Nomenclature.class, "links");
        view.showStructureDetail("Уточнение строки табличной части");

        assertThat(CardSections.tabs(card(view, Nomenclature.class)).getSelectedTab().getLabel())
            .isEqualTo("Обзор");
        assertThat(CardSections.textIn(currentCard(view)))
            .contains("Раздел «Связи» отсутствует в карточке этого типа");
        assertThat(places).isEmpty();
        assertThat(view.applyMenuEntry()).contains(new EntityExplorerView.RestoredSelection(
            Nomenclature.class, "links"));
        flatten(currentCard(view)).stream().filter(component -> component instanceof Button button
            && "Открыть обзор".equals(button.getText())).map(Button.class::cast)
            .findFirst().orElseThrow().click();
        assertThat(places).containsExactly("Nomenclature@overview/summary");
        assertThat(CardSections.textIn(currentCard(view))).doesNotContain("отсутствует в карточке");
    }

    // ------------------------------------------------------------- фикстуры и доступ к внутренностям

    /** Место выбора так, как его видит тест: тип и якорь карточки, если он назван. */
    private static String place(Class<?> type, String anchor) {
        return type.getSimpleName() + (anchor == null ? "" : "@" + anchor);
    }

    private static EntityExplorerView newView() {
        return newView(false, false);
    }

    private static EntityExplorerView newViewWithTableSection() {
        return newView(true, false);
    }

    private static EntityExplorerView newView(boolean tableSection, boolean unassignedDiagnostic) {
        EntityDescriptor descriptor = mock(EntityDescriptor.class);
        when(descriptor.exposure()).thenReturn(EntityExposure.STANDARD_ROOT);
        ExplorerSnapshot.SubsystemRef directories = new ExplorerSnapshot.SubsystemRef(
            "org.ip.subsystem.Subsystems$Directories", ResolvedValue.code("Справочники"));
        ExplorerSnapshot.OwnedSection owned = new ExplorerSnapshot.OwnedSection(
            Nomenclature.class, Nomenclature.class.getName(), "nomenclature",
            NomAttributeValue.class, NomAttributeValue.class.getName(), "NomAttributeValue",
            ResolvedValue.code("Атрибуты номенклатуры"), 1);

        List<ExplorerSnapshot.Entry> roots = List.of(
            new ExplorerSnapshot.Entry(Nomenclature.class, Nomenclature.class.getName(),
                "Nomenclature", ResolvedValue.code("Номенклатура"), EntityKind.CATALOG, descriptor,
                Optional.of(directories), Optional.empty(),
                tableSection ? List.of(owned) : List.of(),
                ExplorerSnapshot.EntryState.READY, "", List.of(), 0, 0, true),
            new ExplorerSnapshot.Entry(ReceivingDocument.class, ReceivingDocument.class.getName(),
                "ReceivingDocument", ResolvedValue.code("Приём документов"), EntityKind.DOCUMENT,
                descriptor, Optional.empty(), Optional.empty(), List.of(),
                ExplorerSnapshot.EntryState.READY, "", List.of(), 0, 0, true));

        Map<Class<?>, EntitySummary> summaries = new HashMap<>();
        summaries.put(Nomenclature.class,
            tableSection ? summaryWithTableSection(Nomenclature.class) : summary(Nomenclature.class));
        summaries.put(ReceivingDocument.class, summary(ReceivingDocument.class));
        Map<Class<?>, List<ExplorerSnapshot.OwnedSection>> sections = new HashMap<>();
        if (tableSection) {
            sections.put(Nomenclature.class, List.of(owned));
        }

        List<ExplorerSnapshot.SearchTerm> terms = new ArrayList<>();
        terms.add(new ExplorerSnapshot.SearchTerm(Nomenclature.class,
            ExplorerSnapshot.SearchKind.TYPE, "номенклатура", "", ""));
        terms.add(new ExplorerSnapshot.SearchTerm(Nomenclature.class,
            ExplorerSnapshot.SearchKind.TYPE, "nomenclature", "", ""));
        terms.add(new ExplorerSnapshot.SearchTerm(Nomenclature.class,
            ExplorerSnapshot.SearchKind.TYPE,
            Nomenclature.class.getName().toLowerCase(Locale.ROOT), "", ""));
        terms.add(new ExplorerSnapshot.SearchTerm(ReceivingDocument.class,
            ExplorerSnapshot.SearchKind.TYPE, "приём документов", "", ""));
        if (tableSection) {
            terms.add(new ExplorerSnapshot.SearchTerm(Nomenclature.class,
                ExplorerSnapshot.SearchKind.SECTION, "атрибуты номенклатуры", owned.id(), ""));
        }

        ExplorerTreeModel.CatalogView catalog = new ExplorerTreeModel.CatalogView() {
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

        List<EntitySummary.DiagnosticRow> diagnostics = unassignedDiagnostic
            ? List.of(new EntitySummary.DiagnosticRow(MetadataDiagnostic.Severity.WARNING,
                "TEST_UNASSIGNED", Nomenclature.class.getName(), "",
                "WARNING [TEST_UNASSIGNED]", null,
                ResolvedValue.fact("запись без карточки", FactOrigin.DERIVED, ""), ""))
            : List.of();

        EntityExplorerAccess access = mock(EntityExplorerAccess.class);
        when(access.allows()).thenReturn(true);
        return new EntityExplorerView(catalog, diagnostics, mock(FormNavigator.class), access);
    }

    private static EntitySummary summary(Class<?> type) {
        return new EntitySummary(type, type.getSimpleName(), ResolvedValue.code(type.getSimpleName()),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Сводка, где у типа есть и обзор, и табличная часть: видно и первый аспект, и раздел части. */
    private static EntitySummary summaryWithTableSection(Class<?> type) {
        return new EntitySummary(type, type.getSimpleName(), ResolvedValue.code(type.getSimpleName()),
            List.of(new EntitySummary.OverviewRow("Сущность",
                FacetKey.of(FacetKind.ENTITY_KIND, type),
                ResolvedValue.fact("Справочник", FactOrigin.EXPLICIT, type.getName()))),
            List.of(), List.of(), List.of(), List.of(),
            List.of(new EntitySummary.SectionRow(
                FacetKey.of(FacetKind.TABLE_SECTION, type, "NomAttributeValue"),
                ResolvedValue.fact("Атрибуты", FactOrigin.EXPLICIT,
                    "org.ip.model.NomAttributeValue"),
                "NomAttributeValue", 1, 0, 2, 1)),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // ------------------------------------------------------------- выбор узлов дерева

    /** Путь узла: индексы от корней visible-дерева; последний узел — цель выбора. */
    private static void selectNodeByPath(EntityExplorerView view, int... path) {
        TreeGrid<ExplorerTreeModel.Node> tree = treeOf(view);
        TreeData<ExplorerTreeModel.Node> data = dataOf(tree);
        ExplorerTreeModel.Node node = data.getRootItems().get(path[0]);
        for (int index = 1; index < path.length; index++) {
            node = data.getChildren(node).get(path[index]);
        }
        tree.select(node);
    }

    private static void selectTypeNode(EntityExplorerView view, Class<?> type) {
        TreeGrid<ExplorerTreeModel.Node> tree = treeOf(view);
        ExplorerTreeModel.Node node = findByType(dataOf(tree).getRootItems(), type);
        assertThat(node).as("узел типа %s есть в дереве", type.getSimpleName()).isNotNull();
        tree.select(node);
    }

    /** Раздел «Табличные части» у Номенклатуры: группа → тип → аспект «Поля и колонки» → раздел. */
    private static void selectSectionNode(EntityExplorerView view) {
        selectNodeByPath(view, 0, 0, 1, 0);
    }

    private static ExplorerTreeModel.Node findByType(List<ExplorerTreeModel.Node> nodes,
                                                     Class<?> type) {
        for (ExplorerTreeModel.Node node : nodes) {
            if (node.kind() == ExplorerTreeModel.NodeKind.TYPE && type.equals(node.type())) {
                return node;
            }
            ExplorerTreeModel.Node found = findByType(node.children(), type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ExplorerTreeModel.Node findNode(EntityExplorerView view,
                                                   java.util.function.Predicate<ExplorerTreeModel.Node> test) {
        for (ExplorerTreeModel.Node root : dataOf(treeOf(view)).getRootItems()) {
            ExplorerTreeModel.Node found = findNode(root, test);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ExplorerTreeModel.Node findNode(ExplorerTreeModel.Node node,
                                                   java.util.function.Predicate<ExplorerTreeModel.Node> test) {
        if (test.test(node)) {
            return node;
        }
        for (ExplorerTreeModel.Node child : node.children()) {
            ExplorerTreeModel.Node found = findNode(child, test);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void collapse(EntityExplorerView view, String nodeId) {
        TreeGrid<ExplorerTreeModel.Node> tree = treeOf(view);
        ExplorerTreeModel.Node node = findNode(view,
            candidate -> nodeId.equals(candidate.id()));
        assertThat(node).as("узел %s есть в дереве", nodeId).isNotNull();
        tree.collapse(node);
    }

    private static boolean isExpanded(EntityExplorerView view, String nodeId) {
        TreeGrid<ExplorerTreeModel.Node> tree = treeOf(view);
        ExplorerTreeModel.Node node = findNode(view, candidate -> nodeId.equals(candidate.id()));
        return node != null && tree.isExpanded(node);
    }

    private static List<String> rootLabels(EntityExplorerView view) {
        return dataOf(treeOf(view)).getRootItems().stream()
            .map(ExplorerTreeModel.Node::label).toList();
    }

    private static String typeLabel(EntityExplorerView view, Class<?> type) {
        ExplorerTreeModel.Node node = findByType(dataOf(treeOf(view)).getRootItems(), type);
        assertThat(node).isNotNull();
        return node.label();
    }

    private static Set<String> selectedNodeIds(EntityExplorerView view) {
        Set<String> ids = new LinkedHashSet<>();
        treeOf(view).getSelectedItems().forEach(node -> ids.add(node.id()));
        return ids;
    }

    // ------------------------------------------------------------- доступ к компонентам и полям

    /** Одна колонка дерева на вид: колонка добавляется один раз, а не на каждый вход. */
    private static int treeColumnCount(EntityExplorerView view) {
        return treeOf(view).getColumns().size();
    }

    /** Панель неадресованных диагностик ищется по заголовку: их может быть только одна на вид. */
    private static long diagnosticsPanels(EntityExplorerView view) {
        return flatten(view).stream()
            .filter(H4.class::isInstance)
            .map(H4.class::cast)
            .filter(title -> title.getText().startsWith("Неадресованные диагностики"))
            .count();
    }

    private static Span nothingFound(EntityExplorerView view) {
        return flatten(view).stream()
            .filter(Span.class::isInstance)
            .map(Span.class::cast)
            .filter(span -> "Ничего не найдено".equals(span.getText()))
            .findFirst().orElseThrow();
    }

    private static Select<?> selectByLabel(EntityExplorerView view, String label) {
        for (Component component : flatten(view)) {
            if (component instanceof Select<?> select && label.equals(select.getLabel())) {
                return select;
            }
        }
        throw new AssertionError("Нет списка с подписью " + label);
    }

    @SuppressWarnings("unchecked")
    private static Select<EntityKind> kindFilter(EntityExplorerView view) {
        return (Select<EntityKind>) selectByLabel(view, "Вид");
    }

    private static List<Component> flatten(Component root) {
        List<Component> all = new ArrayList<>();
        all.add(root);
        root.getChildren().forEach(child -> all.addAll(flatten(child)));
        return all;
    }

    private static VerticalLayout cardHost(EntityExplorerView view) {
        return (VerticalLayout) ReflectionTestUtils.getField(view, "cardHost");
    }

    private static EntitySummaryPanel currentCard(EntityExplorerView view) {
        assertThat(cardHost(view).getComponentCount())
            .as("видимая карточка одна").isEqualTo(1);
        return (EntitySummaryPanel) cardHost(view).getComponentAt(0);
    }

    private static EntitySummaryPanel card(EntityExplorerView view, Class<?> type) {
        return panels(view).get(type);
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, EntitySummaryPanel> panels(EntityExplorerView view) {
        return (Map<Class<?>, EntitySummaryPanel>) ReflectionTestUtils.getField(view, "panels");
    }

    @SuppressWarnings("unchecked")
    private static Select<ExplorerTreeModel.GroupMode> groupMode(EntityExplorerView view) {
        return (Select<ExplorerTreeModel.GroupMode>) selectByLabel(view, "Группировка");
    }

    @SuppressWarnings("unchecked")
    private static TreeGrid<ExplorerTreeModel.Node> treeOf(EntityExplorerView view) {
        return component(view, TreeGrid.class);
    }

    @SuppressWarnings("unchecked")
    private static TreeData<ExplorerTreeModel.Node> dataOf(TreeGrid<ExplorerTreeModel.Node> tree) {
        TreeDataProvider<ExplorerTreeModel.Node> provider =
            (TreeDataProvider<ExplorerTreeModel.Node>) tree.getDataProvider();
        return provider.getTreeData();
    }

    private static <T extends Component> T component(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        return root.getChildren()
            .map(child -> component(child, type))
            .filter(value -> value != null)
            .findFirst().orElse(null);
    }
}
