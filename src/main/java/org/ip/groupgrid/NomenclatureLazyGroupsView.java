package org.ip.groupgrid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;
import jakarta.persistence.EntityManager;
import org.ip.model.Nomenclature;

/**
 * Демо ленивой группировки на TreeGrid: группы = единицы измерения,
 * дети (номенклатура) подгружаются из БД страницами по мере прокрутки.
 *
 * Корневой запрос: агрегат по группам (1 запрос при открытии).
 * Развертывание группы: SELECT ... WHERE unit_id = ? ORDER BY code LIMIT/OFFSET.
 *
 * Доступ: /nomenclature-lazy-groups
 */
@Route("nomenclature-lazy-groups")
@PageTitle("Nomenclature Lazy Groups")
@PermitAll
public class NomenclatureLazyGroupsView extends VerticalLayout {

    /**
     * Узел дерева: либо группа (item == null), либо запись номенклатуры.
     */
    public record NomNode(Long unitId, String groupTitle, long groupTotal, Nomenclature item) {

        public static NomNode group(Long unitId, String title, long total) {
            return new NomNode(unitId, title, total, null);
        }

        public static NomNode leaf(Nomenclature item) {
            return new NomNode(null, null, 0, item);
        }

        public boolean isGroup() {
            return item == null;
        }
    }

    private final transient EntityManager entityManager;
    private final TreeGrid<NomNode> grid = new TreeGrid<>();
    private final List<NomNode> groups = new ArrayList<>();
    private final Map<Long, Long> groupCounts = new HashMap<>();

    public NomenclatureLazyGroupsView(EntityManager entityManager) {
        this.entityManager = entityManager;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H2("TreeGrid — ленивая группировка номенклатуры по ед. изм."));
        add(new Paragraph(
                "Группы загружаются одним агрегирующим запросом. Записи внутри группы "
                + "берутся из БД порциями по мере прокрутки (LIMIT/OFFSET), "
                + "вся таблица может содержать миллионы строк."));

        grid.setSizeFull();

        grid.addHierarchyColumn(this::displayName)
                .setHeader("Наименование / группа")
                .setFlexGrow(1);
        grid.addColumn(node -> node.isGroup() ? "" : node.item().getCode())
                .setHeader("Код")
                .setWidth("150px")
                .setFlexGrow(0);
        grid.addColumn(node -> node.isGroup()
                        ? ""
                        : node.item().getUnitOfMeasurement().getShortCode())
                .setHeader("Ед. изм.")
                .setWidth("120px")
                .setFlexGrow(0);

        loadGroups();
        grid.setDataProvider(new LazyHierarchyProvider());

        setFlexGrow(1.0, grid);
        add(grid);
    }

    private String displayName(NomNode node) {
        return node.isGroup()
                ? node.groupTitle() + " (" + node.groupTotal() + ")"
                : node.item().getName();
    }

    private void loadGroups() {
        List<Object[]> rows = entityManager.createQuery(
                        "SELECT u.id, u.shortCode, COUNT(n.id) "
                        + "FROM Nomenclature n JOIN n.unitOfMeasurement u "
                        + "GROUP BY u.id, u.shortCode "
                        + "ORDER BY u.shortCode",
                        Object[].class)
                .getResultList();
        groups.clear();
        groupCounts.clear();
        for (Object[] row : rows) {
            Long unitId = (Long) row[0];
            String shortCode = (String) row[1];
            long count = (Long) row[2];
            groups.add(NomNode.group(unitId, shortCode, count));
            groupCounts.put(unitId, count);
        }
    }

    private List<NomNode> fetchItemsPage(Long unitId, int offset, int limit) {
        List<Nomenclature> items = entityManager.createQuery(
                        "SELECT n FROM Nomenclature n "
                        + "JOIN FETCH n.unitOfMeasurement "
                        + "WHERE n.unitOfMeasurement.id = :unitId "
                        + "ORDER BY n.code",
                        Nomenclature.class)
                .setParameter("unitId", unitId)
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
        return items.stream().map(NomNode::leaf).collect(Collectors.toList());
    }

    private class LazyHierarchyProvider
            extends AbstractBackEndHierarchicalDataProvider<NomNode, Void> {

        @Override
        public boolean hasChildren(NomNode node) {
            return node != null && node.isGroup();
        }

        @Override
        public int getChildCount(HierarchicalQuery<NomNode, Void> query) {
            NomNode parent = query.getParent();
            if (parent == null) {
                return groups.size();
            }
            return groupCounts.getOrDefault(parent.unitId(), 0L).intValue();
        }

        @Override
        protected Stream<NomNode> fetchChildrenFromBackEnd(
                HierarchicalQuery<NomNode, Void> query) {
            NomNode parent = query.getParent();
            if (parent == null) {
                int from = Math.min(query.getOffset(), groups.size());
                int to = Math.min(query.getOffset() + query.getLimit(), groups.size());
                return groups.subList(from, to).stream();
            }
            return fetchItemsPage(parent.unitId(), query.getOffset(), query.getLimit()).stream();
        }

        @Override
        public boolean isInMemory() {
            return false;
        }
    }
}
