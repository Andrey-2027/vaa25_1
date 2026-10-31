package org.ipro.design;
import java.util.*;
import java.nio.file.*;
import org.ipro.design.generation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceGenerationTest {
    static final String TARGET="com.example.generated.targets.ReferenceTarget";
    static ReferenceTargetDescriptor target(String id) {return new ReferenceTargetDescriptor(id,"Existing target","STANDARD_ROOT","java.lang.Long",ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Eligibility.SUPPORTED,"confirmed");}
    static ProjectDesignContext context() {
        var n=ProjectDesignContext.Names.known(Set.of());
        return new ProjectDesignContext("e2-fixture","v1","com.example.generated",n,n,n,ProjectDesignContext.Names.known(Set.of("FILE")),Map.of(TARGET,target(TARGET)),true,
            new RuntimeDesignSnapshot("e2",true,Map.of(),Map.of("STANDARD_CATALOG_APP_API",new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE,"fixture"),"STANDARD_DOCUMENT_APP_API",new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE,"fixture"),"ET0_ET7_RUNTIME",new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE,"fixture"))));
    }
    static EntityDraft draft(RecipeRef recipe) {
        var name=recipe.equals(RecipeCatalog.STANDARD_DOCUMENT)?"ReferenceDocument":"ReferenceCatalog";
        var fields=List.of(new FieldDraft(new UUID(2,1),"optionalTarget","Необязательная ссылка","REFERENCE",DraftText.auto(),false,FieldDraft.UiRequired.OPTIONAL,ConstraintDraft.empty(),new ReferenceDraft(TARGET),FieldApplications.root()),
            new FieldDraft(new UUID(2,2),"requiredTarget","Обязательная ссылка","REFERENCE",DraftText.auto(),true,FieldDraft.UiRequired.REQUIRED,ConstraintDraft.empty(),new ReferenceDraft(TARGET),FieldApplications.root()));
        return new EntityDraft(1,recipe,new UUID(2,0),new EntityProperties(name,DraftText.auto(),"Ссылки",EntityProperties.Titles.auto(),DraftText.auto(),null,"FILE"),fields,List.of());
    }
    GenerationResult generate(EntityDraft draft,ProjectDesignContext ctx) {
        var valid=new DraftValidator().validate(draft,ctx);assertFalse(valid.hasErrors(),valid.problems().toString());
        return new EntityJavaGenerator().generate(valid.checkedStructure().orElseThrow(),draft,ctx);
    }
    @Test void bothProfilesGenerateLazyAssociationsAndCompletePlans() throws Exception {
        for(var recipe:List.of(RecipeCatalog.STANDARD_CATALOG_V2,RecipeCatalog.STANDARD_DOCUMENT)) {
            var draft=draft(recipe);var plan=generate(draft,context()).plan().orElseThrow();var java=plan.files().getFirst().content();
            assertTrue(plan.generatorVersion().endsWith("-v4"));assertTrue(java.contains("@ManyToOne(fetch = FetchType.LAZY, optional = true)"));
            assertTrue(java.contains("@JoinColumn(name = \"required_target_id\", nullable = false)"));
            assertTrue(java.contains("private "+TARGET+" requiredTarget;"));assertFalse(java.contains("cascade"));assertFalse(java.contains("lookup ="));
            assertEquals(plan,generate(draft,context()).plan().orElseThrow());
            var output=System.getProperty("design.reference.output");if(output!=null) {
                var root=Path.of(output).resolve(recipe.equals(RecipeCatalog.STANDARD_DOCUMENT)?"reference-document":"reference-catalog");Files.createDirectories(root);
                var writer=Fixtures.JSON.writerWithDefaultPrettyPrinter();
                Files.writeString(root.resolve("reviewed-plan.json"),writer.writeValueAsString(plan));Files.writeString(root.resolve("current-draft.json"),writer.writeValueAsString(draft));Files.writeString(root.resolve("current-context.json"),writer.writeValueAsString(context()));
                Files.writeString(root.resolve("manifest.json"),writer.writeValueAsString(GenerationManifest.from(plan,"plan-fingerprint-v2")));
                var file=root.resolve(plan.files().getFirst().relativePath());Files.createDirectories(file.getParent());Files.writeString(file,java);
            }
        }
    }
    @Test void targetChangesInvalidateCheckedAndPlanAndRequiredModeIsRejected() {
        var draft=draft(RecipeCatalog.STANDARD_DOCUMENT);var ctx=context();var checked=new DraftValidator().validate(draft,ctx).checkedStructure().orElseThrow();
        var changed=new ProjectDesignContext(ctx.projectId(),ctx.version(),ctx.defaultPackage(),ctx.existingTypes(),ctx.existingTables(),ctx.subsystemIds(),ctx.iconIds(),Map.of(),true,ctx.runtimeSnapshot());
        assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE,new EntityJavaGenerator().generate(checked,draft,changed).diagnostics().getFirst().code());
        assertTrue(new DraftValidator().validate(draft,changed).hasErrors());
        var f=draft.fields().getLast();f=new FieldDraft(f.id(),f.name(),f.label(),f.type(),f.columnName(),true,FieldDraft.UiRequired.OPTIONAL,f.constraints(),f.reference(),f.applications());
        assertEquals(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE,generate(draft.withFields(List.of(f)),ctx).diagnostics().getFirst().code());
    }
    @Test void identicalSimpleNamesUseUnambiguousQualifiedSourceTypes() {
        var draft=draft(RecipeCatalog.STANDARD_DOCUMENT);var old=draft.fields().getFirst();
        var other=new FieldDraft(old.id(),old.name(),old.label(),old.type(),old.columnName(),old.serverRequired(),old.uiRequired(),old.constraints(),new ReferenceDraft("other.ReferenceTarget"),old.applications());
        draft=draft.withFields(List.of(other,draft.fields().getLast()));var c=context();
        c=new ProjectDesignContext(c.projectId(),c.version(),c.defaultPackage(),c.existingTypes(),c.existingTables(),c.subsystemIds(),c.iconIds(),Map.of(TARGET,target(TARGET),"other.ReferenceTarget",target("other.ReferenceTarget")),true,c.runtimeSnapshot());
        var java=generate(draft,c).plan().orElseThrow().files().getFirst().content();assertTrue(java.contains("private other.ReferenceTarget optionalTarget;"));assertTrue(java.contains("private "+TARGET+" requiredTarget;"));
    }
    @Test void unknownWrongIdentityAndRowsCannotBecomeReferenceTargets() {
        for(var descriptor:List.of(
                new ReferenceTargetDescriptor(TARGET,"Unknown","STANDARD_ROOT","java.lang.Long",ReferenceTargetDescriptor.Fact.UNKNOWN,ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Eligibility.SUPPORTED,"unknown presentation"),
                new ReferenceTargetDescriptor(TARGET,"String ID","STANDARD_ROOT","java.lang.String",ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Eligibility.SUPPORTED,"wrong identity"),
                new ReferenceTargetDescriptor(TARGET,"Row","OWNED_ROW","java.lang.Long",ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Eligibility.SUPPORTED,"owned row"))) {
            var c=context();c=new ProjectDesignContext(c.projectId(),c.version(),c.defaultPackage(),c.existingTypes(),c.existingTables(),c.subsystemIds(),c.iconIds(),Map.of(TARGET,descriptor),true,c.runtimeSnapshot());
            assertTrue(new DraftValidator().validate(draft(RecipeCatalog.STANDARD_DOCUMENT),c).hasErrors());
        }
    }
}
