package org.ipro.form.builtin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.data.selection.SingleSelect;
import com.vaadin.flow.dom.Element;
import jakarta.persistence.Entity;
import org.ipro.crud.BaseEntity;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.filtergrid.FilterGrid;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionRequirement;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.RlsUiGate.AccessDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E1.6a: прикладное действие списка — объявление, решение, исполнение.
 *
 * <p>Проверяется то, чего не было у легаси-команды: доступность действия считается тем же
 * решением, что у CRUD-кнопок (требование вместо предиката по форме), состав берётся из объявлений,
 * а не из перечня, известного рендереру, и <b>недоступное действие не исполняется программным
 * кликом</b> — решение пересчитывается в момент клика, поэтому устаревшее состояние кнопки не
 * становится разрешением.</p>
 *
 * <p>Тест идёт на настоящих реестре, провайдере входов и политике: мок решения проверял бы сам себя.
 * Мокируются только внешние границы — грид Vaadin, метаданные формы, RLS-гейт и навигация.</p>
 */
class ListFormDeclaredActionTest {

    @Entity
    static class Standard extends BaseEntity {
    }

    /** Второй тип с той же формой: у него объявленного действия быть не должно. */
    @Entity
    static class Other extends BaseEntity {
    }

    private static final Set<FetchScenario> READS =
        Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP);

    private static final ActionId ROW_ACTION_ID = ActionId.of("fixture.materials-only");

    /** Объявление действия: ключ, поверхность, тип, требование — всё данные. */
    static ActionDefinition declaration(Class<?> entityType) {
        return ActionDefinition.forEntity(ROW_ACTION_ID, ActionSurface.LIST_TOOLBAR, entityType,
            "Только материалы", "LIST", 100, ActionRequirement.selectionOnly());
    }

    /** Исполнитель, записывающий вызовы: тест проверяет вызов, а не его побочный эффект. */
    private static class RecordingHandler implements ActionHandler {

        private final Class<?> entityType;
        private final List<ActionInvocation> invocations = new ArrayList<>();

        private RecordingHandler(Class<?> entityType) {
            this.entityType = entityType;
        }

        @Override
        public ActionDefinition definition() {
            return declaration(entityType);
        }

        @Override
        public void execute(ActionInvocation invocation) {
            invocations.add(invocation);
        }
    }

    private RlsUiGate gate;

    @BeforeEach
    void setUp() {
        gate = mock(RlsUiGate.class);
        when(gate.canCreate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canUpdate(any())).thenReturn(AccessDecision.ALLOWED);
        when(gate.canDelete(any())).thenReturn(AccessDecision.ALLOWED);
    }

    @Test
    void declaredActionIsRenderedAndWaitsForSelection() {
        RecordingHandler handler = new RecordingHandler(Standard.class);

        ListForm<?, ?> withoutSelection = form(Standard.class, null, handler).form;
        ListForm<?, ?> withSelection = form(Standard.class, new Standard(), handler).form;

        assertThat(button(withoutSelection, "Только материалы")).isNotNull();
        assertThat(button(withoutSelection, "Только материалы").isVisible()).isTrue();
        assertThat(button(withoutSelection, "Только материалы").isEnabled()).isFalse();
        assertThat(button(withSelection, "Только материалы").isEnabled()).isTrue();
    }

    /** Прикладное действие — про свой тип: у соседней сущности его кнопки нет. */
    @Test
    void declaredActionIsNotOfferedForAnotherType() {
        RecordingHandler handler = new RecordingHandler(Standard.class);

        ListForm<?, ?> other = form(Other.class, new Other(), handler).form;

        assertThat(button(other, "Только материалы")).isNull();
    }

    /**
     * Граница исполнения: кнопка была собрана без выделения, клик по ней — программный. Действие
     * обязано не исполниться, потому что требование не выполнено на момент клика.
     */
    @Test
    void programmaticClickOnDisabledActionDoesNotExecute() {
        RecordingHandler handler = new RecordingHandler(Standard.class);
        ListForm<?, ?> form = form(Standard.class, null, handler).form;

        button(form, "Только материалы").click();

        assertThat(handler.invocations).isEmpty();
    }

    /** Разрешённое действие получает снимок списка: строку, навигацию, контекст и обновление. */
    @Test
    void enabledActionExecutesWithSnapshotOfTheList() {
        Standard row = new Standard();
        RecordingHandler handler = new RecordingHandler(Standard.class);
        Fixture fixture = form(Standard.class, row, handler);
        fixture.form.setViewSupport(null, null, "Standard");
        fixture.form.setOpeningParameters(Map.of("target", "42"));

        button(fixture.form, "Только материалы").click();

        assertThat(handler.invocations).hasSize(1);
        ActionInvocation invocation = handler.invocations.get(0);
        assertThat(invocation.selected()).isSameAs(row);
        assertThat(invocation.entityType()).isEqualTo(Standard.class);
        assertThat(invocation.variant()).isNull();
        assertThat(invocation.navigator()).isSameAs(fixture.navigator);
        assertThat(invocation.parameter("target")).isEqualTo("42");
        assertThat(invocation.canRefresh()).isTrue();
    }

    @Test
    void invocationUsesVariantAndEffectiveFiltersAndPreservesNullParameters() {
        RecordingHandler handler = new RecordingHandler(Standard.class);
        Fixture fixture = form(Standard.class, new Standard(), handler);
        fixture.form.setViewSupport(null, null, "Standard.archived");
        fixture.form.setFormVariant("archived");
        fixture.form.setOpeningContextFilter("owner.id", 7L);
        fixture.form.setContextFilter("owner.id", 8L);
        fixture.form.setContextFilter("status", "ready");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("optional", null);
        fixture.form.setOpeningParameters(parameters);

        button(fixture.form, "Только материалы").click();

        ActionInvocation invocation = handler.invocations.get(0);
        assertThat(invocation.variant()).isEqualTo("archived");
        assertThat(invocation.contextFilter("owner.id")).isEqualTo(7L);
        assertThat(invocation.contextFilter("status")).isEqualTo("ready");
        assertThat(invocation.openingParameters()).containsKey("optional");
        assertThat(invocation.parameter("optional")).isNull();
        assertThatThrownBy(() -> invocation.openingParameters().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> invocation.contextFilters().put("new", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectedLocalActionDoesNotModifyHandlerMap() throws ReflectiveOperationException {
        RecordingHandler registered = new RecordingHandler(Standard.class);
        Fixture fixture = form(Standard.class, new Standard(), registered);
        RecordingHandler colliding = new RecordingHandler(Standard.class);

        assertThatThrownBy(() -> fixture.form.addAction(colliding))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fixture.materials-only");

        java.lang.reflect.Field field = ListForm.class.getDeclaredField("localActionHandlers");
        field.setAccessible(true);
        assertThat((Map<?, ?>) field.get(fixture.form)).isEmpty();
        assertThat(button(fixture.form, "Только материалы")).isNotNull();
    }

    /**
     * Режим просмотра прячет CRUD-действия, меняющие данные, но прикладное действие он не решает:
     * его доступность выражается требованием, и второе правило молча скрыло бы выполнимое действие.
     */
    @Test
    void readOnlyModeDoesNotDecideForDeclaredAction() {
        RecordingHandler handler = new RecordingHandler(Standard.class);
        ListForm<?, ?> form = form(Standard.class, new Standard(), handler).form;

        form.setReadOnly(true);

        assertThat(button(form, "Только материалы").isVisible()).isTrue();
        assertThat(button(form, "Только материалы").isEnabled()).isTrue();
    }

    /**
     * Предметная кнопка встаёт до разделителя «Ещё», а не за ним (E1.6b). Порядок здесь не
     * косметика: «Ещё» объявлена крайней справа, а объявленные действия рисуются позже сборки
     * тулбара — решатель ставит координатор. Без этого правила прикладная кнопка оказалась бы
     * правее меню, то есть там, где раньше был шов тулбара, и вид списка менялся бы вместе с
     * рефакторингом.
     */
    @Test
    void declaredActionIsPlacedLeftOfTheMoreMenu() {
        RecordingHandler handler = new RecordingHandler(Standard.class);
        ActionHandlerRegistry handlerRegistry = new ActionHandlerRegistry(List.of(handler));
        ActionRegistry registry = new ActionRegistry(
            Stream.concat(CrudAction.listToolbarDefaults().stream(),
                handlerRegistry.definitions().stream()).toList(),
            List.of());
        ActionResolver resolver = new ActionResolver(registry,
            new ActionContextProvider(catalog(Standard.class), gate), handlerRegistry,
            ActionSurface.LIST_TOOLBAR, Standard.class, null);

        ListForm<?, ?> form = listForm(Standard.class, new Standard());
        form.installMoreMenu();
        form.setActionResolver(resolver);

        List<com.vaadin.flow.component.Component> toolbar =
            form.getToolbar().getChildren().toList();
        int action = indexOf(toolbar, component -> component instanceof Button button
            && "Только материалы".equals(button.getText()));
        int moreMenu = indexOf(toolbar,
            com.vaadin.flow.component.menubar.MenuBar.class::isInstance);

        assertThat(action).isGreaterThanOrEqualTo(0);
        assertThat(moreMenu).isGreaterThanOrEqualTo(0);
        assertThat(action).as("объявленное действие обязано остаться слева от «Ещё»")
            .isLessThan(moreMenu);
    }

    /** Индекс первого компонента тулбара, подходящего под условие; {@code -1} — не найден. */
    private static int indexOf(List<com.vaadin.flow.component.Component> toolbar,
                               java.util.function.Predicate<com.vaadin.flow.component.Component> match) {
        for (int index = 0; index < toolbar.size(); index++) {
            if (match.test(toolbar.get(index))) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Действие одного составного view: объявление у него своё, поэтому ему не нужны ни Spring-бин,
     * ни запись в реестре объявлений — и всё равно это тот же путь решения и исполнения.
     */
    @Test
    void localActionNeedsNoRegistryEntryAndRunsTheSamePath() {
        RecordingHandler registryHandler = new RecordingHandler(Standard.class);
        Fixture fixture = form(Standard.class, new Standard(), registryHandler);
        RecordingHandler local = new RecordingHandler(Standard.class) {
            @Override
            public ActionDefinition definition() {
                return ActionDefinition.forEntity(ActionId.of("fixture.local"),
                    ActionSurface.LIST_TOOLBAR, Standard.class, "Локальное действие", null, 200,
                    ActionRequirement.selectionOnly());
            }
        };

        fixture.form.addAction(local);
        button(fixture.form, "Локальное действие").click();

        assertThat(local.invocations).hasSize(1);
        assertThat(local.invocations.get(0).selected()).isNotNull();
    }

    /** Один и тот же ключ дважды — ошибка старта, а не выбор по порядку бинов. */
    @Test
    void duplicateHandlerKeysFailAtStartup() {
        assertThatThrownBy(() -> new ActionHandlerRegistry(List.of(
            new RecordingHandler(Standard.class), new RecordingHandler(Standard.class))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат обработчика действия")
            .hasMessageContaining("fixture.materials-only");
    }

    // --- фикстуры -------------------------------------------------------------------------------

    private record Fixture(ListForm<?, ?> form, FormNavigator navigator) {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Fixture form(Class<?> type, BaseEntity selected, RecordingHandler handler) {
        // Состав объявлений собирается так же, как в приложении: defaults платформы плюс объявления
        // исполнителей. Один источник состава — иначе рендерер и решение разошлись бы.
        ActionHandlerRegistry handlerRegistry = new ActionHandlerRegistry(List.of(handler));
        ActionRegistry registry = new ActionRegistry(
            Stream.concat(CrudAction.listToolbarDefaults().stream(),
                handlerRegistry.definitions().stream()).toList(),
            List.of());
        ActionResolver resolver = new ActionResolver(registry,
            new ActionContextProvider(catalog(type), gate), handlerRegistry,
            ActionSurface.LIST_TOOLBAR, type, null);

        FormNavigator navigator = mock(FormNavigator.class);
        ListForm form = listForm((Class) type, selected);
        form.setActionResolver(resolver);
        form.setFormNavigator(navigator);
        return new Fixture(form, navigator);
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

    private static Button button(ListForm<?, ?> form, String title) {
        return form.getToolbar().getChildren()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(button -> title.equals(button.getText()))
            .findFirst()
            .orElse(null);
    }

    private static EntityDescriptorCatalog catalog(Class<?> type) {
        ManagedEntityCatalog managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(type));

        SectionMetadataRegistry sections = mock(SectionMetadataRegistry.class);
        when(sections.findByRow(any())).thenReturn(Optional.empty());

        return new EntityDescriptorCatalog(managed, sections, new MetadataResolver(), List.of(),
            List.of(new EntityCapabilityOverride(type,
                READS, Set.of(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE),
                "policy фикстуры E1.6a")));
    }
}
