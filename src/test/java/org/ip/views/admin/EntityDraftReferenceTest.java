package org.ip.views.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import org.ipro.design.*;
import org.ipro.design.json.DraftJsonCodec;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

class EntityDraftReferenceTest {
    private UI ui;
    static final String TARGET="org.ip.model.UnitOfMeasurement";
    static ProjectDesignContext context(boolean available) {
        var base=DraftEditorSessionTest.context("reference-fixture");
        var targets=available?Map.of(TARGET,new ReferenceTargetDescriptor(TARGET,"Единицы","STANDARD_ROOT","java.lang.Long",ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Eligibility.SUPPORTED,"confirmed")):Map.<String,ReferenceTargetDescriptor>of();
        return new ProjectDesignContext(base.projectId(),base.version(),base.defaultPackage(),base.existingTypes(),base.existingTables(),base.subsystemIds(),base.iconIds(),targets,true,base.runtimeSnapshot());
    }
    @BeforeEach void login() {principal("admin",true);ui=uiWithResources();UI.setCurrent(ui);}
    @AfterEach void clear() {UI.setCurrent(null);org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void bothKindsImportAndPreviewRealReferencesWithCurrentProfiles() {
        for(var recipe:List.of(RecipeCatalog.STANDARD_CATALOG_V2,RecipeCatalog.STANDARD_DOCUMENT)) {
            var v=view();v.setContextSource(()->context(true));ui.add(v);
            var fields=List.of(new FieldDraft(UUID.randomUUID(),"unit","Единица","REFERENCE",DraftText.auto(),true,FieldDraft.UiRequired.REQUIRED,ConstraintDraft.empty(),new ReferenceDraft(TARGET),FieldApplications.root()));
            var draft=new EntityDraft(1,recipe,UUID.randomUUID(),new EntityProperties("ReferenceProbe",DraftText.auto(),"Ссылки",EntityProperties.Titles.auto(),DraftText.auto(),null,"FILE"),fields,List.of());
            var codec=new DraftJsonCodec();v.acceptImport(codec.decode(codec.encode(draft)),v.documentEpoch(),v.session().revision());
            button(v,"Показать Java").click();assertThat(v.javaExport()).isNotNull();
            var plan=v.javaExport().plan();assertThat(plan.generatorVersion()).isEqualTo(recipe.equals(RecipeCatalog.STANDARD_DOCUMENT)?"document-java-v4":"catalog-java-v4");
            assertThat(plan.files().getFirst().content()).contains("@ManyToOne(fetch = FetchType.LAZY, optional = false)","private "+TARGET+" unit;");
            assertThat(v.isDirty()).isFalse();assertThat(v.session().draft()).isEqualTo(draft);
        }
    }
    @Test void losingTargetFactsInvalidatesPreviewAndNavigatesToFieldWithoutLosingInput() {
        var facts=new AtomicReference<>(context(true));var v=view();v.setContextSource(facts::get);ui.add(v);
        text(v,"className").setValue("ReferenceProbe");text(v,"label").setValue("Ссылки");
        v.session().addField("REFERENCE");var id=v.session().place().fieldId();
        v.session().editField(id,f->new FieldDraft(f.id(),"unit","Единица",f.type(),f.columnName(),false,f.uiRequired(),f.constraints(),new ReferenceDraft(TARGET),f.applications()));
        button(v,"Показать Java").click();assertThat(v.javaExport()).isNotNull();var before=v.session().draft();
        facts.set(context(false));v.activate();button(v,"Показать Java").click();assertThat(v.javaExport()).isNull();
        assertThat(v.session().draft()).isSameAs(before);assertThat(v.session().draft().fields().getFirst().reference().typeId()).isEqualTo(TARGET);
        var link=all(v).filter(c->c instanceof Button b&&b.getText().contains("Тип ссылки недоступен")).map(c->(Button)c).findFirst().orElseThrow();
        link.click();assertThat(v.session().place().fieldId()).isEqualTo(id);
    }
}
