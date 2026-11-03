import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftText;
import org.ipro.design.DraftValidator;
import org.ipro.design.EntityDraft;
import org.ipro.design.EntityProperties;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.generation.EntityJavaGenerator;

/**
 * EM5 test rig: writes a real generated catalog (the generator, not a hand-made file) into a source root.
 * Run as {@code java -cp entity-design-core/target/classes GenerateCatalog.java <source-root>}.
 */
public class GenerateCatalog {
    public static void main(String[] args) throws Exception {
        String pkg = "com.example.entitytypes.model";
        var names = ProjectDesignContext.Names.known(Set.of());
        var available = new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "em5");
        var snapshot = new RuntimeDesignSnapshot("em5", true, Map.of(), Map.of(
            "STANDARD_CATALOG_APP_API", available, "STANDARD_DOCUMENT_APP_API", available, "ET0_ET7_RUNTIME", available));
        var context = new ProjectDesignContext("em5", "v1", pkg, names, names, names, ProjectDesignContext.Names.known(Set.of("FILE")),
            Map.of(), true, snapshot);
        var title = new FieldDraft(new UUID(5, 1), "title", "Название", "STRING", DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            new ConstraintDraft("100", "", ""), null, new FieldApplications(true, true, true, false));
        var entity = new EntityProperties("GeneratedItem", DraftText.explicit(pkg), "Образец", EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE");
        var draft = new EntityDraft(1, RecipeCatalog.STANDARD_CATALOG_V2, new UUID(5, 0), entity, List.of(title), List.of());
        var result = new DraftValidator().validate(draft, context);
        if (result.hasErrors()) throw new IllegalStateException(result.problems().toString());
        var generated = new EntityJavaGenerator().generate(result.checkedStructure().orElseThrow(), draft, context);
        if (!generated.succeeded()) throw new IllegalStateException(generated.diagnostics().toString());
        var file = generated.plan().orElseThrow().files().getFirst();
        Path target = Path.of(args[0]).resolve(file.relativePath());
        Files.createDirectories(target.getParent());
        Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        System.out.println("generated " + target);
    }
}
