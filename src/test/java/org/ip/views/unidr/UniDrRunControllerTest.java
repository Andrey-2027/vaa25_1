package org.ip.views.unidr;

import com.vaadin.flow.component.UI;
import org.ip.unidr.model.UniDrDefinitions;
import org.ip.unidr.run.*;
import org.ipro.reportstudio.render.ReportExportFormat;
import org.junit.jupiter.api.Test;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrRunControllerTest {
    final UniDrRunService runs=mock(UniDrRunService.class);
    final UniDrRunService.Session session=mock(UniDrRunService.Session.class);
    final UniDrRunService.Ticket ticket=mock(UniDrRunService.Ticket.class);
    final CompletableFuture<UniDrRunResult> future=new CompletableFuture<>();
    final UniDrRunRequest request=new UniDrRunRequest(UniDrDefinitions.receivingItemsSummary(),Map.of(),ZoneOffset.UTC);
    UniDrRunController controller(){when(runs.newSession()).thenReturn(session);when(session.start(any())).thenReturn(ticket);when(ticket.completion()).thenReturn(future);return new UniDrRunController(runs);}
    @Test void automaticRunBeforeDialogAttachmentStillPollsTheCurrentUi() {
        var ui=new UI();ui.setPollInterval(1700);UI.setCurrent(ui);
        try {
            controller();
            var query=new org.ip.unidr.model.UniDrQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL,"select n.name as name from Nomenclature n",null,List.of());
            var schema=org.ip.unidr.query.UniDrQuerySourceService.metadata(List.of(org.ipro.reportstudio.data.QueryField.scalar("name",String.class)));
            var catalog=new org.ip.unidr.source.UniDrSourceCatalog(List.of(),source->schema);
            var definition=new org.ip.unidr.model.UniDrDefinition(org.ip.unidr.model.UniDrDefinition.SCHEMA_VERSION,"query",1,"Авто",
                List.of(new org.ip.unidr.model.UniDrDefinition.Column("name",null)),List.of(),List.of(),List.of(),List.of(),List.of(),org.ip.unidr.model.UniDrDefinition.Mode.DETAILS,true).withQuery(query);
            var queries=mock(UniDrQueryUiSupport.class);when(queries.catalog()).thenReturn(catalog);
            when(queries.canonicalForm(any(),any())).thenReturn(new org.ip.views.components.ReportParamForm(List.of(),null,null,null));
            when(queries.canonicalValues(any(),any())).thenReturn(Map.of());
            var dialog=new UniDrRunDialogFactory(runs,mock(org.ip.unidr.query.UniDrReferenceService.class),queries,java.time.Clock.systemUTC()).open(definition);
            verify(session).start(any());assertThat(ui.getPollInterval()).isEqualTo(250);
            dialog.close();assertThat(ui.getPollInterval()).isEqualTo(1700);
        } finally {UI.setCurrent(null);}
    }
    @Test void lateCompletionAfterCloseDoesNotPublishAndRestoresPolling() {
        var ui=new UI();ui.setPollInterval(1700);var controller=controller();var published=new ArrayList<UniDrRunResult>();
        controller.start(request,ui,published::add,error->{throw new AssertionError(error);});assertThat(ui.getPollInterval()).isEqualTo(250);
        controller.close();future.complete(mock(UniDrRunResult.class));controller.poll();
        assertThat(published).isEmpty();assertThat(ui.getPollInterval()).isEqualTo(1700);verify(session).close();
    }
    @Test void changingValuesStopsPublicationBeforeTheWorkerFinishes() {
        var controller=controller();var published=new ArrayList<UniDrRunResult>();
        controller.start(request,null,published::add,error->{throw new AssertionError(error);});controller.invalidate();
        future.complete(mock(UniDrRunResult.class));controller.poll();assertThat(published).isEmpty();
    }
    @Test void failureReturnsToInputsAndReleasesPolling() {
        var ui=new UI();var controller=controller();var errors=new ArrayList<Throwable>();
        controller.start(request,ui,result->{throw new AssertionError();},errors::add);
        future.completeExceptionally(new IllegalArgumentException("Ошибка"));controller.poll();
        assertThat(errors).hasSize(1);assertThat(ui.getPollInterval()).isEqualTo(-1);
    }
    @Test void simultaneousDialogsSharePollingUntilBothComplete() {
        var ui=new UI();ui.setPollInterval(900);var first=controller();var second=controller();
        first.start(request,ui,result->{},error->{});second.start(request,ui,result->{},error->{});
        first.close();assertThat(ui.getPollInterval()).isEqualTo(250);
        second.close();assertThat(ui.getPollInterval()).isEqualTo(900);
    }
    @Test void ownershipSurvivesSuccessfulHandoffUntilResultIsClosed() {
        var controller=controller();var result=mock(UniDrRunResult.class);
        when(result.report()).thenReturn(mock(org.ip.unidr.render.UniDrRenderedReport.class));
        var handler=controller.downloads(result);verify(session,never()).close();
        controller.close();assertThat(handler.response(ReportExportFormat.PDF).getError()).isEqualTo(410);
    }
}
