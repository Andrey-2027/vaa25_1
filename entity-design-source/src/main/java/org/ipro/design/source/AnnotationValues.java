package org.ipro.design.source;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads annotations syntactically. An argument is a literal, an enum constant, a class literal,
 * a nested annotation, a list, or not understood; "not understood" is never turned into a
 * default value, so a constant such as {@code Limits.MAX} cannot be mistaken for a number.
 */
public final class AnnotationValues {
    private AnnotationValues() {}

    /** The state of one annotation argument. */
    public sealed interface Value permits Missing, Literal, EnumConstant, ClassLiteral, Nested, ListOf, Opaque {}
    /** The annotation has no such argument (or there is no annotation at all). */
    public record Missing() implements Value {}
    /** A string, number or boolean written directly; negative numbers included. */
    public record Literal(Object value, ExpressionTree tree) implements Value {}
    /** {@code RequiredMode.REQUIRED}; the type qualifier is kept so a bare name is never accepted. */
    public record EnumConstant(String type, String name, ExpressionTree tree) implements Value {}
    /** {@code Branch.class}. */
    public record ClassLiteral(String type, ExpressionTree tree) implements Value {}
    /** {@code grid = @GridColumn(...)}. */
    public record Nested(AnnotationTree annotation) implements Value {}
    /**
     * An array initializer ({@code braces}) or a single value standing for a one-element array.
     * {@code {}} is a list with no items.
     */
    public record ListOf(List<Value> items, boolean braces, ExpressionTree tree) implements Value {
        public ListOf { items = List.copyOf(items); }
    }
    /** Anything else: a constant, a concatenation, a method call. */
    public record Opaque(ExpressionTree tree, String source) implements Value {}

    /** The first annotation whose simple name is {@code simpleName}, however it is qualified. */
    public static Optional<AnnotationTree> find(ModifiersTree modifiers, String simpleName) {
        for (var annotation : modifiers.getAnnotations()) {
            if (simpleName.equals(simpleName(annotation.getAnnotationType()))) return Optional.of(annotation);
        }
        return Optional.empty();
    }

    public static boolean has(ModifiersTree modifiers, String simpleName) { return find(modifiers, simpleName).isPresent(); }

    /** The argument called {@code name}; the unnamed single argument counts as {@code value}. */
    public static Value argument(ParsedSource source, Optional<AnnotationTree> annotation, String name) {
        if (annotation.isEmpty()) return new Missing();
        return argument(source, annotation.get(), name);
    }

    public static Value argument(ParsedSource source, AnnotationTree annotation, String name) {
        for (var argument : annotation.getArguments()) {
            if (argument instanceof AssignmentTree assignment) {
                if (assignment.getVariable() instanceof IdentifierTree id && id.getName().contentEquals(name))
                    return value(source, assignment.getExpression());
            } else if (name.equals("value")) {
                return value(source, argument);
            }
        }
        return new Missing();
    }

    /** Reads one expression. */
    public static Value value(ParsedSource source, ExpressionTree tree) {
        ExpressionTree expression = tree;
        while (expression instanceof ParenthesizedTree parenthesized) expression = parenthesized.getExpression();
        if (expression instanceof LiteralTree literal) {
            return literal.getValue() == null ? new Opaque(tree, source.source(tree)) : new Literal(literal.getValue(), tree);
        }
        if (expression instanceof UnaryTree unary && unary.getKind() == Tree.Kind.UNARY_MINUS
                && unary.getExpression() instanceof LiteralTree inner && inner.getValue() instanceof Number number) {
            return switch (number) {
                case Integer i -> new Literal(-i, tree);
                case Long l -> new Literal(-l, tree);
                default -> new Opaque(tree, source.source(tree));
            };
        }
        if (expression instanceof MemberSelectTree select) {
            String member = select.getIdentifier().toString();
            String qualifier = simpleName(select.getExpression());
            if (member.equals("class")) return new ClassLiteral(qualifiedName(select.getExpression()), tree);
            if (qualifier != null
                    && Character.isUpperCase(member.charAt(0)) && member.equals(member.toUpperCase(java.util.Locale.ROOT))
                    && Character.isUpperCase(qualifier.charAt(0)))
                return new EnumConstant(qualifier, member, tree);
            return new Opaque(tree, source.source(tree));
        }
        if (expression instanceof NewArrayTree array && array.getType() == null && array.getInitializers() != null) {
            var items = new ArrayList<Value>();
            for (var item : array.getInitializers()) items.add(value(source, item));
            return new ListOf(items, true, tree);
        }
        if (expression instanceof AnnotationTree nested) return new Nested(nested);
        return new Opaque(tree, source.source(tree));
    }

    /** A value used where a list is expected: a single value stands for a one-element list. */
    public static Value asList(Value value) {
        return switch (value) {
            case Missing missing -> missing;
            case Opaque opaque -> opaque;
            case ListOf list -> list;
            case Literal literal -> new ListOf(List.of(literal), false, literal.tree());
            case EnumConstant constant -> new ListOf(List.of(constant), false, constant.tree());
            case ClassLiteral literal -> new ListOf(List.of(literal), false, literal.tree());
            case Nested nested -> new ListOf(List.of(nested), false, (ExpressionTree) nested.annotation());
        };
    }

    /**
     * The strings of a list argument, in order, or empty when the argument is not a list of string
     * literals. A missing argument and an explicit {@code {}} both give an empty list; ask
     * {@link #asList} when the difference matters.
     */
    public static Optional<List<String>> strings(Value value) {
        var list = asList(value);
        if (list instanceof Missing) return Optional.of(List.of());
        if (!(list instanceof ListOf items)) return Optional.empty();
        var result = new ArrayList<String>();
        for (var item : items.items()) {
            if (item instanceof Literal literal && literal.value() instanceof String text) result.add(text);
            else return Optional.empty();
        }
        return Optional.of(result);
    }

    /** The simple name of an annotation type or qualifier: {@code a.b.C} gives {@code C}. */
    public static String simpleName(Tree tree) {
        if (tree instanceof IdentifierTree id) return id.getName().toString();
        if (tree instanceof MemberSelectTree select) return select.getIdentifier().toString();
        return null;
    }

    /** A dotted name as written; null when the tree is not a plain name. */
    public static String qualifiedName(Tree tree) {
        if (tree instanceof IdentifierTree id) return id.getName().toString();
        if (tree instanceof MemberSelectTree select) {
            String qualifier = qualifiedName(select.getExpression());
            return qualifier == null ? null : qualifier + "." + select.getIdentifier();
        }
        return null;
    }
}
