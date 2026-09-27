package org.ipro.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.shared.Tooltip;
import com.vaadin.flow.data.selection.SingleSelect;
import com.vaadin.flow.dom.Element;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.ipro.crud.BaseEntity;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.filtergrid.FilterGrid;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.RlsUiGate.AccessDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E1.3: состояние generic-кнопок списка считает решение, а не формула внутри формы.
 *
 * <p>Проверки идут на настоящем резолвере (реестр + провайдер входов + чистая политика) и настоящем
 * каталоге дескрипторов: смысл среза в том, что у списка и у решения один источник, поэтому мок
 * решения здесь проверял бы сам себя. Мокирует только то, что действительно внешнее: грид Vaadin,
 * метаданные формы и RLS-гейт.</p>
 *
 * <p>Отдельный случай — двойной клик по строке: он обязан подчиняться тому же решению, что и кнопка.
 * До E1.3 он открывал карточку в обход проверки прав (расхождение найдено в E1.0).</p>
 */
class ListFormActionDecisionTest {

    /** Полный generic CRUD: как обычный справочник. */
    @Entity
    static class Standard extends BaseEntity {
    }

    /** Создание без изменения: носитель действия «Просмотр». */
    @Entity
    static class CreateOnly extends BaseEntity {
    }

    private static final Set<FetchScenario> READS =
        Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP);

    private RlsUiGate gate;

    @BeforeEach
    void setUp() {
        gate = mock(RlsUiGate.class);
        when(gate.canCreate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canUpdate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canDelete(any())).thenReturn(AccessDecision.ALLOWED);
    }

    @Test
    void fullCrudTypeShowsCreateEditDeleteAndNoOpen() {
        ListForm<Standard, Long> form = form(Standard.class, writes(DataOperation.CREATE,
            DataOperation.UPDATE, DataOperation.DELETE), null);

        assertThat(form.getAddButton().isVisible()).isTrue();
        assertThat(form.getAddButton().isEnabled()).isTrue();
        assertThat(form.getEditButton().isEnabled()).isFalse();
        assertThat(form.getEditButton().isVisible()).isTrue();
        assertThat(form.getDeleteButton().isEnabled()).isFalse();
        assertThat(form.getOpenButton().isVisible()).isFalse();
    }

    /** Отсутствие UPDATE у типа — не «серая кнопка», а другой набор действий: «Изменить» нет. */
    @Test
    void typeWithoutUpdateShowsOpenInsteadOfEdit() {
        ListForm<CreateOnly, Long> form = form(CreateOnly.class,
            writes(DataOperation.CREATE), null);

        assertThat(form.getEditButton().isVisible()).isFalse();
        assertThat(form.getDeleteButton().isVisible()).isFalse();
        assertThat(form.getOpenButton().isVisible()).isTrue();
        assertThat(form.getOpenButton().isEnabled()).isFalse();
        assertThat(form.getAddButton().isEnabled()).isTrue();
    }

    @Test
    void createWaitsForRequiredContextAndNamesTheMissingField() {
        ListForm<Standard, Long> form = form(Standard.class, writes(DataOperation.CREATE,
            DataOperation.UPDATE), null);
        form.setContextFilters(List.of(ContextFilterField.requiredAuto("code", "Код")));

        assertThat(form.getAddButton().isEnabled()).isFalse();
        assertThat(tooltipOf(form.getAddButton())).contains("Код");

        form.setContextFilter("code", "A");

        assertThat(form.getAddButton().isEnabled()).isTrue();
        assertThat(tooltipOf(form.getAddButton())).isNull();
    }

    @Test
    void rowRightsDriveRowActionsAndKeepGateReason() {
        Standard row = new Standard();
        when(gate.canUpdate(row)).thenReturn(new AccessDecision(false,
            "Нет прав на изменение (измерение JOURNAL, id=7)"));
        ListForm<Standard, Long> form = form(Standard.class, writes(DataOperation.CREATE,
            DataOperation.UPDATE, DataOperation.DELETE), row);

        assertThat(form.getEditButton().isEnabled()).isFalse();
        assertThat(tooltipOf(form.getEditButton()))
            .isEqualTo("Нет прав на изменение (измерение JOURNAL, id=7)");
        assertThat(form.getDeleteButton().isEnabled()).isTrue();
        assertThat(form.getOpenButton().isVisible())
            .as("отказ в правах на изменение не оставляет строку без пути чтения (E1.2-pilot)")
            .isTrue();
        assertThat(form.getOpenButton().isEnabled()).isTrue();
    }

    /**
     * E1.2-pilot: строка, изменение которой отказано правами, открывается двойным кликом — через
     * решение по «Просмотру», а не в обход него. Карточка при этом правиться не будет: её режим
     * решает то же решение (E1.5). До этого у такой строки не оставалось пути чтения вовсе:
     * «Изменить» запрещено, «Просмотр» был объявлен только для типов без {@code UPDATE}.
     */
    @Test
    void doubleClickOpensRowForReadingWhenUpdateIsDenied() {
        Standard row = new Standard();
        when(gate.canUpdate(row)).thenReturn(new AccessDecision(false, "нет прав"));
        ListForm<Standard, Long> form = form(Standard.class, writes(DataOperation.CREATE,
            DataOperation.UPDATE), row);

        Consumer<Standard> onEdit = capturing();
        form.setOnEdit(onEdit);
        form.handleRowDoubleClick(row);

        verify(onEdit).accept(row);
    }

    /**
     * Обратная сторона: там, где применимого действия нет и по типу (нет {@code DETAIL}), двойной
     * клик по-прежнему ничего не открывает — иначе возвращался бы обход решения из E1.0.
     */
    @Test
    void doubleClickDoesNotOpenRowWithoutDetail() {
        Standard row = new Standard();
        ListForm<Standard, Long> form = form(Standard.class, writes(DataOperation.CREATE,
            DataOperation.UPDATE), row, Set.of(FetchScenario.LIST, FetchScenario.LOOKUP));

        Consumer<Standard> onEdit = capturing();
        form.setOnEdit(onEdit);
        form.handleRowDoubleClick(row);

        verify(onEdit, never()).accept(any());
    }

    /**
     * Объяснение недоступной строки берётся у видимого действия: при отказе в правах — про права,
     * а не про тип. Носитель этого случая — прикладное подавление «Просмотра»
     * ({@code suppress-override}): только тогда строка одновременно «видима-и-запрещена» на
     * изменение и не имеет применимого просмотра.
     */
    @Test
    void blockedRowExplanationNamesRightsWhenReadActionIsSuppressed() {
        Standard row = new Standard();
        when(gate.canUpdate(row)).thenReturn(new AccessDecision(false,
            "Нет прав на изменение (измерение JOURNAL, id=7)"));

        ActionResolver resolver = resolver(Standard.class, writes(DataOperation.CREATE,
            DataOperation.UPDATE), List.of(ActionDefinition.suppress(CrudAction.OPEN,
                ActionSurface.LIST_TOOLBAR, Standard.class)));
        ActionDecision edit = resolver.decide(CrudAction.EDIT, row, true);
        ActionDecision open = resolver.decide(CrudAction.OPEN, row, true);

        assertThat(edit.visible()).isTrue();
        assertThat(open.visible())
            .as("прикладное подавление делает объяснение скрытого действия бесполезным для пользователя")
            .isFalse();

        ActionDecision explanation = ListForm.blockedExplanation(edit, open);
        assertThat(explanation.reason()).isEqualTo(ActionDecision.Reason.ACCESS_DENIED);
        assertThat(explanation.message())
            .isEqualTo("Нет прав на изменение (измерение JOURNAL, id=7)");
    }

    /**
     * Обратная сторона правила: когда применимого действия нет вовсе (тип без {@code DETAIL}),
     * объясняется применимость типа — скрытое решение остаётся единственным ответом.
     */
    @Test
    void blockedRowExplanationFallsBackToApplicabilityWhenNothingApplies() {
        ActionDecision edit = ActionDecision.hidden(ActionDecision.Reason.TYPE_NOT_SUPPORTED,
            "Тип не поддерживает DETAIL");
        ActionDecision open = ActionDecision.hidden(ActionDecision.Reason.TYPE_NOT_SUPPORTED,
            "Тип не поддерживает DETAIL");

        assertThat(ListForm.blockedExplanation(edit, open)).isSameAs(open);
    }

    @Test
    void doubleClickOpensWhenUpdateIsAvailable() {
        Standard row = new Standard();
        ListForm<Standard, Long> form = form(Standard.class, writes(DataOperation.CREATE,
            DataOperation.UPDATE), row);

        Consumer<Standard> onEdit = capturing();
        form.setOnEdit(onEdit);
        form.handleRowDoubleClick(row);

        ArgumentCaptor<Standard> captured = ArgumentCaptor.forClass(Standard.class);
        verify(onEdit).accept(captured.capture());
        assertThat(captured.getValue()).isSameAs(row);
    }

    @Test
    void readOnlyHidesGenericActionsButKeepsRefresh() {
        ListForm<Standard, Long> form = form(Standard.class, writes(DataOperation.CREATE,
            DataOperation.UPDATE, DataOperation.DELETE), null);

        form.setReadOnly(true);

        assertThat(form.isReadOnly()).isTrue();
        assertThat(form.getAddButton().isVisible()).isFalse();
        assertThat(form.getEditButton().isVisible()).isFalse();
        assertThat(form.getDeleteButton().isVisible()).isFalse();
        assertThat(form.getRefreshButton().isVisible()).isTrue();

        form.setReadOnly(false);

        assertThat(form.getAddButton().isVisible()).isTrue();
    }

    @Test
    void readOnlyBlocksDoubleClickAndProgrammaticCrudClicksIncludingCopy() {
        Standard row = new Standard();
        ListForm<Standard, Long> form = form(Standard.class, writes(DataOperation.CREATE,
            DataOperation.UPDATE, DataOperation.DELETE), row);
        Consumer<Standard> onAdd = capturing();
        Consumer<Standard> onEdit = capturing();
        Consumer<Standard> onCopy = capturing();
        form.setOnAdd(onAdd);
        form.setOnEdit(onEdit);
        form.setOnCopy(onCopy);
        Button copy = form.getToolbar().getChildren()
            .filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> "Копировать".equals(button.getText()))
            .findFirst().orElseThrow();
        assertThat(copy.isVisible()).isTrue();
        assertThat(copy.isEnabled()).isTrue();

        form.setReadOnly(true);
        assertThat(copy.isVisible()).isFalse();
        form.handleRowDoubleClick(row);
        form.getAddButton().click();
        form.getEditButton().click();
        form.getDeleteButton().click();
        copy.click();

        verify(onAdd, never()).accept(any());
        verify(onEdit, never()).accept(any());
        verify(onCopy, never()).accept(any());
    }

    /**
     * Без решателя форма остаётся работоспособной (прямое использование и другие тесты): прежнее
     * поведение — строковые действия ждут выделения, создание — обязательного контекста, а
     * «Просмотр» недоступен, потому что его условие требует capability типа, которой здесь нет.
     */
    @Test
    void formWithoutResolverKeepsItsOwnDefaults() {
        ListForm<Standard, Long> form = listForm(Standard.class, null);

        assertThat(form.getAddButton().isEnabled()).isTrue();
        assertThat(form.getEditButton().isEnabled()).isFalse();
        assertThat(form.getOpenButton().isVisible()).isFalse();
    }

    /**
     * E2.1: без источника адреса affordance не существует вовсе — это не «ссылка не построима»,
     * а «форма собрана без слоя адресов».
     */
    @Test
    void copyLinkAffordanceIsAbsentWithoutTheAddressService() {
        ListForm<Standard, Long> form = form(Standard.class, writes(DataOperation.CREATE), null);

        assertThat(copyLinkButton(form))
            .as("кнопка не появляется сама: её создаёт поставленный сервис ссылок")
            .isEmpty();
    }

    /**
     * E2.1: вопрос об адресе задаётся не во время сборки формы.
     *
     * <p>Каталог адресов строится один раз после готовности композиции форм, поэтому сборка формы
     * не имеет права его спрашивать: набор вариантов ещё не полон, а в режиме с разрешённой
     * регистрацией во время работы он не станет полным никогда (на этом падал реальный IT).
     * Каталог здесь — мок, который роняет тест при первом обращении: «не спросили» проверяется
     * утверждением, а не надеждой.</p>
     */
    @Test
    void copyLinkAffordanceDoesNotAskTheCatalogWhileTheFormIsBeingAssembled() {
        ListForm<Standard, Long> form = listForm(Standard.class, null);
        EntityDescriptorCatalog descriptors = catalog(Standard.class, READS,
            writes(DataOperation.CREATE));
        FormRouteCatalog routes = mock(FormRouteCatalog.class);
        when(routes.notLinkable(any(), any(), any())).thenThrow(new AssertionError(
            "каталог адресов спрошен во время сборки формы"));

        form.setActionResolver(new ActionResolver(
            new ActionRegistry(CrudAction.listToolbarDefaults(), List.of()),
            new ActionContextProvider(descriptors, gate, routes),
            ActionHandlerRegistry.empty(), ActionSurface.LIST_TOOLBAR, Standard.class, null));
        form.setFormLinkService(mock(FormLinkService.class));

        Button copyLink = copyLinkButton(form).orElseThrow();
        assertThat(copyLink.isVisible())
            .as("до присоединения к UI состояние не вычисляется: affordance ждёт живой кнопки")
            .isFalse();
        assertThat(copyLink.isEnabled()).isFalse();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Consumer<T> capturing() {
        return mock(Consumer.class);
    }

    /** Кнопка адреса списка в тулбаре, если она создана (E2.1). */
    private static Optional<Button> copyLinkButton(ListForm<?, ?> form) {
        return form.getToolbar().getChildren()
            .filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> "Скопировать ссылку".equals(button.getText()))
            .findFirst();
    }

    /** Подсказка читается через {@code Tooltip}: {@code getTooltipText} в Vaadin 25 нет. */
    private static String tooltipOf(Button button) {
        Tooltip tooltip = button.getTooltip();
        return tooltip == null ? null : tooltip.getText();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends BaseEntity> ActionResolver resolver(Class<T> type, Set<DataOperation> writes) {
        return resolver(type, writes, List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends BaseEntity> ActionResolver resolver(Class<T> type, Set<DataOperation> writes,
                                                           List<ActionDefinition> overrides) {
        return new ActionResolver(
            new ActionRegistry(CrudAction.listToolbarDefaults(), overrides),
            new ActionContextProvider(catalog(type, READS, writes), gate),
            ActionHandlerRegistry.empty(),
            ActionSurface.LIST_TOOLBAR, type, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends BaseEntity> ListForm<T, Long> form(Class<T> type, Set<DataOperation> writes,
                                                          T selected) {
        return form(type, writes, selected, READS);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends BaseEntity> ListForm<T, Long> form(Class<T> type, Set<DataOperation> writes,
                                                          T selected, Set<FetchScenario> reads) {
        ListForm<T, Long> form = listForm(type, selected);
        EntityDescriptorCatalog catalog = catalog(type, reads, writes);
        form.setActionResolver(new ActionResolver(
            new ActionRegistry(CrudAction.listToolbarDefaults(), List.of()),
            new ActionContextProvider(catalog, gate),
            ActionHandlerRegistry.empty(),
            ActionSurface.LIST_TOOLBAR, type, null));
        return form;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends BaseEntity> ListForm<T, Long> listForm(Class<T> type, T selected) {
        EntityMetadataInfo meta = mock(EntityMetadataInfo.class);
        when(meta.getEntityClass()).thenReturn((Class) type);
        when(meta.getListColumnPaths()).thenReturn(List.of());

        Grid<T> grid = mock(Grid.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SingleSelect<Grid<T>, T> selection = mock(SingleSelect.class);
        when(grid.asSingleSelect()).thenReturn(selection);
        when(selection.getValue()).thenReturn(selected);

        FilterGrid<T> filterGrid = mock(FilterGrid.class);
        when(filterGrid.getGrid()).thenReturn(grid);
        when(filterGrid.getElement()).thenReturn(new Element("div"));
        return new ListForm<>(meta, filterGrid);
    }

    private static <T> EntityDescriptorCatalog catalog(Class<T> type, Set<FetchScenario> reads,
                                                       Set<DataOperation> writes) {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(type));

        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        when(sections.findByRow(any())).thenReturn(Optional.empty());

        return new EntityDescriptorCatalog(managed, sections, new MetadataResolver(), List.of(),
            List.of(new EntityCapabilityOverride(type, reads, writes, "policy фикстуры E1.3")));
    }

    private static Set<DataOperation> writes(DataOperation... operations) {
        return Set.of(operations);
    }
}
