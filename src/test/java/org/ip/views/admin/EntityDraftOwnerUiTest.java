package org.ip.views.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import org.ipro.design.*;
import org.ipro.design.json.DraftJsonCodec;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

class EntityDraftOwnerUiTest {
    UI ui;
    static final String TARGET=EntityDraftReferenceTest.TARGET;
    static ProjectDesignContext context() {
        var c=EntityDraftReferenceTest.context(true); var s=c.runtimeSnapshot();
        var type=new RuntimeDesignSnapshot.TypeFacts(TARGET,"CATALOG","DERIVED","fixture","STANDARD_ROOT","DERIVED","fixture",
            "DERIVED","fixture",Set.of(),Set.of(),false,false,"",List.of(),List.of(),List.of(),List.of(),List.of(),"",List.of());
        return c.withRuntimeSnapshot(new RuntimeDesignSnapshot("owner-ui",true,Map.of(TARGET,type),s.prerequisites()));
    }
    @BeforeEach void before(){principal("admin",true);ui=uiWithResources();UI.setCurrent(ui);}
    @AfterEach void after(){UI.setCurrent(null);org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    Checkbox enabled(EntityDraftEditorView v){return (Checkbox)all(v).filter(c->"owner".equals(c.getElement().getAttribute("data-draft-input"))).findFirst().orElseThrow();}
    @SuppressWarnings("unchecked") ComboBox<String> picker(EntityDraftEditorView v){return (ComboBox<String>)all(v).filter(c->"owner.targetTypeId".equals(c.getElement().getAttribute("data-draft-input"))).findFirst().orElseThrow();}
    EntityDraftEditorView create(){var v=view();v.setContextSource(EntityDraftOwnerUiTest::context);ui.add(v);text(v,"className").setValue("OwnedUiProbe");text(v,"label").setValue("Договоры");return v;}
    @Test void ownerPanelMakesExplicitV2DraftAndRealPreviewWithoutSubjectFieldUuid() {
        var v=create();var id=v.session().draft().draftId();enabled(v).setValue(true);picker(v).setValue(TARGET);
        text(v,"owner.fieldName").setValue("counterparty");text(v,"owner.label").setValue("Контрагент");
        var d=v.session().draft();assertThat(d.schemaVersion()).isEqualTo(2);assertThat(d.draftId()).isEqualTo(id);assertThat(d.fields()).isEmpty();
        assertThat(d.owner()).isEqualTo(new OwnerDraft(TARGET,"counterparty",DraftText.auto(),"Контрагент"));
        assertThat(new DraftJsonCodec().decode(new DraftJsonCodec().encode(d))).isEqualTo(d);assertThat(v.isDirty()).isTrue();
        button(v,"Показать Java").click();assertThat(v.javaExport()).isNotNull();
        assertThat(v.javaExport().plan().files().getFirst().content()).contains("@CatalogOwner(property = \"counterparty\")","order = 30");
    }
    @Test void unavailableFactsInvalidatePreviewAndKeepOwnerInputAndDiagnosticAddress() {
        var facts=new AtomicReference<>(context());var v=view();v.setContextSource(facts::get);ui.add(v);
        text(v,"className").setValue("OwnedProbe");text(v,"label").setValue("Договоры");enabled(v).setValue(true);picker(v).setValue(TARGET);
        button(v,"Показать Java").click();assertThat(v.javaExport()).isNotNull();var before=v.session().draft();
        facts.set(EntityDraftReferenceTest.context(false));v.activate();button(v,"Показать Java").click();assertThat(v.javaExport()).isNull();
        assertThat(v.session().draft()).isSameAs(before);assertThat(picker(v).getValue()).isEqualTo(TARGET);
        assertThat(v.session().validate().problems()).anyMatch(p->p.target().propertyKey().equals("owner.targetTypeId")&&p.target().fieldId()==null);
        assertThat(all(v).filter(c->c instanceof com.vaadin.flow.component.html.Span).map(c->((com.vaadin.flow.component.html.Span)c).getText()).toList()).anyMatch(t->t.contains("Цель недоступна"));
    }
    @Test void ownerRemovalPreservesV2AndLateInputsCannotChangeNewOwner() {
        var v=create();enabled(v).setValue(true);var old=picker(v);old.setValue(TARGET);enabled(v).setValue(false);
        assertThat(v.session().draft().owner()).isNull();assertThat(v.session().draft().schemaVersion()).isEqualTo(2);
        enabled(v).setValue(true);old.setValue(null);old.setValue(TARGET);
        assertThat(v.session().draft().owner().targetTypeId()).isEmpty();
    }
    @Test void invalidDocumentOwnerCanBeRemovedWithoutLosingOtherInput() {
        var v=view();v.setContextSource(EntityDraftOwnerUiTest::context);ui.add(v);var old=v.session().draft();
        var d=new EntityDraft(2,RecipeCatalog.STANDARD_DOCUMENT,old.draftId(),old.entity(),old.fields(),old.sections(),new OwnerDraft(TARGET,"counterparty",DraftText.auto(),"Контрагент"));
        v.acceptImport(d,v.documentEpoch(),v.session().revision());
        assertThat(v.session().validate().problems()).anyMatch(p->p.code()==DraftProblem.Code.OWNER_UNSUPPORTED_RECIPE);
        enabled(v).setValue(false);assertThat(v.session().draft().owner()).isNull();assertThat(v.session().draft().entity()).isEqualTo(d.entity());
    }
    @Test void ownerControlsStayLiveAfterSubjectInspectorAndPermissionsAreRechecked() {
        var v=create();enabled(v).setValue(true);picker(v).setValue(TARGET);
        button(v,"+ Реквизит").click();button(v,"Основные").click();
        var label=text(v,"owner.label");label.setValue("Другая подпись");
        assertThat(v.session().draft().owner().label()).isEqualTo("Другая подпись");
        principal("admin",false);label.setValue("Поздний ввод");assertThat(v.session()).isNull();
    }
    @Test void legacyRecipeIsNotSilentlyUpgradedIntoAnUnexportableOwnerDraft() {
        var v=view();v.setContextSource(EntityDraftOwnerUiTest::context);ui.add(v);
        var old=new DraftOperations().create(RecipeCatalog.STANDARD_CATALOG);
        v.acceptImport(old,v.documentEpoch(),v.session().revision());
        assertThat(all(v).filter(c->"owner".equals(c.getElement().getAttribute("data-draft-input"))).toList()).isEmpty();
        assertThatThrownBy(()->v.session().editOwner(new OwnerDraft(TARGET,"counterparty",DraftText.auto(),"Контрагент"))).isInstanceOf(IllegalStateException.class);
        assertThat(new DraftJsonCodec().decode(new DraftJsonCodec().encode(v.session().draft()))).isEqualTo(old);
    }
}
