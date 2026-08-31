package org.ipro.reportstudio.query;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Пакет запросов: упорядоченные нерекурсивные CTE и итоговый запрос. */
public record VisualQueryPackage(int version, List<Cte> ctes, VisualQueryDefinition main) {
    public static final int CURRENT_VERSION = 1;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public VisualQueryPackage(List<Cte> ctes, VisualQueryDefinition main) {
        this(CURRENT_VERSION, ctes, main);
    }

    public VisualQueryPackage {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Неподдерживаемая версия пакета visual query: " + version);
        }
        ctes = List.copyOf(ctes == null ? List.of() : ctes);
        main = Objects.requireNonNull(main, "main");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < ctes.size(); i++) {
            Cte cte = Objects.requireNonNull(ctes.get(i), "ctes[" + i + "]");
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
                fields.add(new QueryBuilderMetadataCatalog.Field(selected.resultName(), selected.resultName(),
                        sourceType(selected.path(), definition.entityAlias(), root), false));
            }
            for (VisualQueryDefinition.Aggregate aggregate : definition.aggregates()) {
                fields.add(new QueryBuilderMetadataCatalog.Field(aggregate.resultName(), aggregate.resultName(),
                        aggregateType(aggregate, definition.entityAlias(), root), false));
            }
            for (VisualQueryDefinition.Expression expression : definition.expressions()) {
                fields.add(new QueryBuilderMetadataCatalog.Field(expression.resultName(), expression.resultName(),
                        expressionType(expression.expression(), definition.entityAlias(), root), false));
            }
            return new VirtualEntity(name, fields);
        }

        private static Class<?> sourceType(String path, String alias,
                                           QueryBuilderMetadataCatalog.Entity root) {
            String relative = path.startsWith(alias + ".") ? path.substring(alias.length() + 1) : path;
            String[] segments = relative.split("\\.");
            String last = segments[segments.length - 1];
            // Системный идентификатор (в т.ч. в конце dotted-traversal «m.prdSpec.id») — Long.
            if ("id".equals(last)) return Long.class;
            if (segments.length > 1) {
                // Глубокий путь к полю через ассоциации: тип ищем на корне по последнему сегменту,
                // иначе точный тип недоступен без прохода по каталогу.
                return root.fields().stream().filter(field -> field.name().equals(last))
                        .map(QueryBuilderMetadataCatalog.Field::javaType).findFirst().orElse(Object.class);
            }
            return root.fields().stream().filter(field -> field.name().equals(last))
                    .map(QueryBuilderMetadataCatalog.Field::javaType).findFirst().orElse(Object.class);
        }

        private static Class<?> aggregateType(VisualQueryDefinition.Aggregate aggregate, String alias,
                                              QueryBuilderMetadataCatalog.Entity root) {
            String function = aggregate.function().toUpperCase();
            if (function.equals("COUNT") || function.equals("COUNT_ROWS")) return Long.class;
            if (function.equals("AVG")) return Double.class;
            Class<?> source = sourceType(aggregate.path(), alias, root);
            return function.equals("SUM") || function.equals("MIN") || function.equals("MAX") ? source : Object.class;
        }

        private static Class<?> expressionType(VisualQueryExpression expression, String alias,
                                               QueryBuilderMetadataCatalog.Entity root) {
            if (expression instanceof VisualQueryExpression.Literal literal) {
                return literal.value() == null ? Object.class : literal.value().getClass();
            }
            if (expression instanceof VisualQueryExpression.FieldRef field) {
                return sourceType(field.path(), alias, root);
            }
            if (expression instanceof VisualQueryExpression.Binary) return Number.class;
            if (expression instanceof VisualQueryExpression.Case caseExpression
                    && !caseExpression.branches().isEmpty()) {
                return expressionType(caseExpression.branches().get(0).result(), alias, root);
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

    public record Cte(String name, Source source, VisualQueryDefinition definition) {
        public Cte {
            validateName(name, "имени CTE");
            source = Objects.requireNonNull(source, "source");
            definition = Objects.requireNonNull(definition, "definition");
            if (!source.alias().equals(definition.entityAlias())) {
                throw new IllegalArgumentException("Alias источника не совпадает с alias определения: " + source.name());
            }
        }

        /** Совместимый shorthand: старое определение CTE начинается от JPA-сущности. */
        public Cte(String name, VisualQueryDefinition definition) {
            this(name, new EntitySource(definition.entityName(), definition.entityAlias()), definition);
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
