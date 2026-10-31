package org.ip.views.admin;

import org.ipro.design.*;
import org.junit.jupiter.api.*;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.select.Select;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

class EntityDraftSectionsTest {
    @BeforeEach void login() { principal("admin",true); }
    @AfterEach void logout() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void sessionHasScopedFieldsStableIdsCollapseRestoreAndSectionDeletion() {
        var s = new DraftEditorSession(DraftEditorSessionTest.context("v1")); DraftEditorSessionTest.basics(s);
        s.addField("STRING"); UUID root=s.place().fieldId(); s.editField(root,f->f.withName("note"));
        s.addSection(); UUID first=s.place().sectionId();
        s.editSection(first,old->new SectionDraft(old.id(),"Примечания","NoteRow",old.packageName(),old.tableName(),old.parentFieldName(),old.parentColumnName(),old.fields()));
        s.addField("STRING"); UUID child=s.place().fieldId(); s.editField(child,f->new FieldDraft(f.id(),"note","Текст",f.type(),f.columnName(),false,f.uiRequired(),new ConstraintDraft("12x","",""),null,f.applications()));
        assertThat(s.selectedField().applications()).isEqualTo(FieldApplications.row());
        long revision=s.revision(); s.toggleSection(first); assertThat(s.place().fieldId()).isNull(); assertThat(s.place().sectionId()).isEqualTo(first);
        s.toggleSection(first); assertThat(s.place().fieldId()).isEqualTo(child); assertThat(s.revision()).isEqualTo(revision);
        s.addSection(); UUID second=s.place().sectionId(); s.moveSelected(-1);
        assertThat(s.draft().sections()).extracting(SectionDraft::id).containsExactly(second,first);
        s.selectField(first,child); assertThat(s.selectedField().constraints().length()).isEqualTo("12x");
        assertThat(s.draft().fields().getFirst().id()).isEqualTo(root);
        assertThat(s.validate().problems()).noneMatch(p->p.code()==DraftProblem.Code.DUPLICATE_NAME);
        s.selectSection(first); s.removeSelected(); assertThat(s.draft().sections()).extracting(SectionDraft::id).containsExactly(second);
        s.editField(child,f->f.withName("late")); assertThat(s.draft().fields().getFirst().name()).isEqualTo("note");
    }
    @Test void sectionInspectorStoresRawInputAndOwnedApplicationsWithoutChangingRootFields() {
        var v=view(); button(v,"+ Табчасть").click(); UUID section=v.session().place().sectionId();
        text(v,"section.label").setValue("Позиции"); text(v,"rowClassName").setValue("MaterialRow");
        assertThat(text(v,"section.tableName").getValue()).isEqualTo("material_row");
        text(v,"parentFieldName").setValue("materialCard"); assertThat(text(v,"parentColumnName").getValue()).isEqualTo("material_card_id");
        button(v,"+ Реквизит").click(); text(v,"name").setValue("note"); text(v,"constraints.length").setValue("12x");
        assertThat(v.session().draft().fields()).isEmpty(); assertThat(v.session().section(section).fields()).hasSize(1);
        assertThat(v.session().selectedField().applications()).isEqualTo(FieldApplications.row());
        var header=all(v).filter(c->c instanceof NativeButton && section.toString().equals(c.getElement().getAttribute("data-section-id"))).findFirst().orElseThrow();
        ComponentUtil.fireEvent(header,new ClickEvent<>(header));
        assertThat(text(v,"rowClassName").getValue()).isEqualTo("MaterialRow");
        assertThat(v.session().section(section).fields().getFirst().constraints().length()).isEqualTo("12x");
    }
    @Test void referencePickerUsesOnlySupportedTargetsAndKeepsLostSelectionOnRefresh() {
        var base=DraftEditorSessionTest.context("v1"); String target="org.example.Existing";
        var descriptor=new ReferenceTargetDescriptor(target,"Существующий","STANDARD_ROOT","java.lang.Long",ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Eligibility.SUPPORTED,"");
        var context=new ProjectDesignContext(base.projectId(),"v1",base.defaultPackage(),base.existingTypes(),base.existingTables(),base.subsystemIds(),base.iconIds(),Map.of(target,descriptor),true);
        AtomicReference<DraftProjectCatalog> catalog=new AtomicReference<>(new DraftProjectCatalog(context,Map.of()));
        var v=new EntityDraftEditorView(new EntityDraftAccess(true,new EntityExplorerAccess())); v.setCatalogSource(catalog::get); v.initialize(()->{});
        button(v,"+ Реквизит").click();
        @SuppressWarnings("unchecked") var type=(Select<String>) all(v).filter(c->c instanceof Select<?> && "type".equals(c.getElement().getAttribute("data-draft-input"))).findFirst().orElseThrow();
        type.setValue("REFERENCE");
        @SuppressWarnings("unchecked") var picker=(ComboBox<String>) all(v).filter(c->c instanceof ComboBox<?> && c.getParent().map(p->p.getParent().map(r->"reference.typeId".equals(r.getElement().getAttribute("data-property"))).orElse(false)).orElse(false)).findFirst().orElseThrow();
        picker.setValue(target); UUID id=v.session().selectedField().id(); long revision=v.session().revision();
        assertThat(v.session().selectedField().reference().typeId()).isEqualTo(target);
        catalog.set(new DraftProjectCatalog(base,Map.of())); v.activate();
        assertThat(v.session().selectedField().id()).isEqualTo(id); assertThat(v.session().revision()).isEqualTo(revision);
        assertThat(picker.getValue()).isEqualTo(target); assertThat(v.session().validate().problems()).anyMatch(p->p.code()==DraftProblem.Code.REFERENCE_UNAVAILABLE);
    }
}
