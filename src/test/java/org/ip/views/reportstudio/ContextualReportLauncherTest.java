package org.ipro.report.ui.print;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import org.ipro.query.ReportContext;
import org.ipro.unidr.model.UniDrDefinitionJsonCodec;
import org.ipro.unidr.model.UniDrDefinitionValidator;
import org.ip.unidr.model.UniDrDefinitions;
import org.ipro.unidr.run.UniDrAccess;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ipro.unidr.store.UniDrFileStore;
import org.ipro.report.ui.UniDrStoreGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Кнопка карточки открывает общий диалог печати; R3 оставил в нём только UniDR и JR.
 */
class ContextualReportLauncherTest {

    @TempDir
    Path directory;

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    /** Карточка и список ведут в один диалог: привязанный UniDR виден и здесь. */
    @Test
    void cardOffersUniDrReportsBoundToTheFormEntityType() {
        var store = new UniDrFileStore(directory,
            new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(UniDrDefinitions.catalog())),
            new UniDrAccess(() -> true, () -> "alice"));
        store.create(UniDrDefinitions.receivingItemsSummary().withTitle("Накладная")
            .withTargetEntityClass(Printable.class.getName()), "");
        store.create(UniDrDefinitions.receivingItemsSummary().withTitle("Без привязки"), "");

        ReportContext context = ReportContext.of(Printable.class, 5L, List.of(5L),
            "form", "tester", Instant.now());
        TestLauncher launcher = new TestLauncher(() -> context, store,
            mock(UniDrRunLauncher.class));

        launcher.click();

        @SuppressWarnings({"unchecked", "rawtypes"})
        Grid<org.ipro.report.catalog.ReportCatalogItem> templates =
            (Grid) componentOf(launcher.openedDialog, Grid.class);
        assertThat(templates.getListDataView().getItems().toList())
            .extracting(org.ipro.report.catalog.ReportCatalogItem::type,
                org.ipro.report.catalog.ReportCatalogItem::name)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(
                org.ipro.report.catalog.ReportEngineType.UNIDR, "Накладная"));
    }

    @Test
    void runAndEditButtonsEnableOnlyWhenRowIsSelected() {
        var store = new UniDrFileStore(directory,
            new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(UniDrDefinitions.catalog())),
            new UniDrAccess(() -> true, () -> "alice"));
        store.create(UniDrDefinitions.receivingItemsSummary().withTitle("Накладная")
            .withTargetEntityClass(Printable.class.getName()), "");
        ReportContext context = ReportContext.of(Printable.class, 5L, List.of(5L),
            "form", "tester", Instant.now());
        TestLauncher launcher = new TestLauncher(() -> context, store,
            mock(UniDrRunLauncher.class));

        launcher.click();

        Button edit = button(launcher.openedDialog, "Редактировать");
        Button run = button(launcher.openedDialog, "Открыть параметры и запустить");
        assertNotNull(edit);
        assertNotNull(run);
        assertFalse(edit.isEnabled());
        assertFalse(run.isEnabled());

        @SuppressWarnings({"unchecked", "rawtypes"})
        Grid<org.ipro.report.catalog.ReportCatalogItem> templates =
            (Grid) componentOf(launcher.openedDialog, Grid.class);
        templates.asSingleSelect().setValue(new org.ipro.report.catalog.ReportCatalogItem(
            11L, org.ipro.report.catalog.ReportEngineType.UNIDR,
            "Накладная", null, true, false));

        assertTrue(edit.isEnabled());
        assertTrue(run.isEnabled());

        templates.asSingleSelect().setValue(null);

        assertFalse(edit.isEnabled());
        assertFalse(run.isEnabled());
    }

    /** UDR-пункта «Создать» больше нет: вход снят вместе с редактором. */
    @Test
    void createMenuHasNoUdrEntry() {
        ReportContext context = ReportContext.of(Printable.class, 5L, List.of(5L),
            "form", "tester", Instant.now());
        TestLauncher launcher = new TestLauncher(() -> context, null, null);

        launcher.click();

        assertThat(descendants(launcher.openedDialog, com.vaadin.flow.component.menubar.MenuBar.class))
            .flatExtracting(bar -> bar.getItems())
            .flatExtracting(item -> item.getSubMenu().getItems())
            .extracting(com.vaadin.flow.component.contextmenu.MenuItem::getText)
            .noneMatch(text -> text != null && text.startsWith("UDR"));
    }

    private static Button button(Dialog dialog, String caption) {
        return descendants(dialog, Button.class).stream()
                .filter(b -> caption.equals(b.getText()))
                .findFirst()
                .orElse(null);
    }

    private static <T extends Component> T componentOf(Dialog dialog, Class<T> type) {
        return descendants(dialog, type).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Компонент не найден: " + type.getSimpleName()));
    }

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) {
            result.add(type.cast(root));
        }
        root.getChildren().forEach(child -> result.addAll(descendants(child, type)));
        return result;
    }

    /** Тип реестра, к которому привязан отчёт. */
    static final class Printable {
    }

    private static final class TestLauncher extends ContextualReportLauncher {

        private Dialog openedDialog;

        private TestLauncher(Supplier<ReportContext> contextSupplier,
                UniDrFileStore store, UniDrRunLauncher runs) {
            super(contextSupplier, null, null,
                store == null ? null : new UniDrStoreGateway(store), runs);
        }

        @Override
        protected void openDialog(Dialog dialog) {
            openedDialog = dialog;
        }
    }
}
