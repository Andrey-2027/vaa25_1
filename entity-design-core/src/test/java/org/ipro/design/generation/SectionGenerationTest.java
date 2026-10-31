package org.ipro.design.generation;

import org.ipro.design.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SectionGenerationTest {
    static FieldDraft field(String name,String type,boolean required,FieldApplications applications) {
        return new FieldDraft(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),name,name,type,DraftText.auto(),required,
            required?FieldDraft.UiRequired.REQUIRED:FieldDraft.UiRequired.AUTO,RecipeCatalog.initialConstraints(type),
            type.equals("REFERENCE")?new ReferenceDraft(OwnerGenerationTest.TARGET):null,applications);
    }
    static SectionDraft section(int index) {
        return new SectionDraft(new UUID(5,index),"Строки "+index,"GeneratedRow"+index,DraftText.explicit("com.example.generated.rows"),DraftText.auto(),
            DraftText.explicit("document"),DraftText.auto(),List.of(field("note","STRING",true,FieldApplications.row()),field("target","REFERENCE",false,FieldApplications.row())));
    }
    static EntityDraft draft(RecipeRef recipe,int count,boolean owner) {
        var base=OwnerGenerationTest.draft();var sections=new ArrayList<SectionDraft>();for(int i=1;i<=count;i++){
            int slot=i;var s=section(i);var fields=s.fields().stream().map(f->new FieldDraft(new UUID(slot,Math.abs(f.id().hashCode())),f.name(),f.label(),f.type(),f.columnName(),f.serverRequired(),f.uiRequired(),f.constraints(),f.reference(),f.applications())).toList();
            sections.add(new SectionDraft(s.id(),s.label(),s.rowClassName(),s.packageName(),s.tableName(),s.parentFieldName(),s.parentColumnName(),fields));
        }
        return new EntityDraft(owner?2:1,recipe,base.draftId(),base.entity(),List.of(),sections,owner?base.owner():null);
    }
    static ProjectDesignContext context(){return OwnerGenerationTest.context("CATALOG",null);}
    static GenerationPlan plan(EntityDraft d){return OwnerGenerationTest.plan(d,context());}
    @Test void bothKindsProduceOrderedDeterministicZeroOneTwoSectionSets() {
        for(var recipe:List.of(RecipeCatalog.STANDARD_CATALOG_V2,RecipeCatalog.STANDARD_DOCUMENT))for(int n=0;n<=2;n++){
            var d=draft(recipe,n,false);var p=plan(d);assertEquals(n+1,p.files().size());assertTrue(p.generatorVersion().endsWith("-v4"));assertEquals(p,plan(d));
            assertEquals("com/example/generated/OwnedCatalog.java",p.files().getFirst().relativePath());
            if(n==0)assertFalse(p.files().getFirst().content().contains("@TableSections"));
            else assertTrue(p.files().getFirst().content().contains("@TableSections({com.example.generated.rows.GeneratedRow1.class"));
            for(int i=1;i<=n;i++){
                var source=p.files().get(i).content();assertEquals("com/example/generated/rows/GeneratedRow"+i+".java",p.files().get(i).relativePath());
                assertTrue(source.contains("@Table(name = \"generated_row"+i+"\")"));assertTrue(source.contains("order = "+10*i+", lineNumberField = \"lineNumber\""));
                assertTrue(source.contains("parentEntity = com.example.generated.OwnedCatalog.class"));assertTrue(source.contains("private com.example.generated.OwnedCatalog document;"));
                assertTrue(source.contains("@JoinColumn(name = \"document_id\", nullable = false)"));assertTrue(source.contains("private Integer lineNumber;"));
                assertTrue(source.contains("line_number"));assertTrue(source.contains("SectionPersistenceMode.MUTABLE_REPLACE_ALL"));assertTrue(source.contains("SectionRlsPolicy.INHERIT_ROOT"));
                assertTrue(source.contains("order = 10, required = RequiredMode.REQUIRED, grid = @GridColumn(visible = true, order = 10)"));
                assertFalse(source.contains("@CatalogOwner"));assertFalse(source.contains("cascade"));assertFalse(p.files().getFirst().content().contains("OneToMany"));
            }
            GenerationManifest.from(p,GenerationManifest.EXTENSION_FINGERPRINT).verify(p,GenerationManifest.EXTENSION_FINGERPRINT);
        }
    }
    @Test void rootOwnerRowParentAndOrdinaryReferenceRemainSeparate() {
        var p=plan(draft(RecipeCatalog.STANDARD_CATALOG_V2,2,true));
        assertTrue(p.files().getFirst().content().contains("@CatalogOwner(property = \"counterparty\")"));
        var row=p.files().get(1).content();assertFalse(row.contains("CatalogOwner"));assertTrue(row.contains("parentField = \"document\""));
        assertTrue(row.contains("private "+OwnerGenerationTest.TARGET+" target;"));
    }
    @Test void rowConstraintsAndIndependentFormTableApplicationsUseSharedMapping() {
        var d=draft(RecipeCatalog.STANDARD_DOCUMENT,1,false);var s=d.sections().getFirst();var fields=new ArrayList<FieldDraft>();
        for(var type:List.of("STRING","INTEGER","LONG","DECIMAL","BOOLEAN","DATE","DATETIME","REFERENCE"))fields.add(field("value"+type,type,false,FieldApplications.row()));
        fields.add(field("gridOnly","STRING",false,new FieldApplications(false,false,false,true)));
        fields.add(field("formOnly","STRING",false,new FieldApplications(true,false,false,false)));
        s=new SectionDraft(s.id(),s.label(),s.rowClassName(),s.packageName(),s.tableName(),s.parentFieldName(),s.parentColumnName(),fields);
        var source=plan(d.withSections(List.of(s))).files().get(1).content();
        assertTrue(source.contains("java.math.BigDecimal"));assertTrue(source.contains("java.time.LocalDateTime"));assertTrue(source.contains("@Digits("));
        assertTrue(source.contains("label = \"gridOnly\", order = 90, hidden = true, grid = @GridColumn(visible = true, order = 90)"));
        assertTrue(source.contains("label = \"formOnly\", order = 100, grid = @GridColumn(visible = false)"));
    }
    @Test void requiredOptionalMismatchIsAddressedToRowField() {
        var d=draft(RecipeCatalog.STANDARD_DOCUMENT,1,false);var s=d.sections().getFirst();var f=s.fields().getFirst();
        f=new FieldDraft(f.id(),f.name(),f.label(),f.type(),f.columnName(),true,FieldDraft.UiRequired.OPTIONAL,f.constraints(),f.reference(),f.applications());
        s=new SectionDraft(s.id(),s.label(),s.rowClassName(),s.packageName(),s.tableName(),s.parentFieldName(),s.parentColumnName(),List.of(f));d=d.withSections(List.of(s));
        var c=context();var checked=OwnerGenerationTest.checked(d,c);var result=new EntityJavaGenerator().generate(checked,d,c);
        assertTrue(result.plan().isEmpty());assertEquals(s.id(),result.diagnostics().getFirst().target().sectionId());assertEquals(f.id(),result.diagnostics().getFirst().target().fieldId());
    }
    @Test void technicalParentAndLineNumberAndCaseInsensitivePathsCannotCollide() {
        var d=draft(RecipeCatalog.STANDARD_DOCUMENT,1,false);var s=d.sections().getFirst();
        for(var name:List.of("lineNumber","LineNumber","document")){
            var f=field(name,"STRING",false,FieldApplications.row());var changed=new SectionDraft(s.id(),s.label(),s.rowClassName(),s.packageName(),s.tableName(),s.parentFieldName(),s.parentColumnName(),List.of(f));
            var result=new DraftValidator().validate(d.withSections(List.of(changed)),context());assertTrue(result.hasErrors());
            assertTrue(result.problems().stream().anyMatch(p->p.code()==DraftProblem.Code.RESERVED_FIELD&&p.target().sectionId().equals(s.id())));
        }
        var badParent=new SectionDraft(s.id(),s.label(),s.rowClassName(),s.packageName(),s.tableName(),DraftText.explicit("lineNumber"),s.parentColumnName(),List.of());
        assertTrue(new DraftValidator().validate(d.withSections(List.of(badParent)),context()).hasErrors());
        var collision=new SectionDraft(s.id(),s.label(),"ownedcatalog",DraftText.explicit("com.example.generated"),s.tableName(),s.parentFieldName(),s.parentColumnName(),List.of());
        assertTrue(new DraftValidator().validate(d.withSections(List.of(collision)),context()).problems().stream().anyMatch(p->p.code()==DraftProblem.Code.DUPLICATE_TYPE_NAME));
    }
    @Test void rowChangesAndOrderInvalidateWholePlanWhileFixedLegacyStillRefusesSections() {
        var d=draft(RecipeCatalog.STANDARD_CATALOG_V2,2,false);var p=plan(d);var reversed=new ArrayList<>(d.sections());Collections.reverse(reversed);
        var changed=d.withSections(reversed);assertFalse(p.appliesTo(changed,context()));assertNotEquals(p.planId(),plan(changed).planId());
        var legacy=new CatalogJavaGenerator().generate(OwnerGenerationTest.checked(d,context()),d,context());assertTrue(legacy.plan().isEmpty());
    }
}
