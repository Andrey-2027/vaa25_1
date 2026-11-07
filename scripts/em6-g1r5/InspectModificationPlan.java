import java.nio.file.Path;
import org.ipro.design.source.PlanDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads an extracted browser ZIP using the production strict plan-directory reader. */
class InspectModificationPlan {
    public static void main(String[] args) throws Exception {
        var plan=PlanDirectory.read(Path.of(args[0]));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),plan);
        System.out.println("PASS "+plan.planId()+" "+plan.relativePath());
    }
}
