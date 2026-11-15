package org.ipro.query.ui.listform;

import com.vaadin.flow.component.button.Button;
import org.ipro.filtergrid.FooterAggregateKind;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.metadata.GridViewState;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.grid.QueryGridSpec;
import org.ipro.query.ui.grid.QueryGridState;
import org.ipro.query.ui.grid.QueryRunHistory;
import org.ipro.query.ui.grid.QueryTaskRunner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectionListFormTest {
    private static final List<jakarta.persistence.Tuple> ROWS = List.of(row(1L, "A"), row(2L, "B"));

    private static jakarta.persistence.Tuple row(Long id, String name) {
        var tuple = mock(jakarta.persistence.Tuple.class);
        when(tuple.get("id")).thenReturn(id);
        when(tuple.get("name")).thenReturn(name);
        return tuple;
    }

    private static QueryGridEnvironment environment() {
        return new QueryGridEnvironment((spec, context, values) -> {
            var definition = org.ipro.filtergrid.projection.ProjectionDefinition.define("fixture")
                    .hql("select x.id as id, x.name as name from X x").rowKey("id")
                    .column("id", "ID", org.ipro.filtergrid.projection.DataType.LONG, false, false)
                    .column("name", "Имя", org.ipro.filtergrid.projection.DataType.STRING, true, true, true)
                    .mode(ProjectionExecutionMode.IN_MEMORY).build();
            var strategy = new org.ipro.filtergrid.projection.ExecutionStrategy() {
                @Override public org.ipro.filtergrid.projection.ProjectionDefinition definition() { return definition; }
                @Override public long count(org.ipro.filtergrid.projection.ExecutionContext context) { return ROWS.size(); }
                @Override public List<?> fetch(org.ipro.filtergrid.projection.ExecutionContext context) { return new ArrayList<>(ROWS); }
            };
            return new org.ipro.query.ui.grid.QueryProjectionSource.Prepared(definition, Map.of(), strategy, null, 30_000);
        }, (type, id) -> "#" + id, null, QueryTaskRunner.direct(), new QueryRunHistory(), () -> "tester");
    }

    private static QueryGridSpec spec(QueryGridState standard) {
        return new QueryGridSpec("Поступление", "receipts#1",
                new QueryDefinition("select 1", null, null, null, List.of()), ProjectionExecutionMode.IN_MEMORY, 0,
                null, standard, null);
    }

    /** Хранилище видов в памяти: общий вид правят все, личный — автор. */
    static class MemoryViews implements QueryListViews {
        final Map<String, List<View>> byForm = new java.util.LinkedHashMap<>();
        final Map<String, String> defaults = new java.util.HashMap<>();
        final AtomicInteger ids = new AtomicInteger();

        @Override public List<View> views(String formKey) { return byForm.getOrDefault(formKey, List.of()); }
        @Override public Optional<String> defaultViewId(String formKey) { return Optional.ofNullable(defaults.get(formKey)); }
        @Override public void setDefaultView(String formKey, String viewId) {
            if (viewId == null) defaults.remove(formKey); else defaults.put(formKey, viewId);
        }
        @Override public View create(String formKey, String name, boolean shared, GridViewState state) {
            View view = new View("v" + ids.incrementAndGet(), name, shared, true, state);
            byForm.computeIfAbsent(formKey, key -> new ArrayList<>()).add(view);
            return view;
        }
        @Override public View update(String formKey, String viewId, String name, boolean shared, GridViewState state) {
            var list = byForm.get(formKey);
            list.replaceAll(view -> view.id().equals(viewId) ? new View(viewId, name, shared, true, state) : view);
            return list.stream().filter(view -> view.id().equals(viewId)).findFirst().orElseThrow();
        }
        @Override public void delete(String formKey, String viewId) {
            byForm.get(formKey).removeIf(view -> view.id().equals(viewId));
        }
    }

    @Test
    void differentVariantsGiveDifferentViewKeysAndTheSameVariantSharesThem() {
        var views = new MemoryViews();
        var byWorkshop = new ProjectionListForm(environment(), spec(null), "receiving.receipts",
                QueryListOptions.DEFAULT.withVariant("by-workshop").withViews(views));
        var byNomenclature = new ProjectionListForm(environment(), spec(null), "receiving.receipts",
                QueryListOptions.DEFAULT.withVariant("by-nomenclature").withViews(views));
        var again = new ProjectionListForm(environment(), spec(null), "receiving.receipts",
                QueryListOptions.DEFAULT.withVariant("by-workshop"));
        var plain = new ProjectionListForm(environment(), spec(null), "receiving.receipts", null);

        assertThat(byWorkshop.formKey()).isEqualTo("querylist.receiving.receipts.by-workshop");
        assertThat(byNomenclature.formKey()).isEqualTo("querylist.receiving.receipts.by-nomenclature");
        assertThat(again.formKey()).isEqualTo(byWorkshop.formKey());
        assertThat(plain.formKey()).as("без варианта виды ключа общие").isEqualTo("querylist.receiving.receipts");
        assertThatThrownBy(() -> QueryListOptions.DEFAULT.withVariant("By Workshop"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolbarHasItsOwnRowWithRefreshViewsAndMore() {
        var form = new ProjectionListForm(environment(), spec(null), "k",
                QueryListOptions.DEFAULT.withViews(new MemoryViews()).withLink(() -> "http://x/query-list/k"));

        assertThat(form.toolbarLabels()).containsExactly("Обновить", "Виды", "Ещё");
        assertThat(form.moreItems()).containsExactly("Колонки…", "Группировка", "Итоги…", "Скопировать ссылку");
        assertThat(form.getElement().getTextRecursively()).contains("Поступление");
    }

    @Test
    void embeddedViewHasNoTitleNoParametersAndOnlyRefreshAndMore() {
        var form = new ProjectionListForm(environment(), spec(null), "k",
                QueryListOptions.DEFAULT.asEmbedded().withViews(new MemoryViews()));

        assertThat(form.toolbarLabels()).containsExactly("Обновить", "Ещё");
        assertThat(form.moreItems()).containsExactly("Колонки…", "Группировка", "Итоги…");
        assertThat(form.getElement().getTextRecursively()).doesNotContain("Поступление");
        assertThat(form.panel().getComponentAt(0).isVisible()).isFalse();
    }

    @Test
    void objectCommandsStandAtTheStartOfTheToolbar() {
        var form = new ProjectionListForm(environment(), spec(null), "k", null);

        form.addCommand(new Button("Создать"));

        assertThat(form.getComponentAt(1).getChildren().findFirst().orElseThrow().getChildren().findFirst().orElseThrow())
                .isInstanceOf(Button.class);
    }

    @Test
    void selectedRowFollowsTheGridAndIsClearedWhenTheResultIsReplaced() {
        var form = new ProjectionListForm(environment(), spec(null), "k", null);
        List<Optional<?>> seen = new ArrayList<>();
        form.addSelectionListener(seen::add);

        form.panel().run();
        assertThat(form.selectedRow()).isEmpty();

        var grid = form.panel().grid().orElseThrow();
        grid.getGrid().select(ROWS.get(1));
        assertThat(form.selectedRow()).isPresent();
        assertThat(form.selectedRow().orElseThrow().toString()).contains("2");

        form.panel().run();
        assertThat(form.selectedRow()).as("новый результат — выбора нет").isEmpty();
        assertThat(seen).isNotEmpty();
    }

    @Test
    void viewsAreCreatedFromTheCurrentGridAndLoadedBack() {
        var views = new MemoryViews();
        var form = new ProjectionListForm(environment(), spec(null), "k", QueryListOptions.DEFAULT.withViews(views));
        var controller = new QueryViewsController(form.panel(), views, form.formKey());
        form.panel().run();
        form.panel().applyTotals(Map.of("name", FooterAggregateKind.COUNT));

        var created = controller.create("  Мой вид  ", false);

        assertThat(created.name()).isEqualTo("Мой вид");
        assertThat(views.views("querylist.k")).hasSize(1);
        assertThat(QueryGridState.fromViewState(created.state()).totals()).containsEntry("name", FooterAggregateKind.COUNT);

        form.panel().applyTotals(Map.of());
        controller.load(created.id());
        assertThat(form.panel().captureState().totals()).containsEntry("name", FooterAggregateKind.COUNT);

        controller.loadStandard();
        assertThat(form.panel().captureState().totals()).isEmpty();
        assertThatThrownBy(() -> controller.create(" ", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editTakesTheCurrentGridStateAndDeleteRemovesTheView() {
        var views = new MemoryViews();
        var form = new ProjectionListForm(environment(), spec(null), "k", QueryListOptions.DEFAULT.withViews(views));
        var controller = new QueryViewsController(form.panel(), views, form.formKey());
        form.panel().run();
        var created = controller.create("Вид", false);
        form.panel().applyTotals(Map.of("name", FooterAggregateKind.COUNT));

        var edited = controller.edit(created.id(), "Общий вид", true);

        assertThat(edited.shared()).isTrue();
        assertThat(QueryGridState.fromViewState(edited.state()).totals()).containsKey("name");
        var copy = controller.copy(edited.id());
        assertThat(copy.name()).isEqualTo("Общий вид (копия)");
        assertThat(copy.shared()).as("копия личная").isFalse();
        controller.delete(copy.id());
        assertThat(controller.rows()).extracting(QueryListViews.View::id).containsExactly(edited.id());
    }

    @Test
    void foreignViewCannotBeChangedOrDeleted() {
        var views = new MemoryViews() {
            @Override public List<View> views(String formKey) {
                return List.of(new View("x", "Чужой", false, false, QueryGridState.EMPTY.toViewState()));
            }
        };
        var form = new ProjectionListForm(environment(), spec(null), "k", QueryListOptions.DEFAULT.withViews(views));
        var controller = new QueryViewsController(form.panel(), views, form.formKey());

        assertThatThrownBy(() -> controller.edit("x", "Новое", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> controller.delete("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> controller.load("missing")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultViewIsAppliedOnOpeningAndTheStarTogglesIt() {
        var views = new MemoryViews();
        var preparer = new ProjectionListForm(environment(), spec(null), "k", QueryListOptions.DEFAULT.withViews(views));
        var controller = new QueryViewsController(preparer.panel(), views, preparer.formKey());
        preparer.panel().run();
        preparer.panel().applyTotals(Map.of("name", FooterAggregateKind.COUNT));
        var saved = controller.create("По умолчанию", false);

        controller.toggleDefault(saved.id());
        assertThat(controller.defaultViewId()).contains(saved.id());

        var reopened = new ProjectionListForm(environment(), spec(null), "k", QueryListOptions.DEFAULT.withViews(views));
        reopened.panel().run();
        assertThat(reopened.panel().captureState().totals()).containsKey("name");

        controller.toggleDefault(saved.id());
        assertThat(controller.defaultViewId()).isEmpty();
        var standard = new ProjectionListForm(environment(), spec(null), "k", QueryListOptions.DEFAULT.withViews(views));
        standard.panel().run();
        assertThat(standard.panel().captureState().totals()).isEmpty();
    }

    @Test
    void aMissingDefaultViewFallsBackToTheStandardOne() {
        var views = new MemoryViews();
        views.defaults.put("querylist.k", "gone");

        var form = new ProjectionListForm(environment(), spec(null), "k", QueryListOptions.DEFAULT.withViews(views));
        form.panel().run();

        assertThat(form.panel().phase()).isEqualTo(org.ipro.query.ui.grid.QueryGridPanel.Phase.READY);
    }
}
