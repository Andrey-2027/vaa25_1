package org.ipro.query;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.ipro.register.RecorderRef;

/** Пакет запросов: упорядоченные нерекурсивные CTE и итоговый запрос. */
public record VisualQueryPackage(int version, List<Cte> ctes, VisualQueryDefinition main) {
    public static final int CURRENT_VERSION = 2;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public VisualQueryPackage(List<Cte> ctes, VisualQueryDefinition main) {
        this(CURRENT_VERSION, ctes, main);
    }

    public VisualQueryPackage {
        if (version < 1 || version > CURRENT_VERSION) {
            throw new IllegalArgumentException("Неподдерживаемая версия пакета visual query: " + version);
        }
        ctes = List.copyOf(ctes == null ? List.of() : ctes);
        main = Objects.requireNonNull(main, "main");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < ctes.size(); i++) {
            Cte cte = Objects.requireNonNull(ctes.get(i), "ctes[" + i + "]");
            if (version < 2 && !cte.selectedRecorders().isEmpty())
                throw new IllegalArgumentException("Выбор регистратора в CTE требует версию visual package 2 или новее");
            if (!names.add(cte.name())) throw new IllegalArgumentException("Повторное имя CTE: " + cte.name());
            if (cte.source() instanceof CteSource source && !names.contains(source.cteName())) {
                throw new IllegalArgumentException("CTE " + cte.name() + " ссылается на недоступный этап: " + source.cteName());
            }
        }
    }

    /** Явный источник корня этапа: JPA-сущность или виртуальный результат предыдущего CTE. */
    public sealed interface Source permits EntitySource, CteSource {
        String name();
        String alias();
    }

    public record EntitySource(String name, String alias) implements Source {
        public EntitySource {
            validateName(name, "сущности");
            validateName(alias, "alias источника");
        }

        public EntitySource(String name) {
            this(name, "root");
        }
    }

    /** name — alias корня, cteName — имя ранее объявленного CTE. */
    public record CteSource(String alias, String cteName) implements Source {
        public CteSource {
            validateName(alias, "alias источника CTE");
            validateName(cteName, "имени CTE");
        }

        @Override
        public String name() {
            return cteName;
        }
    }

    /** Виртуальная сущность, построенная из колонок SELECT конкретного этапа. */
    public record VirtualEntity(String name, List<QueryBuilderMetadataCatalog.Field> fields) {
        public VirtualEntity {
            validateName(name, "имени CTE");
            fields = List.copyOf(fields == null ? List.of() : fields);
        }

        public QueryBuilderMetadataCatalog.Entity asCatalogEntity() {
            return new QueryBuilderMetadataCatalog.Entity(name, Object.class, fields, List.of(),
                    name + " (временная таблица)");
        }

        public static VirtualEntity from(String name, VisualQueryDefinition definition,
                                         QueryBuilderMetadataCatalog metadataCatalog) {
            return from(name, definition, metadataCatalog, VirtualCatalog.empty());
        }

        public static VirtualEntity from(String name, VisualQueryDefinition definition,
                                         QueryBuilderMetadataCatalog metadataCatalog,
                                         VirtualCatalog virtualCatalog) {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(metadataCatalog, "metadataCatalog");
            Objects.requireNonNull(virtualCatalog, "virtualCatalog");
            QueryBuilderMetadataCatalog.Entity root = virtualCatalog.entity(definition.entityName());
            if (root == null) {
                try {
                    root = metadataCatalog.root(definition.entityName());
                } catch (IllegalArgumentException ignored) {
                    root = null;
                }
            }
            if (root == null) {
                throw new IllegalArgumentException("Источник CTE не найден в виртуальном или обычном каталоге: " + definition.entityName());
            }
            var fields = new java.util.ArrayList<QueryBuilderMetadataCatalog.Field>();
            for (VisualQueryDefinition.SelectField selected : definition.selectFields()) {
                QueryBuilderMetadataCatalog.Field source = sourceField(selected.path(), definition, metadataCatalog, virtualCatalog);
                fields.add(new QueryBuilderMetadataCatalog.Field(selected.resultName(), selected.resultName(),
                        sourceType(selected.path(), definition, metadataCatalog, virtualCatalog), source != null && source.technical(), List.of(),
                        referenceType(selected.path(), definition, metadataCatalog, virtualCatalog)));
            }
            for (VisualQueryDefinition.Aggregate aggregate : definition.aggregates()) {
                fields.add(new QueryBuilderMetadataCatalog.Field(aggregate.resultName(), aggregate.resultName(),
                        aggregateType(aggregate, definition, metadataCatalog, virtualCatalog), false));
            }
            for (VisualQueryDefinition.Expression expression : definition.expressions()) {
                fields.add(new QueryBuilderMetadataCatalog.Field(expression.resultName(), expression.resultName(),
                        expressionType(expression.expression(), definition, metadataCatalog, virtualCatalog), false));
            }
            return new VirtualEntity(name, fields);
        }

        private static Class<?> referenceType(String path, VisualQueryDefinition definition,
                                              QueryBuilderMetadataCatalog catalog, VirtualCatalog virtual) {
            int dot = path.indexOf('.');
            if (dot < 0) return null;
            var entity = sourceEntity(path.substring(0, dot), definition, catalog, virtual);
            if (entity == null) return null;
            String relative = path.substring(dot + 1);
            var field = entity.fields().stream().filter(value -> value.name().equals(relative)).findFirst();
            if (field.isPresent() && field.get().referenceType() != null) return field.get().referenceType();
            if (!relative.equals("id") && !relative.endsWith(".id")) return null;
            String[] segments = relative.split("\\.");
            for (int index = 0; index < segments.length - 1; index++) {
                String segment = segments[index];
                var association = entity.associations().stream().filter(value -> value.name().equals(segment)).findFirst();
                if (association.isEmpty()) return null;
                Class<?> target = association.get().targetType();
                if (index == segments.length - 2) return target;
                entity = catalog.roots().stream().filter(value -> value.javaType() == target).findFirst().orElse(null);
                if (entity == null) return null;
            }
            return entity.javaType() == Object.class ? null : entity.javaType();
        }

        private static QueryBuilderMetadataCatalog.Entity sourceEntity(String alias, VisualQueryDefinition definition,
                                                                        QueryBuilderMetadataCatalog catalog, VirtualCatalog virtual) {
            if (alias.equals(definition.entityAlias())) {
                var entity = virtual.entity(definition.entityName());
                if (entity != null) return entity;
                try { return catalog.root(definition.entityName()); } catch (IllegalArgumentException unavailable) { return null; }
            }
            var join = definition.joins().stream().filter(value -> value.alias().equals(alias)).findFirst();
            if (join.isEmpty()) return null;
            if (join.get().independent()) {
                var entity = virtual.entity(join.get().sourcePath());
                if (entity != null) return entity;
                try { return catalog.root(join.get().sourcePath()); } catch (IllegalArgumentException unavailable) { return null; }
            }
            var parent = sourceEntity(join.get().parentAlias() == null ? definition.entityAlias() : join.get().parentAlias(), definition, catalog, virtual);
            if (parent == null) return null;
            var association = parent.associations().stream().filter(value -> value.name().equals(join.get().sourcePath())).findFirst();
            return association.isEmpty() ? null : catalog.roots().stream().filter(value -> value.javaType() == association.get().targetType()).findFirst().orElse(null);
        }

        private static Class<?> sourceType(String path, VisualQueryDefinition definition,
                                           QueryBuilderMetadataCatalog catalog, VirtualCatalog virtual) {
            var field = sourceField(path, definition, catalog, virtual);
            return field == null ? Object.class : field.javaType();
        }

        private static QueryBuilderMetadataCatalog.Field sourceField(String path, VisualQueryDefinition definition,
                                                                      QueryBuilderMetadataCatalog catalog, VirtualCatalog virtual) {
            int dot = path.indexOf('.');
            String alias = dot < 0 ? definition.entityAlias() : path.substring(0, dot);
            String relative = dot < 0 ? path : path.substring(dot + 1);
            var entity = sourceEntity(alias, definition, catalog, virtual);
            while (entity != null) {
                String fieldPath = relative;
                var exact = entity.fields().stream().filter(field -> field.name().equals(fieldPath)).findFirst();
                if (exact.isPresent()) return exact.get();
                if (relative.equals("id") && entity.javaType() != Object.class)
                    return new QueryBuilderMetadataCatalog.Field("id", "id", Long.class, true);
                int next = relative.indexOf('.');
                if (next < 0) return null;
                String segment = relative.substring(0, next);
                var association = entity.associations().stream().filter(value -> value.name().equals(segment)).findFirst();
                if (association.isEmpty()) return null;
                entity = catalog.roots().stream().filter(value -> value.javaType() == association.get().targetType()).findFirst().orElse(null);
                relative = relative.substring(next + 1);
            }
            return null;
        }

        private static Class<?> aggregateType(VisualQueryDefinition.Aggregate aggregate, VisualQueryDefinition definition,
                                              QueryBuilderMetadataCatalog catalog, VirtualCatalog virtual) {
            String function = aggregate.function().toUpperCase();
            if (function.equals("LISTAGG")) return String.class;
            if (function.equals("COUNT") || function.equals("COUNT_ROWS")) return Long.class;
            if (function.equals("AVG")) return Double.class;
            Class<?> source = sourceType(aggregate.path(), definition, catalog, virtual);
            return function.equals("SUM") || function.equals("MIN") || function.equals("MAX") ? source : Object.class;
        }

        private static Class<?> expressionType(VisualQueryExpression expression, VisualQueryDefinition definition,
                                               QueryBuilderMetadataCatalog catalog, VirtualCatalog virtual) {
            if (expression instanceof VisualQueryExpression.Literal literal) {
                return literal.value() == null ? Object.class : literal.value().getClass();
            }
            if (expression instanceof VisualQueryExpression.FieldRef field) {
                return sourceType(field.path(), definition, catalog, virtual);
            }
            if (expression instanceof VisualQueryExpression.Binary) return Number.class;
            if (expression instanceof VisualQueryExpression.Case caseExpression
                    && !caseExpression.branches().isEmpty()) {
                return expressionType(caseExpression.branches().get(0).result(), definition, catalog, virtual);
            }
            if (expression instanceof VisualQueryExpression.FunctionCall function) {
                return switch (function.name().toUpperCase()) {
                    case "LOWER", "UPPER", "TRIM", "SUBSTRING", "CONCAT" -> String.class;
                    case "LENGTH", "YEAR", "MONTH", "DAY", "ABS", "ROUND", "MOD" -> Number.class;
                    default -> Object.class;
                };
            }
            return Object.class;
        }
    }

    /** Каталог виртуальных источников, доступных на выбранном этапе. */
    public record VirtualCatalog(List<VirtualEntity> entities) {
        public VirtualCatalog {
            entities = List.copyOf(entities == null ? List.of() : entities);
        }

        public static VirtualCatalog empty() {
            return new VirtualCatalog(List.of());
        }

        public QueryBuilderMetadataCatalog.Entity entity(String name) {
            return entities.stream().filter(item -> item.name().equals(name))
                    .findFirst().map(VirtualEntity::asCatalogEntity).orElse(null);
        }

        public boolean contains(String name) {
            return entities.stream().anyMatch(item -> item.name().equals(name));
        }

        public VirtualCatalog add(VirtualEntity entity) {
            Objects.requireNonNull(entity, "entity");
            if (contains(entity.name())) {
                throw new IllegalArgumentException("Повторное имя виртуальной сущности: " + entity.name());
            }
            var copy = new java.util.ArrayList<>(entities);
            copy.add(entity);
            return new VirtualCatalog(copy);
        }

        public VirtualCatalog add(String name, VisualQueryDefinition definition,
                                  QueryBuilderMetadataCatalog metadataCatalog) {
            return add(VirtualEntity.from(name, definition, metadataCatalog, this));
        }
    }

    private static void validateName(String name, String kind) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Недопустимое имя " + kind + ": " + name);
        }
    }

    public record Cte(String name, Source source, VisualQueryDefinition definition,
                       List<RecorderRef> selectedRecorders) {
        public Cte {
            validateName(name, "имени CTE");
            source = Objects.requireNonNull(source, "source");
            definition = Objects.requireNonNull(definition, "definition");
            selectedRecorders = List.copyOf(selectedRecorders == null ? List.of() : selectedRecorders);
            if (new java.util.HashSet<>(selectedRecorders).size() != selectedRecorders.size())
                throw new IllegalArgumentException("Повторная ссылка регистратора в CTE: " + name);
            if (!source.alias().equals(definition.entityAlias())) {
                throw new IllegalArgumentException("Alias источника не совпадает с alias определения: " + source.name());
            }
        }

        public Cte(String name, Source source, VisualQueryDefinition definition) {
            this(name, source, definition, List.of());
        }

        /** Совместимый shorthand: старое определение CTE начинается от JPA-сущности. */
        public Cte(String name, VisualQueryDefinition definition) {
            this(name, new EntitySource(definition.entityName(), definition.entityAlias()), definition);
        }

        /** Generated recorder slices carry a typed candidate selection beside the generic filter AST. */
        public Cte(String name, VisualQueryDefinition definition, List<RecorderRef> selectedRecorders) {
            this(name, new EntitySource(definition.entityName(), definition.entityAlias()), definition, selectedRecorders);
        }
    }

    public record Main(Source source, VisualQueryDefinition definition) {
        public Main {
            source = Objects.requireNonNull(source, "source");
            definition = Objects.requireNonNull(definition, "definition");
            if (!source.alias().equals(definition.entityAlias())) {
                throw new IllegalArgumentException("Alias источника не совпадает с alias определения: " + source.name());
            }
        }
    }
}
