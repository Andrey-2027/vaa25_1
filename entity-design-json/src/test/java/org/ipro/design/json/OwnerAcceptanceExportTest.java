package org.ipro.design.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.design.*;
import org.ipro.design.generation.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JSON -> validator -> current public builder input for the independent E3.C harness. */
class OwnerAcceptanceExportTest {
    static final String TARGET="com.example.generated.targets.ReferenceTarget";
    @Test void ownerAndBothDocumentEnvelopesExportActualPlansAndInvalidOwnerStaysInput() throws Exception {
        var names=ProjectDesignContext.Names.known(Set.of());
        var facts=new RuntimeDesignSnapshot.TypeFacts(TARGET,"CATALOG","DERIVED","fixture","STANDARD_ROOT","DERIVED","fixture","DERIVED","fixture",Set.of(),Set.of(),false,false,"",
            List.of("code","name"),List.of("code","name"),List.of(),List.of(),List.of(),"",List.of());
        var available=new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE,"fixture");
        var snapshot=new RuntimeDesignSnapshot("e3-acceptance",true,Map.of(TARGET,facts),Map.of("STANDARD_CATALOG_APP_API",available,"STANDARD_DOCUMENT_APP_API",available,"ET0_ET7_RUNTIME",available));
        var target=new ReferenceTargetDescriptor(TARGET,"Контрагенты","STANDARD_ROOT","java.lang.Long",ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Eligibility.SUPPORTED,"existing support JAR");
        var context=new ProjectDesignContext("e3-acceptance","1","com.example.generated",names,names,names,ProjectDesignContext.Names.known(Set.of("FILE")),Map.of(TARGET,target),true,snapshot);
        var owner=new OwnerDraft(TARGET,"counterparty",DraftText.auto(),"Контрагент");
        var property=new EntityProperties("OwnedCatalog",DraftText.auto(),"Договоры",EntityProperties.Titles.auto(),DraftText.auto(),null,"FILE");
        var description=new FieldDraft(new UUID(4,1),"description","Описание","STRING",DraftText.auto(),false,FieldDraft.UiRequired.AUTO,new ConstraintDraft("200","",""),null,FieldApplications.root());
        var owned=new EntityDraft(2,RecipeCatalog.STANDARD_CATALOG_V2,new UUID(4,0),property,List.of(description),List.of(),owner);
        var docProperty=new EntityProperties("OwnerEnvelopeDocument",DraftText.auto(),"Документ envelope E3",EntityProperties.Titles.auto(),DraftText.auto(),null,"FILE");
        var doc1=new EntityDraft(1,RecipeCatalog.STANDARD_DOCUMENT,new UUID(4,2),docProperty,List.of(),List.of());
        var doc2=new EntityDraft(2,doc1.recipe(),doc1.draftId(),docProperty,List.of(),List.of(),null);
        var codec=new DraftJsonCodec();var mapper=new ObjectMapper().writerWithDefaultPrettyPrinter();
        var cases=new LinkedHashMap<String,EntityDraft>();cases.put("owned-catalog",owned);cases.put("document-v1",doc1);cases.put("document-v2",doc2);
        var plans=new HashMap<String,GenerationPlan>();
        for(var entry:cases.entrySet()) {
            var draft=codec.decode(codec.encode(entry.getValue()));assertEquals(entry.getValue(),draft);
            var validation=new DraftValidator().validate(draft,context);assertFalse(validation.hasErrors(),validation.problems().toString());
            var plan=new EntityJavaGenerator().generate(validation.checkedStructure().orElseThrow(),draft,context).plan().orElseThrow();
            assertTrue(plan.generatorVersion().endsWith("-v4"));assertEquals(1,plan.files().size());plans.put(entry.getKey(),plan);
            var output=System.getProperty("design.owner.acceptance.output");
            if(output!=null) {
                var root=Path.of(output).resolve(entry.getKey());Files.createDirectories(root);
                Files.write(root.resolve("current-draft.json"),codec.encode(draft));
                Files.writeString(root.resolve("current-context.json"),mapper.writeValueAsString(context));
                Files.writeString(root.resolve("reviewed-plan.json"),mapper.writeValueAsString(plan));
                Files.writeString(root.resolve("manifest.json"),mapper.writeValueAsString(GenerationManifest.from(plan,GenerationManifest.EXTENSION_FINGERPRINT)));
                var file=plan.files().getFirst();var path=root.resolve(file.relativePath());Files.createDirectories(path.getParent());Files.writeString(path,file.content());
            }
        }
        assertEquals(plans.get("document-v1").files(),plans.get("document-v2").files());
        assertNotEquals(plans.get("document-v1").planId(),plans.get("document-v2").planId());
        var invalid=codec.decode(codec.encode(doc2.withOwner(owner)));var validation=new DraftValidator().validate(invalid,context);
        assertTrue(validation.checkedStructure().isEmpty());
        assertTrue(validation.problems().stream().anyMatch(p->p.code()==DraftProblem.Code.OWNER_UNSUPPORTED_RECIPE&&p.target().propertyKey().equals("owner")&&p.target().fieldId()==null));
        var output=System.getProperty("design.owner.acceptance.output");
        if(output!=null) {
            Files.write(Path.of(output).resolve("invalid-document-owner.json"),codec.encode(invalid));
            Files.writeString(Path.of(output).resolve("invalid-document-diagnostics.json"),mapper.writeValueAsString(validation.problems()));
        }
    }
}
