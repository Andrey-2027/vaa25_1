package org.ipro.design.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.design.*;
import org.ipro.design.generation.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SectionAcceptanceExportTest {
    static final String TARGET="com.example.generated.targets.ReferenceTarget";
    @Test void actualJsonBuilderExportsBothKindsZeroOneTwoAndCombinedOwnerReference() throws Exception {
        var names=ProjectDesignContext.Names.known(Set.of());var available=new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE,"fixture");
        var facts=new RuntimeDesignSnapshot.TypeFacts(TARGET,"CATALOG","DERIVED","fixture","STANDARD_ROOT","DERIVED","fixture","DERIVED","fixture",Set.of(),Set.of(),false,false,"",List.of("code","name"),List.of("code","name"),List.of(),List.of(),List.of(),"",List.of());
        var snapshot=new RuntimeDesignSnapshot("e4-fixture",true,Map.of(TARGET,facts),Map.of("STANDARD_CATALOG_APP_API",available,"STANDARD_DOCUMENT_APP_API",available,"ET0_ET7_RUNTIME",available));
        var descriptor=new ReferenceTargetDescriptor(TARGET,"Контрагенты","STANDARD_ROOT","java.lang.Long",ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Eligibility.SUPPORTED,"existing target JAR");
        var context=new ProjectDesignContext("e4","1","com.example.generated",names,names,names,ProjectDesignContext.Names.known(Set.of("FILE")),Map.of(TARGET,descriptor),true,snapshot);
        var codec=new DraftJsonCodec();var writer=new ObjectMapper().writerWithDefaultPrettyPrinter();
        for(int c=0;c<7;c++){
            boolean owned=c==6;boolean document=c>=3&&c<6;int count=owned?2:c%3;
            String id=owned?"owned-catalog-2":(document?"document-":"catalog-")+count;
            String className=owned?"OwnedCatalogSections2":(document?"DocumentSections":"CatalogSections")+count;
            String table=id.replace('-','_');var sections=new ArrayList<SectionDraft>();
            for(int i=1;i<=count;i++){
                var note=new FieldDraft(new UUID(6+c,10*i+1),"note","Текст","STRING",DraftText.auto(),true,FieldDraft.UiRequired.REQUIRED,new ConstraintDraft("60","",""),null,FieldApplications.row());
                var target=new FieldDraft(new UUID(6+c,10*i+2),"target","Ссылка","REFERENCE",DraftText.auto(),false,FieldDraft.UiRequired.OPTIONAL,ConstraintDraft.empty(),new ReferenceDraft(TARGET),FieldApplications.row());
                sections.add(new SectionDraft(new UUID(6+c,i),"Строки "+i,className+"Row"+i,DraftText.explicit("com.example.generated.rows"),DraftText.explicit(table+"_row_"+i),DraftText.explicit(document?"document":"catalog"),DraftText.auto(),List.of(note,target)));
            }
            var property=new EntityProperties(className,DraftText.auto(),"Комплект",EntityProperties.Titles.auto(),DraftText.explicit(table),null,"FILE");
            var draft=new EntityDraft(owned?2:1,document?RecipeCatalog.STANDARD_DOCUMENT:RecipeCatalog.STANDARD_CATALOG_V2,new UUID(6+c,0),property,List.of(),sections,owned?new OwnerDraft(TARGET,"counterparty",DraftText.auto(),"Контрагент"):null);
            var restored=codec.decode(codec.encode(draft));assertEquals(draft,restored);
            var validation=new DraftValidator().validate(restored,context);assertFalse(validation.hasErrors(),validation.problems().toString());
            var plan=new EntityJavaGenerator().generate(validation.checkedStructure().orElseThrow(),restored,context).plan().orElseThrow();
            assertEquals(count+1,plan.files().size());assertTrue(plan.generatorVersion().endsWith("-v4"));
            var output=System.getProperty("design.sections.output");if(output!=null){
                var root=Path.of(output).resolve(id);Files.createDirectories(root);
                Files.write(root.resolve("current-draft.json"),codec.encode(restored));Files.writeString(root.resolve("current-context.json"),writer.writeValueAsString(context));
                Files.writeString(root.resolve("reviewed-plan.json"),writer.writeValueAsString(plan));Files.writeString(root.resolve("manifest.json"),writer.writeValueAsString(GenerationManifest.from(plan,GenerationManifest.EXTENSION_FINGERPRINT)));
                for(var file:plan.files()){var path=root.resolve(file.relativePath());Files.createDirectories(path.getParent());Files.writeString(path,file.content());}
            }
        }
    }
}
