import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.ipro.design.source.*;

/** Exercises the real CLI against disposable copies of an extracted actual browser plan. */
class ProbeModificationApply {
    public static void main(String[] args) throws Exception {
        var directory=Path.of(args[0]);var root=Path.of(args[1]);
        var plan=PlanDirectory.read(directory);
        String[] command={"apply","--project",root.toString(),"--plan",directory.toString()};
        int applied=EntityModifyCli.run(command,System.out,System.err);
        int noop=EntityModifyCli.run(command,System.out,System.err);
        if(applied!=0 || noop!=0) throw new IllegalStateException("CLI positive/NOOP failed");
        var target=root.resolve("src/main/java").resolve(plan.relativePath());
        Files.writeString(target,"// controlled acceptance conflict\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        byte[] edited=Files.readAllBytes(target);
        int conflict=EntityModifyCli.run(command,System.out,System.err);
        if(conflict!=2 || !Arrays.equals(edited,Files.readAllBytes(target))) throw new IllegalStateException("CLI did not preserve manual edit");
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),
            Map.of("status","PASS","planId",plan.planId(),"appliedExit",applied,"noopExit",noop,"conflictExit",conflict,"manualEditPreserved",true,"scope","separate external copy; no change to positive editor"));
    }
}
