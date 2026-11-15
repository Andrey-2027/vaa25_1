package org.ipro.query.ui.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.EntityLookup;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryAssemblyService;
import org.ipro.query.list.QueryListDefinition;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.ui.editor.QueryEditorAnalysisService;
import org.ipro.query.ui.editor.QueryMetadataCatalogService;
import org.ipro.query.ui.editor.ReportQueryEditor;
import org.ipro.query.ui.fixture.EmptyEntitySelection;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.grid.QueryRunHistory;
import org.ipro.query.ui.grid.QueryTaskRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryListEditorTest {
    private static final QueryDefinition QUERY = new QueryDefinition(
            "select p.code as code from Part p", null, null, null, List.of());
    private static final QueryListRegistry.Check OK =
            new QueryListRegistry.Check(null, List.of("org.example.Part"), List.of("code"), List.of("Код"));

    private final QueryListRegistry registry = mock(QueryListRegistry.class);
    private final AtomicReference<QueryListRegistry.Entry> savedEntry = new AtomicReference<>();
    private final UI ui = new UI();

    @BeforeEach
    void setUp() {
        UI.setCurrent(ui);
        when(registry.check(any())).thenReturn(OK);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    private QueryListAdminEnvironment environment() {
        var grid = new QueryGridEnvironment((spec, context, values) -> {
            throw new IllegalStateException("Сетка в этих проверках не запускается");
        }, (type, id) -> "#" + id, null, QueryTaskRunner.direct(), new QueryRunHistory(), () -> "admin");
        return new QueryListAdminEnvironment(registry, grid, QueryListEditorTest::queryEditor, null,
                name -> name.substring(name.lastIndexOf('.') + 1), key -> "/query-list/" + key, 30_000);
    }

    private static ReportQueryEditor queryEditor() {
        return new ReportQueryEditor(mock(QueryEditorAnalysisService.class), mock(QueryMetadataCatalogService.class),
                mock(ReportPreviewService.class), mock(EntityLookup.class), new EmptyEntitySelection(),
                mock(ReportQueryAssemblyService.class));
    }

    private QueryListEditor newList() {
        return new QueryListEditor(environment(), null, QueryListEditor.Draft.empty(), () -> { }, savedEntry::set);
    }

    private static QueryListRegistry.Entry stored(long version, String modifiedBy) {
        var definition = new QueryListDefinition("stock.parts", "Остатки", QUERY, null, ProjectionExecutionMode.LAZY,
                null, Set.of(), QueryProvenance.REGISTERED);
        return new QueryListRegistry.Entry("stock.parts", "Остатки", definition, true, version, modifiedBy,
                Instant.parse("2026-11-14T13:20:00Z"), null, List.of("org.example.Part"));
    }

    private QueryListEditor existing(QueryListRegistry.Entry entry) {
        return new QueryListEditor(environment(), entry, QueryListEditor.Draft.of(entry), () -> { }, savedEntry::set);
    }

    private static void fill(QueryListEditor editor, String title, String key) {
        ((TextField) ReflectionTestUtils.getField(editor, "title")).setValue(title);
        ((TextField) ReflectionTestUtils.getField(editor, "key")).setValue(key);
    }

    @Test
    void savesANewListAfterTheCheckAndFixesItsKey() {
        when(registry.administrativeEntries()).thenReturn(List.of());
        var created = stored(0, "admin");
        when(registry.create(any(), anyBoolean())).thenReturn(created);
        var editor = newList();
        fill(editor, "Остатки", "stock.parts");
        assertThat(editor.isDirty()).isTrue();

        editor.save();

        var definition = ArgumentCaptor.forClass(QueryListDefinition.class);
        verify(registry).create(definition.capture(), eq(true));
        assertThat(definition.getValue().key()).isEqualTo("stock.parts");
        assertThat(definition.getValue().title()).isEqualTo("Остатки");
        assertThat(definition.getValue().provenance()).isEqualTo(QueryProvenance.REGISTERED);
        assertThat(savedEntry.get()).isSameAs(created);
        assertThat(editor.isDirty()).isFalse();
        assertThat(((TextField) ReflectionTestUtils.getField(editor, "key")).isReadOnly()).isTrue();
        assertThat(editor.noteText()).isEmpty();
    }

    @Test
    void aBlankKeyNeverReachesTheRegistry() {
        var editor = newList();
        fill(editor, "Остатки", "");

        editor.save();

        verify(registry, never()).create(any(), anyBoolean());
        assertThat(editor.noteText()).contains("Не сохранено").contains("Ключ");
    }

    @Test
    void aListThatFailsTheCheckIsNotSavedAndTheReasonIsShown() {
        when(registry.check(any())).thenReturn(
                new QueryListRegistry.Check("Поле «Склад» удалено", List.of(), List.of(), List.of()));
        var editor = newList();
        fill(editor, "Остатки", "stock.parts");

        editor.save();

        verify(registry, never()).create(any(), anyBoolean());
        assertThat(editor.noteText()).contains("не прошло проверку").contains("Склад");
    }

    @Test
    void aKeyDeclaredInCodeIsReportedBeforeAnythingIsWritten() {
        var declared = new QueryListRegistry.Entry("stock.parts", "Из кода", null, true, null, null, null, null,
                List.of());
        when(registry.administrativeEntries()).thenReturn(List.of(declared));
        var editor = newList();
        fill(editor, "Остатки", "stock.parts");

        editor.save();

        verify(registry, never()).create(any(), anyBoolean());
        assertThat(editor.noteText()).contains("Ключ уже занят").contains("объявлен в коде");
    }

    @Test
    void aVersionConflictNamesWhoChangedTheListAndWritesNothing() {
        var mine = stored(3, "admin");
        when(registry.update(any(), anyLong(), anyBoolean())).thenThrow(new OptimisticLockingFailureException("v"));
        when(registry.administrativeEntries()).thenReturn(List.of(stored(4, "ivanova")));
        var editor = existing(mine);
        ((TextField) ReflectionTestUtils.getField(editor, "title")).setValue("Остатки по ячейкам");

        editor.save();

        assertThat(editor.noteText()).contains("изменил другой администратор").contains("ivanova")
                .contains("Перечитать");
        assertThat(savedEntry.get()).isNull();
        assertThat(editor.isDirty()).isTrue();
    }

    @Test
    void editsMakeTheListDirtyAndRestoringThemDoesNot() {
        var editor = existing(stored(1, "admin"));
        assertThat(editor.isDirty()).isFalse();
        var title = (TextField) ReflectionTestUtils.getField(editor, "title");
        title.setValue("Другое название");
        assertThat(editor.isDirty()).isTrue();
        title.setValue("Остатки");
        assertThat(editor.isDirty()).isFalse();
        ((Checkbox) ReflectionTestUtils.getField(editor, "enabled")).setValue(false);
        assertThat(editor.isDirty()).isTrue();
    }

    @Test
    void firstNRowsGoIntoTheLimitsAndTheHybridMode() {
        when(registry.administrativeEntries()).thenReturn(List.of());
        when(registry.create(any(), anyBoolean())).thenReturn(stored(0, "admin"));
        var editor = newList();
        fill(editor, "Остатки", "stock.parts");
        @SuppressWarnings("unchecked")
        var choice = (RadioButtonGroup<Object>) ReflectionTestUtils.getField(editor, "choice");
        choice.setValue(choice.getListDataView().getItems().toList().get(1));
        ((IntegerField) ReflectionTestUtils.getField(editor, "rows")).setValue(1234);

        editor.save();

        var definition = ArgumentCaptor.forClass(QueryListDefinition.class);
        verify(registry).create(definition.capture(), eq(true));
        assertThat(definition.getValue().mode()).isEqualTo(ProjectionExecutionMode.HYBRID);
        assertThat(definition.getValue().query().limits().maxRows()).isEqualTo(1234);
    }

    @Test
    void leavingTheFirstNRowsModeRestoresTheStandardLimitAndKeepsTheTimeout() {
        var limited = new QueryDefinition("select p.code as code from Part p", null, null, null, List.of(),
                new org.ipro.query.model.QueryLimits(200, 12_000));
        var definition = new QueryListDefinition("stock.parts", "Остатки", limited, null,
                ProjectionExecutionMode.HYBRID, null, Set.of(), QueryProvenance.REGISTERED);
        var entry = new QueryListRegistry.Entry("stock.parts", "Остатки", definition, true, 3L, "admin",
                Instant.parse("2026-11-14T13:20:00Z"), null, List.of("org.example.Part"));
        when(registry.update(any(), anyLong(), anyBoolean())).thenReturn(entry);
        var editor = existing(entry);
        @SuppressWarnings("unchecked")
        var choice = (RadioButtonGroup<Object>) ReflectionTestUtils.getField(editor, "choice");
        choice.setValue(choice.getListDataView().getItems().toList().getFirst());

        editor.save();

        var saved = ArgumentCaptor.forClass(QueryListDefinition.class);
        verify(registry).update(saved.capture(), eq(3L), eq(true));
        assertThat(saved.getValue().mode()).isEqualTo(ProjectionExecutionMode.LAZY);
        assertThat(saved.getValue().query().limits().maxRows())
                .isEqualTo(org.ipro.query.model.QueryLimits.DEFAULT_MAX_ROWS);
        assertThat(saved.getValue().query().limits().timeoutMs()).isEqualTo(12_000);
    }

    @Test
    void aListDeclaredInCodeIsOnlyViewedAndNeverWritten() {
        var declared = new QueryListRegistry.Entry("stock.parts", "Остатки", stored(1, "x").definition(), true, null,
                null, null, null, List.of("org.example.Part"));
        var editor = existing(declared);
        ((TextField) ReflectionTestUtils.getField(editor, "title")).setReadOnly(false);
        ((TextField) ReflectionTestUtils.getField(editor, "title")).setValue("Правка");

        editor.save();

        verify(registry, never()).create(any(), anyBoolean());
        verify(registry, never()).update(any(), anyLong(), anyBoolean());
        assertThat(editor.isDirty()).isFalse();
        assertThat(editor.noteText()).contains("Список объявлен в коде");
    }

    @Test
    void copyStartsANewListWithAnotherKeyAndTitle() {
        var source = stored(5, "admin");
        var draft = QueryListEditor.Draft.copyOf(source);
        assertThat(draft.key()).isEqualTo("stock.parts-copy");
        assertThat(draft.title()).isEqualTo("Остатки (копия)");
        assertThat(draft.enabled()).isTrue();
        var editor = new QueryListEditor(environment(), null, draft, () -> { }, savedEntry::set);
        assertThat(((TextField) ReflectionTestUtils.getField(editor, "key")).isReadOnly()).isFalse();
        assertThat(editor.isDirty()).isFalse();
    }

    @Test
    void leavingAnUnchangedListNeedsNoQuestion() {
        var editor = existing(stored(1, "admin"));
        boolean[] left = {false};
        editor.requestLeave(() -> left[0] = true);
        assertThat(left[0]).isTrue();
    }
}
