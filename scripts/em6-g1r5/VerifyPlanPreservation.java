import java.nio.file.Path;
import java.util.*;
import com.github.javaparser.StaticJavaParser;
import org.ipro.design.source.PlanDirectory;

/** Independent AST comparison of pre-existing manual code in the actual browser plans. */
class VerifyPlanPreservation {
    public static void main(String[] args) throws Exception {
        var plan=PlanDirectory.read(Path.of(args[0]));
        var before=StaticJavaParser.parse(plan.baseContent()).getType(0).asClassOrInterfaceDeclaration();
        var after=StaticJavaParser.parse(plan.newContent()).getType(0).asClassOrInterfaceDeclaration();
        var oldAnnotations=before.getAnnotations().stream().filter(a->!a.getNameAsString().equals("EntityMetadata")).map(Object::toString).toList();
        var newAnnotations=after.getAnnotations().stream().filter(a->!a.getNameAsString().equals("EntityMetadata")).map(Object::toString).toList();
        if(!oldAnnotations.equals(newAnnotations)) throw new IllegalStateException("Manual class annotation sequence changed");
        for(var method:before.getMethods()) {
            var actual=after.getMethodsByName(method.getNameAsString()).stream().filter(m->m.getSignature().equals(method.getSignature())).findFirst().orElseThrow();
            if(!method.toString().equals(actual.toString())) throw new IllegalStateException("Manual method changed: "+method.getSignature());
        }
        for(var constructor:before.getConstructors()) {
            if(after.getConstructors().stream().noneMatch(c->c.toString().equals(constructor.toString()))) throw new IllegalStateException("Constructor changed");
        }
        for(var field:before.getFields()) for(var variable:field.getVariables()) {
            var actual=after.getFieldByName(variable.getNameAsString()).orElseThrow();
            boolean allowedCaption=before.getNameAsString().equals("Workshop") && variable.getNameAsString().equals("code");
            if(!allowedCaption && !field.toString().equals(actual.toString())) throw new IllegalStateException("Existing field changed: "+variable.getNameAsString());
        }
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),
            Map.of("status","PASS","planId",plan.planId(),"existingMethods",before.getMethods().size(),"manualClassAnnotationsAndConstructorsPreserved",true,"existingFieldsPreservedExceptRequestedCaption",true));
    }
}
