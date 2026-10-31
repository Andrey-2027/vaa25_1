package org.ipro.unidr.ui;
import org.ip.unidr.query.UniDrReferenceService;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import org.ipro.unidr.model.*;
import org.ipro.unidr.query.*;
import org.ipro.unidr.run.UniDrRunService;
import org.ipro.unidr.source.*;
import org.ipro.report.data.QueryField;
import org.ipro.query.document.ReportQuerySource;
import org.junit.jupiter.api.*;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real decision window and editor state, without a database or browser. */
class UniDrSourceChangeUiTest {
    private UI ui;
    @BeforeEach void setup(){ui=new UI();UI.setCurrent(ui);}
    @AfterEach void cleanup(){UI.setCurrent(null);}
    private UniDrSourcePlan plan(UniDrQuerySource query) {
        String id=query.jpql().contains("amount")?"amount":"qty";
        var schema=UniDrQuerySourceService.metadata(List.of(QueryField.scalar("name",String.class),QueryField.scalar(id,Integer.class)));
        return new UniDrSourcePlan(query,schema,Map.of("name",new UniDrSourcePlan.Projection("name","r.name",String.class,null,"name",true),
            id,new UniDrSourcePlan.Projection(id,"r."+id,Integer.class,null,id,true)),List.of());
    }
    private UniDrView view() {
        var catalog=new UniDrSourceCatalog(List.of(),query->plan(query).schema());catalog.usePlans(this::plan);
        var support=mock(UniDrQueryUiSupport.class);when(support.catalog()).thenReturn(catalog);
        var view=new UniDrView(mock(UniDrRunService.class),mock(UniDrReferenceService.class),Clock.systemUTC(),support);
        view.applyQuery(new UniDrQuerySource(ReportQuerySource.MANUAL,"select r.name as name,r.qty as qty from Example r",null,List.of()));
        return view;
    }
    private UniDrSourceReconciler.Analysis analysis(UniDrDefinition original) {
        var next=new UniDrQuerySource(ReportQuerySource.MANUAL,"select r.name as name,r.amount as amount from Example r",null,List.of());
        return UniDrSourceReconciler.analyze(original,plan(original.query()),plan(next),original.parameterDeclarations());
    }
    @Test void cancelKeepsDraftSelectionAndRunValues() {
        var view=view();var editor=UniDrEditorTest.all(view,UniDrDefinitionEditor.class).getFirst();editor.selectField("qty");
        var values=(UniDrRunDialog.Values)org.springframework.test.util.ReflectionTestUtils.getField(view,"runValues");values.named.put("unchanged","X");
        var previous=view.definition();var dialog=view.openSourceChanges(previous,analysis(previous));
        UniDrEditorTest.footerButton(dialog,"Отмена").click();
        assertThat(view.definition()).isEqualTo(previous);assertThat(editor.selectedField()).isEqualTo("qty");assertThat(values.named).containsExactlyEntriesOf(Map.of("unchanged","X"));
    }
    @Test void lossesNeedConfirmationAndApplyLeavesNoMissingFieldReferences() {
        var view=view();var previous=view.definition();var dialog=view.openSourceChanges(previous,analysis(previous));
        var apply=UniDrEditorTest.footerButton(dialog,"Применить источник");assertThat(apply.isEnabled()).isFalse();
        UniDrEditorTest.all(dialog,Checkbox.class).getFirst().setValue(true);assertThat(apply.isEnabled()).isTrue();apply.click();
        assertThat(dialog.isOpened()).isFalse();assertThat(view.definition().query().jpql()).contains("amount");
        assertThat(view.definition().columns()).extracting(UniDrDefinition.Column::fieldId).containsExactly("name");
    }
    @Test void staleAnalysisCannotOverwriteAnEditedDraft() {
        var view=view();var previous=view.definition();var dialog=view.openSourceChanges(previous,analysis(previous));
        UniDrEditorTest.all(dialog,Checkbox.class).getFirst().setValue(true);
        UniDrEditorTest.all(view,UniDrDefinitionEditor.class).getFirst().title.setValue("Изменённый черновик");
        UniDrEditorTest.footerButton(dialog,"Применить источник").click();
        assertThat(dialog.isOpened()).isTrue();assertThat(view.definition().title()).isEqualTo("Изменённый черновик");assertThat(view.definition().query()).isEqualTo(previous.query());
    }
}
