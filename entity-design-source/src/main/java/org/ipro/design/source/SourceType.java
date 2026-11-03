package org.ipro.design.source;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Modifier;
import org.ipro.design.source.AnnotationValues.Literal;

/**
 * What the project index keeps about one top-level type, read from its source alone.
 *
 * @param superName   the {@code extends} clause as written, without type arguments; empty when absent
 * @param annotations simple names of the class annotations
 * @param tableName   the physical table of an {@code @Entity}; empty when unknown or not an entity
 * @param fieldNames  non-static fields in declaration order
 */
public record SourceType(String typeId, String simpleName, String packageName, String relativePath, Path root, String sha256,
                         Kind kind, boolean isAbstract, String superName, List<String> imports, Set<String> annotations,
                         String entityName, String tableName, boolean schemaDeclared, boolean secondaryTable,
                         boolean inheritanceDeclared, List<String> fieldNames, Set<String> methodNames) {
    public enum Kind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION }

    public SourceType {
        imports = List.copyOf(imports); annotations = Set.copyOf(annotations);
        fieldNames = List.copyOf(fieldNames); methodNames = Set.copyOf(methodNames);
    }

    public boolean isEntity() { return annotations.contains("Entity"); }

    static SourceType of(ParsedSource source, ClassTree type, String relativePath, Path root) {
        String simple = type.getSimpleName().toString(), pkg = source.packageName();
        var imports = new ArrayList<String>();
        for (var imported : source.imports()) imports.add((imported.isStatic() ? "static " : "") + imported.getQualifiedIdentifier());
        var annotations = new LinkedHashSet<String>();
        for (var annotation : type.getModifiers().getAnnotations()) annotations.add(AnnotationValues.simpleName(annotation.getAnnotationType()));
        String superName = "";
        if (type.getExtendsClause() != null) {
            Tree clause = type.getExtendsClause() instanceof ParameterizedTypeTree generic ? generic.getType() : type.getExtendsClause();
            String written = AnnotationValues.qualifiedName(clause);
            superName = written == null ? "?" : written;
        }
        var modifiers = type.getModifiers();
        var fields = new ArrayList<String>();
        var methods = new LinkedHashSet<String>();
        for (var member : type.getMembers()) {
            if (member instanceof VariableTree variable && !variable.getModifiers().getFlags().contains(Modifier.STATIC))
                fields.add(variable.getName().toString());
            else if (member instanceof MethodTree method && !method.getName().contentEquals("<init>"))
                methods.add(method.getName().toString());
        }
        var entity = AnnotationValues.find(modifiers, "Entity");
        var entityNameArgument = AnnotationValues.argument(source, entity, "name");
        String entityName = simple;
        if (entityNameArgument instanceof Literal literal && literal.value() instanceof String text) { if (!text.isEmpty()) entityName = text; }
        else if (!(entityNameArgument instanceof AnnotationValues.Missing)) entityName = "";
        var table = AnnotationValues.find(modifiers, "Table");
        String tableName = "";
        if (entity.isPresent() && !entityName.isEmpty()) {
            var tableNameArgument = AnnotationValues.argument(source, table, "name");
            if (tableNameArgument instanceof Literal literal && literal.value() instanceof String text)
                tableName = text.isEmpty() ? PhysicalNames.implicitTable(entityName) : text;
            else if (tableNameArgument instanceof AnnotationValues.Missing) tableName = PhysicalNames.implicitTable(entityName);
        }
        boolean schema = !(AnnotationValues.argument(source, table, "schema") instanceof AnnotationValues.Missing)
            || !(AnnotationValues.argument(source, table, "catalog") instanceof AnnotationValues.Missing);
        Kind kind = switch (type.getKind()) {
            case INTERFACE -> Kind.INTERFACE; case ENUM -> Kind.ENUM; case RECORD -> Kind.RECORD;
            case ANNOTATION_TYPE -> Kind.ANNOTATION; default -> Kind.CLASS;
        };
        return new SourceType(pkg.isEmpty() ? simple : pkg + "." + simple, simple, pkg, relativePath, root, source.sha256(), kind,
            modifiers.getFlags().contains(Modifier.ABSTRACT), superName, imports, annotations, entityName, tableName, schema,
            AnnotationValues.has(modifiers, "SecondaryTable") || AnnotationValues.has(modifiers, "SecondaryTables"),
            AnnotationValues.has(modifiers, "Inheritance"), fields, methods);
    }
}
