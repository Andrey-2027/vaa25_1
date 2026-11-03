package org.ipro.design.source;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.VariableTree;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.lang.model.element.Modifier;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftSuggestions;
import org.ipro.design.DraftText;
import org.ipro.design.EntityDraft;
import org.ipro.design.EntityProperties;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeCatalog.FieldType;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.EntityShape;
import org.ipro.design.existing.ExistingEntity;
import org.ipro.design.existing.LockedField;
import org.ipro.design.source.AnnotationValues.EnumConstant;
import org.ipro.design.source.AnnotationValues.Literal;
import org.ipro.design.source.AnnotationValues.Missing;
import org.ipro.design.source.AnnotationValues.Nested;
import org.ipro.design.source.AnnotationValues.Value;

/**
 * Reads an entity's Java file into an {@link ExistingEntity}. A field is read only when every
 * value that matters is understood; otherwise it is locked with the reason, and its name and column
 * stay reserved. Nothing is guessed: a constant where a literal is needed locks the field.
 */
public final class JavaEntityReader {
    private JavaEntityReader() {}

    /** Why a field cannot be read; carries only the stable reason code. */
    private static final class Unreadable extends RuntimeException {
        Unreadable(String reason) { super(reason, null, false, false); }
    }

    /** A field that was read completely, before its publication in lists is decided. */
    private record Read(int order, boolean hidden, boolean gridVisible, FieldDraft field) {}

    public static ExistingEntity read(String typeId, String text, ProjectSources project) {
        String simple = typeId.substring(typeId.lastIndexOf('.') + 1);
        var source = JavaSources.parse(simple + ".java", text);
        ClassTree type = JavaSources.primaryClass(source);
        var modifiers = type.getModifiers();
        if (!AnnotationValues.has(modifiers, "Entity") && !AnnotationValues.has(modifiers, "MappedSuperclass"))
            throw new SourceRefusal(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, typeId, "the class is neither @Entity nor @MappedSuperclass");
        String relativePath = project.type(typeId).map(SourceType::relativePath).orElse(typeId.replace('.', '/') + ".java");
        var described = SourceType.of(source, type, relativePath, null);
        if (!described.typeId().equals(typeId))
            throw new SourceRefusal(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, typeId, "the file declares " + described.typeId());
        var resolver = project.resolver(described.packageName(), described.imports());

        var chain = described.superName().isEmpty() ? new ProjectSources.Chain(List.of(), List.of(), false)
            : resolver.resolve(described.superName()).map(project::chain).orElse(new ProjectSources.Chain(List.of(), List.of(), false));
        var kind = ProjectSources.classify(described.annotations(), chain);
        var shape = switch (kind) {
            case ROOT -> EntityShape.ROOT; case SECTION_ROW -> EntityShape.SECTION_ROW;
            case REGISTER -> EntityShape.REGISTER; case UNRESOLVED_BASE -> EntityShape.UNRESOLVED_BASE;
        };

        var inherited = new LinkedHashSet<String>();
        var occupied = new LinkedHashSet<>(described.methodNames());
        for (var base : chain.projectTypes()) { inherited.addAll(base.fieldNames()); occupied.addAll(base.methodNames()); }
        for (var base : chain.platformBases()) { inherited.addAll(base.fields()); occupied.addAll(base.methods()); }
        var inheritedColumns = new LinkedHashSet<String>();
        var unreadableColumns = new LinkedHashSet<String>();
        for (var base : chain.projectTypes()) {
            base.fieldColumns().forEach((field, column) -> { if (column.isEmpty()) unreadableColumns.add(base.simpleName() + "." + field); else inheritedColumns.add(column); });
            if (base.attributeOverrides()) unreadableColumns.add(base.simpleName() + " @AttributeOverride");
        }
        for (var base : chain.platformBases()) base.fields().forEach(field -> inheritedColumns.add(RecipeCatalog.snake(field)));
        if (described.attributeOverrides()) unreadableColumns.add(simple + " @AttributeOverride");
        for (var child : project.descendants(typeId)) occupied.addAll(child.methodNames());

        // Class-level publication: a manual list wins over the per-field grid flag.
        var metadata = AnnotationValues.find(modifiers, "EntityMetadata");
        var list = columns(source, metadata, "listColumns");
        var selection = columns(source, metadata, "selectColumns");
        boolean row = shape == EntityShape.SECTION_ROW;

        var reads = new ArrayList<Read>();
        var locked = new ArrayList<LockedField>();
        var variables = type.getMembers().stream().filter(VariableTree.class::isInstance).map(VariableTree.class::cast)
            .filter(variable -> !variable.getModifiers().getFlags().contains(Modifier.STATIC)).toList();
        for (int i = 0; i < variables.size(); i++) {
            var variable = variables.get(i);
            boolean shared = i > 0 && variables.get(i - 1).getModifiers() == variable.getModifiers()
                || i + 1 < variables.size() && variables.get(i + 1).getModifiers() == variable.getModifiers();
            try {
                reads.add(readField(typeId, source, resolver, project, variable, shared));
            } catch (Unreadable unreadable) {
                locked.add(lockedField(source, variable, unreadable.getMessage()));
            }
        }
        // The order the platform shows the fields in: by declared order, ties by position in the file.
        reads.sort(Comparator.comparingInt(Read::order));

        var fields = new ArrayList<FieldDraft>();
        for (var read : reads) {
            if (!row && (list.opaque() || selection.opaque())) {
                locked.add(new LockedField(read.field().name(), DraftSuggestions.column(read.field()), read.field().label(),
                    read.field().type(), "NOT_LITERAL:" + (list.opaque() ? "listColumns" : "selectColumns")));
                continue;
            }
            boolean inList = list.manual() ? list.publishes(read.field().name()) : read.gridVisible();
            boolean inSelection = selection.manual() ? selection.publishes(read.field().name()) : inList;
            var applications = row ? new FieldApplications(!read.hidden(), false, false, read.gridVisible())
                : new FieldApplications(!read.hidden(), inList, inSelection, false);
            fields.add(withApplications(read.field(), applications));
        }

        String label = literal(source, AnnotationValues.argument(source, metadata, "itemFormTitle")).filter(s -> !s.isEmpty()).orElse(simple);
        var titles = new EntityProperties.Titles(title(source, metadata, "listFormTitle"), title(source, metadata, "itemFormTitle"),
            title(source, metadata, "selectionFormTitle"));
        String icon = literal(source, AnnotationValues.argument(source, metadata, "icon")).filter(s -> !s.isEmpty()).orElse("FILE");
        var properties = new EntityProperties(simple, DraftText.explicit(described.packageName()), label, titles,
            described.tableName().isEmpty() ? DraftText.auto() : DraftText.explicit(described.tableName()), null, icon);
        var draft = new EntityDraft(2, RecipeCatalog.EXISTING_ENTITY, UUID.nameUUIDFromBytes(("entity#" + typeId).getBytes(StandardCharsets.UTF_8)),
            properties, fields, List.of());
        return new ExistingEntity(typeId, relativePath, source.sha256(), described.tableName(), shape, draft, locked, inherited, occupied, inheritedColumns, unreadableColumns);
    }

    // ------------------------------------------------------------------ one field

    private static Read readField(String typeId, ParsedSource source, NameResolver resolver, ProjectSources project,
                                  VariableTree variable, boolean shared) {
        String name = variable.getName().toString();
        var modifiers = variable.getModifiers();
        if (shared) throw new Unreadable("MULTIPLE_NAMES");
        if (modifiers.getFlags().contains(Modifier.TRANSIENT) || AnnotationValues.has(modifiers, "Transient")) throw new Unreadable("TRANSIENT");
        if (AnnotationValues.has(modifiers, "Id") || AnnotationValues.has(modifiers, "EmbeddedId")) throw new Unreadable("ID");
        if (AnnotationValues.has(modifiers, "OneToMany") || AnnotationValues.has(modifiers, "ManyToMany")
                || AnnotationValues.has(modifiers, "ElementCollection")) throw new Unreadable("COLLECTION");
        if (AnnotationValues.has(modifiers, "Enumerated")) throw new Unreadable("ENUM");
        if (AnnotationValues.has(modifiers, "OneToOne") || AnnotationValues.has(modifiers, "Embedded"))
            throw new Unreadable("ASSOCIATION_NOT_SUPPORTED");
        var metadata = AnnotationValues.find(modifiers, "FieldMetadata");
        if (metadata.isEmpty()) throw new Unreadable("NO_FIELD_METADATA");

        // Type.
        var tree = variable.getType();
        if (tree instanceof ParameterizedTypeTree) throw new Unreadable("COLLECTION");
        if (tree instanceof ArrayTypeTree) throw new Unreadable("TYPE_NOT_SUPPORTED");
        boolean primitive = tree instanceof PrimitiveTypeTree;
        boolean reference = AnnotationValues.has(modifiers, "ManyToOne");
        FieldType fieldType;
        String referenceTypeId = null;
        if (primitive) {
            fieldType = switch (((PrimitiveTypeTree) tree).getPrimitiveTypeKind()) {
                case INT -> FieldType.INTEGER; case LONG -> FieldType.LONG; case BOOLEAN -> FieldType.BOOLEAN;
                default -> throw new Unreadable("TYPE_NOT_SUPPORTED");
            };
            if (reference) throw new Unreadable("TYPE_NOT_SUPPORTED");
        } else {
            String written = AnnotationValues.qualifiedName(tree);
            if (written == null) throw new Unreadable("TYPE_NOT_SUPPORTED");
            Optional<String> resolved = resolver.resolve(written);
            if (reference) {
                if (resolved.isEmpty()) throw new Unreadable("REFERENCE_UNRESOLVED");
                if (scalar(resolved.get()) != null) throw new Unreadable("TYPE_NOT_SUPPORTED");
                var target = project.type(resolved.get());
                if (target.isPresent() && target.get().kind() != SourceType.Kind.CLASS)
                    throw new Unreadable(target.get().kind() == SourceType.Kind.ENUM ? "ENUM" : "TYPE_NOT_SUPPORTED");
                fieldType = FieldType.REFERENCE;
                referenceTypeId = resolved.get();
            } else {
                if (resolved.isEmpty()) throw new Unreadable("TYPE_UNRESOLVED");
                fieldType = scalar(resolved.get());
                if (fieldType == null) {
                    boolean enumeration = project.type(resolved.get()).map(t -> t.kind() == SourceType.Kind.ENUM).orElse(false);
                    throw new Unreadable(enumeration ? "ENUM" : "TYPE_NOT_SUPPORTED");
                }
            }
        }

        // Values of @FieldMetadata that the model keeps.
        String label = scalar(source, AnnotationValues.argument(source, metadata, "label"), String.class, "label", "");
        int order = scalar(source, AnnotationValues.argument(source, metadata, "order"), Integer.class, "order", 999);
        boolean hidden = scalar(source, AnnotationValues.argument(source, metadata, "hidden"), Boolean.class, "hidden", false);
        var required = AnnotationValues.argument(source, metadata, "required");
        FieldDraft.UiRequired uiRequired;
        if (required instanceof Missing) uiRequired = FieldDraft.UiRequired.AUTO;
        else if (required instanceof EnumConstant constant && constant.type().equals("RequiredMode") && Set.of("AUTO", "REQUIRED", "OPTIONAL").contains(constant.name()))
            uiRequired = FieldDraft.UiRequired.valueOf(constant.name());
        else throw new Unreadable("NOT_LITERAL:required");
        boolean gridVisible = true;
        var grid = AnnotationValues.argument(source, metadata, "grid");
        if (grid instanceof Nested nested)
            gridVisible = scalar(source, AnnotationValues.argument(source, nested.annotation(), "visible"), Boolean.class, "grid", true);
        else if (!(grid instanceof Missing)) throw new Unreadable("NOT_LITERAL:grid");

        // Write contract: Bean Validation, then JPA.
        boolean serverRequired = AnnotationValues.has(modifiers, "NotNull") || AnnotationValues.has(modifiers, "NotBlank")
            || AnnotationValues.has(modifiers, "NotEmpty");
        var column = AnnotationValues.find(modifiers, "Column");
        var joinColumn = AnnotationValues.find(modifiers, "JoinColumn");
        if (!primitive) {
            serverRequired |= !flag(source, column, "nullable", true, "nullable");
            serverRequired |= !flag(source, joinColumn, "nullable", true, "nullable");
            serverRequired |= !flag(source, AnnotationValues.find(modifiers, "ManyToOne"), "optional", true, "optional");
            serverRequired |= !flag(source, AnnotationValues.find(modifiers, "Basic"), "optional", true, "optional");
        }

        // Constraints.
        var constraints = ConstraintDraft.empty();
        if (fieldType == FieldType.STRING) {
            Optional<Integer> columnLength = optionalInt(source, column, "length", "length");
            Optional<Integer> sizeMax = optionalInt(source, AnnotationValues.find(modifiers, "Size"), "max", "length");
            constraints = new ConstraintDraft(Integer.toString(columnLength.or(() -> sizeMax).orElse(255)), "", "");
        } else if (fieldType == FieldType.DECIMAL) {
            Optional<Integer> columnPrecision = optionalInt(source, column, "precision", "precision");
            Optional<Integer> columnScale = optionalInt(source, column, "scale", "scale");
            var digits = AnnotationValues.find(modifiers, "Digits");
            String precision = "", scale = "";
            if (columnPrecision.isPresent() && columnPrecision.get() > 0) {
                precision = columnPrecision.get().toString();
                scale = Integer.toString(columnScale.orElse(0));
            } else if (digits.isPresent()) {
                int integer = optionalInt(source, digits, "integer", "precision").orElseThrow(() -> new Unreadable("NOT_LITERAL:precision"));
                int fraction = optionalInt(source, digits, "fraction", "scale").orElse(0);
                precision = Integer.toString(integer + fraction);
                scale = Integer.toString(fraction);
            } else if (columnScale.isPresent() && columnScale.get() > 0) {
                scale = columnScale.get().toString();
            }
            constraints = new ConstraintDraft("", precision, scale);
        }

        DraftText columnText = columnName(source, fieldType == FieldType.REFERENCE ? joinColumn : column, name, fieldType == FieldType.REFERENCE)
            .orElseThrow(() -> new Unreadable("NOT_LITERAL:column"));
        var draft = new FieldDraft(UUID.nameUUIDFromBytes((typeId + "#" + name).getBytes(StandardCharsets.UTF_8)), name,
            label.isEmpty() ? name : label, fieldType.name(), DraftText.auto(), serverRequired, uiRequired, constraints,
            referenceTypeId == null ? null : new ReferenceDraft(referenceTypeId), FieldApplications.root());
        if (columnText.mode() == DraftText.Mode.EXPLICIT) draft = withColumn(draft, columnText);
        return new Read(order, hidden, gridVisible, draft);
    }

    /**
     * The column of a field: the declared literal, or Hibernate's implicit name. It stays automatic
     * when the editor would suggest the same name, so only real differences are written down.
     */
    private static Optional<DraftText> columnName(ParsedSource source, Optional<AnnotationTree> annotation,
                                                  String field, boolean join) {
        var declared = AnnotationValues.argument(source, annotation, "name");
        String physical;
        if (declared instanceof Literal literal && literal.value() instanceof String text && !text.isEmpty()) return Optional.of(DraftText.explicit(text));
        else if (declared instanceof Missing || declared instanceof Literal literal && "".equals(literal.value()))
            physical = join ? PhysicalNames.implicitJoinColumn(field) : PhysicalNames.implicitColumn(field);
        else return Optional.empty();
        String suggested = RecipeCatalog.snake(field) + (join ? "_id" : "");
        return Optional.of(physical.equals(suggested) ? DraftText.auto() : DraftText.explicit(physical));
    }

    private static LockedField lockedField(ParsedSource source, VariableTree variable, String reason) {
        String name = variable.getName().toString();
        var modifiers = variable.getModifiers();
        boolean join = AnnotationValues.has(modifiers, "ManyToOne");
        String column = "";
        try {
            var annotation = AnnotationValues.find(modifiers, join ? "JoinColumn" : "Column");
            var text = columnName(source, annotation, name, join);
            if (text.isPresent()) column = text.get().mode() == DraftText.Mode.EXPLICIT ? text.get().text()
                : RecipeCatalog.snake(name) + (join ? "_id" : "");
        } catch (RuntimeException ignored) { /* a column that cannot be read stays empty */ }
        String label = literal(source, AnnotationValues.argument(source, AnnotationValues.find(modifiers, "FieldMetadata"), "label")).orElse("");
        return new LockedField(name, column, label, source.source(variable.getType()), reason);
    }

    // ------------------------------------------------------------------ helpers

    private static FieldType scalar(String typeId) {
        return switch (typeId) {
            case "java.lang.String" -> FieldType.STRING;
            case "java.lang.Integer" -> FieldType.INTEGER;
            case "java.lang.Long" -> FieldType.LONG;
            case "java.math.BigDecimal" -> FieldType.DECIMAL;
            case "java.lang.Boolean" -> FieldType.BOOLEAN;
            case "java.time.LocalDate" -> FieldType.DATE;
            case "java.time.LocalDateTime" -> FieldType.DATETIME;
            default -> null;
        };
    }

    private static FieldDraft withApplications(FieldDraft f, FieldApplications applications) {
        return new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(),
            f.constraints(), f.reference(), applications);
    }
    private static FieldDraft withColumn(FieldDraft f, DraftText column) {
        return new FieldDraft(f.id(), f.name(), f.label(), f.type(), column, f.serverRequired(), f.uiRequired(),
            f.constraints(), f.reference(), f.applications());
    }

    private static Optional<String> literal(ParsedSource source, Value value) {
        return value instanceof Literal literal && literal.value() instanceof String text ? Optional.of(text) : Optional.empty();
    }

    /** A literal of the given type, {@code whenMissing} when the argument is absent; anything else locks the field. */
    private static <T> T scalar(ParsedSource source, Value value, Class<T> type, String what, T whenMissing) {
        if (value instanceof Missing) return whenMissing;
        if (value instanceof Literal literal && type.isInstance(literal.value())) return type.cast(literal.value());
        throw new Unreadable("NOT_LITERAL:" + what);
    }

    private static boolean flag(ParsedSource source, Optional<AnnotationTree> annotation, String argument,
                                boolean whenMissing, String what) {
        if (annotation.isEmpty()) return true;
        var value = AnnotationValues.argument(source, annotation, argument);
        if (value instanceof Missing) return whenMissing;
        if (value instanceof Literal literal && literal.value() instanceof Boolean flag) return flag;
        throw new Unreadable("NOT_LITERAL:" + what);
    }

    private static Optional<Integer> optionalInt(ParsedSource source, Optional<AnnotationTree> annotation,
                                                 String argument, String what) {
        var value = AnnotationValues.argument(source, annotation, argument);
        if (value instanceof Missing) return Optional.empty();
        if (value instanceof Literal literal && literal.value() instanceof Integer number) return Optional.of(number);
        throw new Unreadable("NOT_LITERAL:" + what);
    }

    private static DraftText title(ParsedSource source, Optional<AnnotationTree> metadata, String argument) {
        return literal(source, AnnotationValues.argument(source, metadata, argument)).filter(s -> !s.isEmpty())
            .map(DraftText::explicit).orElse(DraftText.auto());
    }

    /** The manual column list of {@code @EntityMetadata}: absent, a list of strings, or not understood. */
    private record Columns(boolean manual, boolean opaque, List<String> paths) {
        boolean publishes(String field) {
            return paths.stream().anyMatch(path -> path.equals(field) || path.startsWith(field + "."));
        }
    }

    private static Columns columns(ParsedSource source, Optional<AnnotationTree> metadata, String argument) {
        var value = AnnotationValues.asList(AnnotationValues.argument(source, metadata, argument));
        var strings = AnnotationValues.strings(value);
        if (strings.isEmpty()) return new Columns(false, true, List.of());
        // An absent argument and an explicit {} both ask the platform to pick columns itself.
        return new Columns(!strings.get().isEmpty(), false, strings.get());
    }
}
