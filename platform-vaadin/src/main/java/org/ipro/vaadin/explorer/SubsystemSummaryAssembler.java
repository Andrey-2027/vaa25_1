package org.ipro.vaadin.explorer;

import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.SubsystemNode;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only агрегатор «Структура подсистем» — обратное направление Entity Explorer:
 * подсистема → её сущности и грани. Только чтение (никаких {@code register*} и мутаций);
 * замкнутый словарь (П1) — готовые факты, без Object payload и Vaadin-типов.
 *
 * <p>Единственное место знания о том, «что входит в строку каталога». Грани строки:
 * эффективное наименование ({@link EntitySummaryAssembler}, тот же путь резолюции
 * «код ← переопределение»), нумерация, наличие кастомных регистраций форм/наборов
 * ({@link FormRegistry#registrationsOf}), наличие контекст-фильтров и RLS-гейт
 * {@code ENTITY:<Класс>} (CHECK_ONLY) — как в {@code SubsystemHomeView}.</p>
 */
public class SubsystemSummaryAssembler {

    /** Весь каталог: дерево подсистем (включая пустые) + сущности без подсистемы. */
    public record Catalog(
            List<SubsystemRef> subsystems,
            List<EntityFacet> unassigned) {

        public Catalog {
            subsystems = List.copyOf(subsystems);
            unassigned = List.copyOf(unassigned);
        }
    }

    /** Строка дерева: подсистема с числом сущностей всего поддерева (пустая — {@code 0}). */
    public record SubsystemRef(
            Class<?> markerClass,
            String title,
            String path,
            int depth,
            int entityCount) {
    }

    /** Строка сущности в каталоге: подсистема-владелец, наименование и грани. */
    public record EntityFacet(
            Class<?> entityClass,
            String simpleName,
            ResolvedValue displayName,
            String numbering,
            boolean customForms,
            boolean contextFilters,
            boolean rlsGate) {
    }

    /** Группа строк каталога: одна подсистема и её собственные сущности. */
    public record Group(
            Class<?> markerClass,
            String title,
            String path,
            List<EntityFacet> entities) {

        public Group {
            entities = List.copyOf(entities);
        }
    }

    private final EntitySummaryAssembler entityAssembler;
    private final SubsystemRegistry subsystemRegistry;
    private final RlsDimensionRegistry rlsDimensionRegistry;

    public SubsystemSummaryAssembler(
            EntitySummaryAssembler entityAssembler,
            SubsystemRegistry subsystemRegistry,
            RlsDimensionRegistry rlsDimensionRegistry) {
        this.entityAssembler = entityAssembler;
        this.subsystemRegistry = subsystemRegistry;
        this.rlsDimensionRegistry = rlsDimensionRegistry;
    }

    /**
     * Весь каталог: подсистемы в pre-order (корни по порядку, дети рекурсивно),
     * сущности без подсистемы ({@code NoSubsystem}) — отдельным списком. Детерминированно (П4).
     */
    public Catalog catalog() {
        List<SubsystemRef> refs = new ArrayList<>();
        for (SubsystemNode root : subsystemRegistry.getRoots()) {
            collectNode(root, 0, "", refs);
        }

        List<EntityFacet> unassigned = new ArrayList<>();
        for (EntitySummaryAssembler.EntityRef ref : entityAssembler.entities()) {
            EntityMetadata annotation = ref.entityClass().getAnnotation(EntityMetadata.class);
            if (annotation != null && annotation.subsystem() == Subsystem.NoSubsystem.class) {
                unassigned.add(facetOf(ref.entityClass()));
            }
        }
        return new Catalog(refs, unassigned);
    }

    /**
     * Группы сущностей подсистемы: собственная группа узла и рекурсивно группы детей
     * (пустые узлы пропускаются — как {@code SubsystemNode.getEntityGroupsRecursive()}).
     *
     * @param markerClass маркер {@code @Subsystem}; {@code null} — сущности без подсистемы
     */
    public List<Group> groupsOf(Class<?> markerClass) {
        if (markerClass == null) {
            List<EntityFacet> unassigned = catalog().unassigned();
            return List.of(new Group(null, "Без подсистемы", "Без подсистемы", unassigned));
        }
        SubsystemNode node = subsystemRegistry.findByMarker(markerClass)
            .orElseThrow(() -> new IllegalArgumentException(
                "Неизвестная подсистема: " + markerClass.getName() +
                " — маркер не аннотирован @Subsystem (или вне base package)."));
        List<Group> groups = new ArrayList<>();
        collectGroup(node, groups);
        return groups;
    }

    private void collectNode(SubsystemNode node, int depth, String parentPath,
                             List<SubsystemRef> out) {
        String path = parentPath.isEmpty()
            ? node.getTitle()
            : parentPath + " / " + node.getTitle();
        out.add(new SubsystemRef(node.getMarkerClass(), node.getTitle(), path, depth,
            entityCountOf(node)));
        for (SubsystemNode child : node.getChildren()) {
            collectNode(child, depth + 1, path, out);
        }
    }

    private int entityCountOf(SubsystemNode node) {
        int count = node.getOwnEntities().size();
        for (SubsystemNode child : node.getChildren()) {
            count += entityCountOf(child);
        }
        return count;
    }

    private void collectGroup(SubsystemNode node, List<Group> out) {
        if (!node.getOwnEntities().isEmpty()) {
            out.add(toGroup(node));
        }
        for (SubsystemNode child : node.getChildren()) {
            collectGroup(child, out);
        }
    }

    private Group toGroup(SubsystemNode node) {
        List<EntityFacet> facets = new ArrayList<>();
        for (var entity : node.getOwnEntities()) {
            facets.add(facetOf(entity.getEntityClass()));
        }
        // Собственные сущности узла уже отсортированы по @EntityMetadata.order (SubsystemRegistry).
        return new Group(node.getMarkerClass(), node.getTitle(), pathOf(node), facets);
    }

    private static String pathOf(SubsystemNode node) {
        List<String> titles = new ArrayList<>();
        for (SubsystemNode current = node; current != null; current = current.getParent()) {
            titles.add(0, current.getTitle());
        }
        return String.join(" / ", titles);
    }

    private EntityFacet facetOf(Class<?> entityClass) {
        EntitySummary summary = entityAssembler.summarize(entityClass);
        String numbering = summary.numbering().stream()
            .map(row -> {
                String period = row.period();
                String tail = (period == null || period.isBlank() || "NEVER".equals(period))
                    ? row.scope()
                    : row.scope() + ", " + period;
                return row.fieldName() + " (" + tail + ")";
            })
            .collect(Collectors.joining(", "));
        boolean customForms = summary.forms().stream()
            .anyMatch(row -> !row.platformDefault());
        boolean filters = !summary.contextFilters().isEmpty();
        boolean rlsGate = hasEntityGate(entityClass.getSimpleName());
        return new EntityFacet(
            entityClass,
            entityClass.getSimpleName(),
            summary.displayName(),
            numbering,
            customForms,
            filters,
            rlsGate);
    }

    /** RLS-гейт вида {@code ENTITY:<ИмяКласса>} CHECK_ONLY — та же конвенция, что в SubsystemHomeView. */
    private boolean hasEntityGate(String simpleName) {
        String dimension = "ENTITY:" + simpleName;
        return rlsDimensionRegistry.dimensions().contains(dimension)
            && rlsDimensionRegistry.kindOf(dimension) == RlsDimensionKind.CHECK_ONLY;
    }
}
