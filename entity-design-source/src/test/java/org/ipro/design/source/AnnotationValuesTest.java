package org.ipro.design.source;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.VariableTree;
import java.util.List;
import java.util.Optional;
import org.ipro.design.source.AnnotationValues.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnnotationValuesTest {
    private static final String SOURCE = """
        package a;
        import jakarta.persistence.*;
        import jakarta.validation.constraints.*;
        public class Foo {
            @Size(max = Limits.NAME) private String constant;
            @Size(max = 40) private String number;
            @Size(max = -1) private String negative;
            @org.ipro.metadata.annotation.FieldMetadata(label = "Имя \\"x\\"", order = 7, hidden = true,
                required = RequiredMode.REQUIRED, grid = @GridColumn(visible = false, order = 3),
                lookup = @Lookup(entity = Branch.class)) private String full;
            @FieldMetadata("single") private String single;
            @EntityMetadata(listColumns = {}) private int empty;
            @EntityMetadata(listColumns = "code") private int one;
            @EntityMetadata(listColumns = {"code", "branch.name"}) private int many;
            @EntityMetadata(listColumns = COLUMNS) private int named;
            @EntityMetadata(listColumns = {"a", NAME}) private int mixed;
            @EntityMetadata(displayName = "a" + NAME, icon = "a" + "b") private int concatenated;
            private String plain;
        }
        """;

    private final ParsedSource source = JavaSources.parse("Foo.java", SOURCE);

    private VariableTree field(String name) {
        for (var member : ((ClassTree) source.topLevelTypes().getFirst()).getMembers())
            if (member instanceof VariableTree variable && variable.getName().contentEquals(name)) return variable;
        throw new AssertionError(name);
    }
    private Value argument(String field, String annotation, String argument) {
        return AnnotationValues.argument(source, AnnotationValues.find(field(field).getModifiers(), annotation), argument);
    }

    @Test void literalsAreReadWithTheirTypes() {
        assertEquals(new Literal(40, null).value(), ((Literal) argument("number", "Size", "max")).value());
        assertEquals(-1, ((Literal) argument("negative", "Size", "max")).value());
        assertEquals("Имя \"x\"", ((Literal) argument("full", "FieldMetadata", "label")).value());
        assertEquals(7, ((Literal) argument("full", "FieldMetadata", "order")).value());
        assertEquals(true, ((Literal) argument("full", "FieldMetadata", "hidden")).value());
    }

    @Test void aConstantIsNeverAValue() {
        assertInstanceOf(EnumConstant.class, argument("constant", "Size", "max"));
        var concatenated = argument("concatenated", "EntityMetadata", "displayName");
        assertInstanceOf(Opaque.class, concatenated);
        assertEquals("\"a\" + NAME", ((Opaque) concatenated).source());
        // javac folds adjacent string literals, so a literal-only concatenation is the literal it spells.
        assertEquals("ab", ((Literal) argument("concatenated", "EntityMetadata", "icon")).value());
    }

    @Test void enumConstantKeepsItsQualifier() {
        var required = (EnumConstant) argument("full", "FieldMetadata", "required");
        assertEquals("RequiredMode", required.type());
        assertEquals("REQUIRED", required.name());
    }

    @Test void nestedAnnotationsAndClassLiteralsAreReadInPlace() {
        var grid = (Nested) argument("full", "FieldMetadata", "grid");
        assertEquals(false, ((Literal) AnnotationValues.argument(source, grid.annotation(), "visible")).value());
        assertEquals(3, ((Literal) AnnotationValues.argument(source, grid.annotation(), "order")).value());
        var lookup = (Nested) argument("full", "FieldMetadata", "lookup");
        assertEquals("Branch", ((ClassLiteral) AnnotationValues.argument(source, lookup.annotation(), "entity")).type());
    }

    @Test void missingArgumentAndMissingAnnotationAreDistinctFromAnyValue() {
        assertInstanceOf(Missing.class, argument("full", "FieldMetadata", "placeholder"));
        assertInstanceOf(Missing.class, argument("plain", "FieldMetadata", "label"));
        assertEquals(Optional.empty(), AnnotationValues.find(field("plain").getModifiers(), "FieldMetadata"));
    }

    @Test void theUnnamedArgumentIsTheValueArgument() {
        assertEquals("single", ((Literal) argument("single", "FieldMetadata", "value")).value());
    }

    @Test void listsKeepTheirShape() {
        var empty = AnnotationValues.asList(argument("empty", "EntityMetadata", "listColumns"));
        assertEquals(new ListOf(List.of(), true, null).items(), ((ListOf) empty).items());
        assertTrue(((ListOf) empty).braces());
        assertEquals(Optional.of(List.of()), AnnotationValues.strings(empty));

        var one = (ListOf) AnnotationValues.asList(argument("one", "EntityMetadata", "listColumns"));
        assertFalse(one.braces());
        assertEquals(Optional.of(List.of("code")), AnnotationValues.strings(one));

        var many = argument("many", "EntityMetadata", "listColumns");
        assertEquals(Optional.of(List.of("code", "branch.name")), AnnotationValues.strings(many));
        assertTrue(((ListOf) many).braces());
    }

    @Test void aConstantOrAMixedListHasNoStrings() {
        assertEquals(Optional.empty(), AnnotationValues.strings(argument("named", "EntityMetadata", "listColumns")));
        assertEquals(Optional.empty(), AnnotationValues.strings(argument("mixed", "EntityMetadata", "listColumns")));
        assertInstanceOf(Opaque.class, AnnotationValues.asList(argument("named", "EntityMetadata", "listColumns")));
    }

    @Test void aMissingListReadsAsNoStringsButStaysMissing() {
        var missing = argument("plain", "EntityMetadata", "listColumns");
        assertInstanceOf(Missing.class, AnnotationValues.asList(missing));
        assertEquals(Optional.of(List.of()), AnnotationValues.strings(missing));
    }

    @Test void qualifiedAnnotationNamesMatchBySimpleName() {
        assertTrue(AnnotationValues.has(field("full").getModifiers(), "FieldMetadata"));
        assertFalse(AnnotationValues.has(field("full").getModifiers(), "Size"));
    }
}
