package org.ipro.report.ui.print;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import org.ip.unidr.model.UniDrDefinitions;
import org.ipro.unidr.model.UniDrDefinition;
import org.ipro.unidr.model.UniDrDefinitionJsonCodec;
import org.ipro.unidr.model.UniDrDefinitionValidator;
import org.ipro.unidr.run.UniDrAccess;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ipro.unidr.store.UniDrFileStore;
import org.ipro.report.ui.UniDrStoreGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.catalog.ReportCatalogItem;
import org.ipro.report.catalog.ReportEngineType;
import org.ipro.query.ReportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Печать по типу сущности не зависит от UDR: UniDR-отчёт попадает в тот же список, если
 * привязан к типу реестра, и запускается с контекстом выделенной строки.
 */
class ReportPrintDialogUniDrTest {

    @TempDir
    Path directory;

    private final UniDrDefinitionJsonCodec codec = new UniDrDefinitionJsonCodec(
        new UniDrDefinitionValidator(UniDrDefinitions.catalog()));
    private final UniDrAccess access = new UniDrAccess(() -> true, () -> "alice");
    private final UniDrDefinition base = UniDrDefinitions.receivingItemsSummary();

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void boundReportJoinsThePrintListAndRunsWithTheRowContext() {
        UniDrFileStore store = new UniDrFileStore(directory, codec, access);
        UniDrFileStore.Saved bound = store.create(base.withTitle("Накладная")
            .withTargetEntityClass(Printable.class.getName()), "");
        store.create(base.withTitle("Без привязки"), "");

        UniDrRunLauncher launcher = mock(UniDrRunLauncher.class);
        AtomicReference<Dialog> presented = new AtomicReference<>();
        ReportPrintDialog dialog = dialog(store, launcher, presented);

        ReportContext context = ReportContext.of(Printable.class, 7L, List.of(7L),
            Printable.class.getName() + "-list", "alice", Instant.now());
        dialog.open(context);

        Grid<ReportCatalogItem> grid = grid(presented.get());
        assertThat(grid.getListDataView().getItems().toList())
            .extracting(ReportCatalogItem::type, ReportCatalogItem::name)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(ReportEngineType.UNIDR, "Накладная"));

        grid.asSingleSelect().setValue(grid.getListDataView().getItems().findFirst().orElseThrow());
        button(presented.get(), "Открыть параметры и запустить").click();

        verify(launcher).open(any(UniDrDefinition.class), same(context));
        assertThat(store.load(bound.id()).definition().targetEntityClass())
            .isEqualTo(Printable.class.getName());
    }

    @Test
    void reportWithoutBindingIsNotOfferedForPrinting() {
        UniDrFileStore store = new UniDrFileStore(directory, codec, access);
        store.create(base.withTitle("Только каталог"), "");

        AtomicReference<Dialog> presented = new AtomicReference<>();
        ReportPrintDialog dialog = dialog(store, mock(UniDrRunLauncher.class), presented);
        dialog.open(ReportContext.of(Printable.class, 1L, List.of(1L), "list", "alice", Instant.now()));

        assertThat(grid(presented.get()).getListDataView().getItems().toList()).isEmpty();
    }

    private ReportPrintDialog dialog(UniDrFileStore store, UniDrRunLauncher launcher,
                                     AtomicReference<Dialog> presented) {
        JrxmlTemplateService jr = mock(JrxmlTemplateService.class);
        when(jr.findPrintableItemsForEntity(any())).thenReturn(List.of());
        return new ReportPrintDialog(jr, null, new UniDrStoreGateway(store), launcher, presented::set);
    }

    private static Grid<ReportCatalogItem> grid(Dialog dialog) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Grid<ReportCatalogItem> grid = (Grid) descendants(dialog, Grid.class).stream()
            .findFirst().orElseThrow(() -> new AssertionError("Грид печатных форм не найден"));
        return grid;
    }

    private static Button button(Dialog dialog, String caption) {
        return descendants(dialog, Button.class).stream()
            .filter(button -> caption.equals(button.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Кнопка не найдена: " + caption));
    }

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) {
            result.add(type.cast(root));
        }
        root.getChildren().forEach(child -> result.addAll(descendants(child, type)));
        return result;
    }

    /** Тип реестра, для которого привязан отчёт. */
    static final class Printable {
    }
}
