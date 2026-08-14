package org.ip.views.reportstudio;

import com.vaadin.flow.component.dialog.Dialog;
import org.ip.form.SelectionFormAssembler;
import org.ip.service.LookupService;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
