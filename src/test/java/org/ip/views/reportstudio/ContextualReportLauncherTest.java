package org.ip.views.reportstudio;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextualReportLauncherTest {

    @Mock
    private ReportTemplateService templateService;
    @Mock
    private ReportExecutionService executionService;
    @Mock
    private LookupService lookupService;
    @Mock
    private SelectionFormAssembler selectionFormAssembler;

    @Test
    void clickLoadsTemplatesAndBuildsSelectionDialog() {
        when(templateService.search("")).thenReturn(List.of());
        ReportContext context = ReportContext.of(null, null, List.of(), "journal-form", "tester", Instant.now());
        TestLauncher launcher = new TestLauncher(
                () -> context,
                templateService,
                executionService,
                lookupService,
                selectionFormAssembler);

        launcher.click();

        verify(templateService).search("");
        assertNotNull(launcher.openedDialog);
    }

    @Test
    void runAndEditButtonsEnableOnlyWhenTemplateRowIsSelected() {
        ReportTemplate template = new ReportTemplate();
        template.setId(11L);
        template.setName("Остатки");
        when(templateService.search("")).thenReturn(List.of(template));
        ReportContext context = ReportContext.of(null, null, List.of(), "journal-form", "tester", Instant.now());
        TestLauncher launcher = new TestLauncher(
                () -> context,
                templateService,
                executionService,
                lookupService,
                selectionFormAssembler);

        launcher.click();

        Button edit = button(launcher.openedDialog, "Редактировать");
        Button run = button(launcher.openedDialog, "Открыть параметры и запустить");
        assertNotNull(edit);
        assertNotNull(run);
        assertFalse(edit.isEnabled());
        assertFalse(run.isEnabled());

        Grid<ReportTemplate> templates = componentOf(launcher.openedDialog, Grid.class);
        templates.asSingleSelect().setValue(template);

        assertTrue(edit.isEnabled());
        assertTrue(run.isEnabled());

        templates.asSingleSelect().setValue(null);

        assertFalse(edit.isEnabled());
        assertFalse(run.isEnabled());
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

    private static final class TestLauncher extends ContextualReportLauncher {

        private Dialog openedDialog;

        private TestLauncher(
                Supplier<ReportContext> contextSupplier,
                ReportTemplateService templateService,
                ReportExecutionService executionService,
                LookupService lookupService,
                SelectionFormAssembler selectionFormAssembler) {
            super(contextSupplier, templateService, executionService, lookupService, selectionFormAssembler);
        }

        @Override
        protected void openDialog(Dialog dialog) {
            openedDialog = dialog;
        }
    }
}
