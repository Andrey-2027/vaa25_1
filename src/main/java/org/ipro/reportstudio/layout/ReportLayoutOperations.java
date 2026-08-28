package org.ipro.reportstudio.layout;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportGroupHeaderLayout;
import org.ipro.reportstudio.dom.ReportTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Общие операции изменения layout отчёта. Класс не зависит от Vaadin и является
 * единым владельцем инвариантов групп, порядка бэндов и добавления агрегатов.
 */
public final class ReportLayoutOperations {

    private ReportLayoutOperations() {
    }

    public static ReportBand resolveScopeAt(List<ReportBand> ordered, int index) {
        Deque<ReportBand> openGroups = new ArrayDeque<>();
        int limit = Math.max(0, Math.min(index, ordered.size()));
        for (int i = 0; i < limit; i++) {
            ReportBand band = ordered.get(i);
            if (band.getKind() == ReportBandKind.GROUP_HEADER) {
                openGroups.push(band);
            } else if (band.getKind() == ReportBandKind.GROUP_FOOTER
                    && !openGroups.isEmpty()
                    && Objects.equals(openGroups.peek().getGroupField(), band.getGroupField())) {
                openGroups.pop();
            }
        }
        return openGroups.isEmpty() ? null : openGroups.peek();
    }

    public static void renumberGroupPositions(ReportTemplate template) {
        List<ReportBand> headers = template.getBands().stream()
                .filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER)
                .toList();
        if (headers.isEmpty()) {
            return;
        }
        List<ReportBand> topLevel = headers.stream()
                .filter(b -> b.getParent() == null)
                .sorted(Comparator.comparingInt(ReportBand::getPosition))
                .toList();
        int base = template.getBands().stream()
                .filter(b -> !b.getKind().isGroupBand())
                .mapToInt(ReportBand::getPosition).max().orElse(-1) + 1;
        int[] next = {base};
        for (ReportBand root : topLevel) {
            renumberGroupSubtree(template, root, headers, next, new ArrayList<>());
        }
    }

    private static void renumberGroupSubtree(ReportTemplate template, ReportBand header,
                                              List<ReportBand> allHeaders, int[] next,
                                              List<ReportBand> path) {
        if (path.contains(header)) {
            throw new IllegalArgumentException("Циклическая вложенность групп");
        }
        path.add(header);
        header.setPosition(next[0]++);
        List<ReportBand> children = allHeaders.stream()
                .filter(b -> b.getParent() == header)
                .filter(b -> b != header)
                .sorted(Comparator.comparingInt(ReportBand::getPosition))
                .toList();
        for (ReportBand child : children) {
            renumberGroupSubtree(template, child, allHeaders, next, path);
        }
        groupFooterOf(template, header).ifPresent(footer -> footer.setPosition(next[0]++));
        path.remove(header);
    }

    public static boolean reparentGroup(ReportTemplate template, ReportBand child, ReportBand newParent) {
        Objects.requireNonNull(template, "template");
        if (child == null || child.getKind() != ReportBandKind.GROUP_HEADER
                || !template.getBands().contains(child)) {
            return false;
        }
        if (newParent != null) {
            if (newParent == child || !template.getBands().contains(newParent)
                    || newParent.getKind() != ReportBandKind.GROUP_HEADER) {
                return false;
            }
            for (ReportBand current = newParent; current != null; current = current.getParent()) {
                if (current == child) {
                    return false;
                }
            }
            if (usesAncestorField(child.getGroupField(), newParent)) {
                return false;
            }
        }
        child.setParent(newParent);
        groupFooterOf(template, child).ifPresent(footer -> footer.setParent(child));
        renumberGroupPositions(template);
        return true;
    }

    public static void removeGroup(ReportTemplate template, ReportBand selected) {
        Objects.requireNonNull(template, "template");
        if (selected == null || !selected.getKind().isGroupBand()) {
            return;
        }
        ReportBand header = selected.getKind() == ReportBandKind.GROUP_HEADER
                ? selected : groupHeaderOf(template, selected.getGroupField()).orElse(null);
        if (header != null) {
            ReportBand grandParent = header.getParent();
            for (ReportBand child : List.copyOf(template.getBands())) {
                if (child.getParent() == header) {
                    child.setParent(grandParent);
                }
            }
        }
        String groupField = selected.getGroupField();
        template.getBands().removeIf(band -> band.getKind().isGroupBand()
                && Objects.equals(groupField, band.getGroupField()));
        renumberGroupPositions(template);
    }

    public static ReportBand findBand(ReportTemplate template, ReportBandKind kind) {
        return template.getBands().stream()
                .filter(band -> band.getKind() == kind)
                .findFirst().orElse(null);
    }

    public static void removeField(ReportBand band, ReportField field) {
        if (band == null || field == null || !band.getFields().remove(field)) return;
        renumberFields(band);
    }

    public static void removeBand(ReportTemplate template, ReportBand band) {
        if (template == null || band == null || band.getKind() == ReportBandKind.DETAIL) return;
        template.getBands().remove(band);
        renumberGroupPositions(template);
    }

    public static ReportField addRowNumber(ReportBand detail) {
        if (detail == null || detail.getKind() != ReportBandKind.DETAIL) {
            throw new IllegalArgumentException("Нужен detail-бэнд");
        }
        ReportField field = new ReportField();
        field.setKind(ReportFieldKind.ROW_NUMBER);
        field.setCaption("№");
        detail.addField(field);
        field.setPosition(detail.getFields().size() - 1);
        return field;
    }

    public static ReportField addComputed(ReportBand detail, ReportFieldKind kind) {
        if (detail == null || detail.getKind() != ReportBandKind.DETAIL
                || (kind != ReportFieldKind.EXPRESSION && kind != ReportFieldKind.FORMULA)) {
            throw new IllegalArgumentException("Нужен detail и вычисляемый вид поля");
        }
        ReportField field = new ReportField();
        field.setKind(kind);
        field.setCaption(kind == ReportFieldKind.EXPRESSION ? "Выражение" : "Формула");
        field.setText(kind == ReportFieldKind.EXPRESSION ? "{code}" : "({qty} * {price})");
        field.setAlignment(org.ipro.reportstudio.dom.ReportFieldAlignment.RIGHT);
        detail.addField(field);
        field.setPosition(detail.getFields().size() - 1);
        return field;
    }

    public static ReportField addColumnOrAggregate(ReportBand target, String alias,
                                                     QueryField queryField) {
        if (target == null || alias == null || alias.isBlank()
                || (target.getKind() != ReportBandKind.DETAIL && !target.getKind().isFooterBand())) {
            throw new IllegalArgumentException("Недопустимый бэнд или поле");
        }
        if (target.getKind() == ReportBandKind.DETAIL) return addDetailColumn(target, alias, queryField);
        return addFooterAggregate(target, alias, queryField, ReportFieldAggregation.NONE);
    }

    public static void addTextField(ReportBand band, String text) {
        if (band == null || (!band.getKind().isTextOnlyBand() && !band.getKind().isFooterBand())) {
            throw new IllegalArgumentException("Текстовое поле недоступно для этого бэнда");
        }
        ReportField field = new ReportField();
        field.setKind(ReportFieldKind.TEXT);
        field.setText(text);
        band.addField(field);
        field.setPosition(band.getFields().size() - 1);
    }

    public static boolean applyGrouping(ReportTemplate template, ReportBand band, String nextField,
                                        ReportBand nextParent, boolean startNewPage,
                                        Integer titleWidth, ReportGroupHeaderLayout headerLayout) {
        if (template == null || band == null || !band.getKind().isGroupBand()) return false;
        boolean header = band.getKind() == ReportBandKind.GROUP_HEADER;
        String current = band.getGroupField();
        for (ReportBand candidate : List.copyOf(template.getBands())) {
            if (candidate.getKind().isGroupBand()
                    && (candidate == band || Objects.equals(current, candidate.getGroupField()))) {
                candidate.setGroupField(nextField);
                if (header && nextParent != null) candidate.setParent(nextParent);
                if (candidate.getKind() == ReportBandKind.GROUP_HEADER) {
                    candidate.setStartNewPage(startNewPage);
                    candidate.setTitleWidth(titleWidth);
                    candidate.setHeaderLayout(headerLayout);
                }
            }
        }
        band.setParent(header ? nextParent : null);
        renumberGroupPositions(template);
        return true;
    }

    private static void renumberFields(ReportBand band) {
        for (int i = 0; i < band.getFields().size(); i++) band.getFields().get(i).setPosition(i);
    }

    public static ReportBand createBand(ReportTemplate template, ReportBandKind kind, String groupField) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(kind, "kind");
        ReportBand band = new ReportBand();
        band.setKind(kind);
        band.setGroupField(groupField);
        band.setPosition(template.getBands().stream().mapToInt(ReportBand::getPosition).max().orElse(-1) + 1);
        template.addBand(band);
        return band;
    }

    public static ReportBand addGroupPair(ReportTemplate template, String alias, ReportBand parent) {
        Objects.requireNonNull(template, "template");
        if (alias == null || alias.isBlank()) throw new IllegalArgumentException("Нужно поле группировки");
        if (template.getBands().stream().anyMatch(b -> b.getKind() == ReportBandKind.GROUP_HEADER
                && alias.equals(b.getGroupField()))) throw new IllegalArgumentException("Группа уже существует: " + alias);
        ReportBand header = createBand(template, ReportBandKind.GROUP_HEADER, alias);
        ReportBand footer = createBand(template, ReportBandKind.GROUP_FOOTER, alias);
        header.setParent(parent);
        footer.setParent(header);
        renumberGroupPositions(template);
        return header;
    }

    public static ReportBand addNestedGroup(ReportTemplate template, String alias, ReportBand target) {
        if (target == null || target.getKind() != ReportBandKind.GROUP_HEADER) {
            throw new IllegalArgumentException("Нужен заголовок родительской группы");
        }
        for (ReportBand current = target; current != null; current = current.getParent()) {
            if (Objects.equals(alias, current.getGroupField())) throw new IllegalArgumentException("Поле уже используется в предках");
        }
        ReportBand header = addGroupPair(template, alias, target);
        for (ReportBand child : List.copyOf(template.getBands())) {
            if (child.getParent() == target && child != header
                    && child.getKind() == ReportBandKind.GROUP_HEADER) child.setParent(header);
        }
        renumberGroupPositions(template);
        return header;
    }

    public static ReportBand addGroupAt(ReportTemplate template, String alias, List<ReportBand> ordered, int index) {
        Objects.requireNonNull(template, "template");
        ReportBand parent = resolveScopeAt(ordered, index);
        return addGroupPair(template, alias, parent);
    }

    public static boolean moveBand(ReportTemplate template, ReportBand band, int direction) {
        if (template == null || band == null) return false;
        int from = template.getBands().indexOf(band);
        int to = from + direction;
        if (from < 0 || to < 0 || to >= template.getBands().size()) return false;
        java.util.Collections.swap(template.getBands(), from, to);
        for (int i = 0; i < template.getBands().size(); i++) template.getBands().get(i).setPosition(i);
        return true;
    }

    public static ReportBand ensureBand(ReportTemplate template, ReportBandKind kind) {
        ReportBand existing = findBand(template, kind);
        if (existing != null) {
            return existing;
        }
        ReportBand band = new ReportBand();
        band.setKind(kind);
        band.setPosition(template.getBands().stream()
                .mapToInt(ReportBand::getPosition).max().orElse(-1) + 1);
        template.addBand(band);
        return band;
    }

    public static ReportField addDetailColumn(ReportBand detail, String alias, QueryField queryField) {
        if (detail == null || detail.getKind() != ReportBandKind.DETAIL || alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Для колонки нужны detail и поле");
        }
        if (detail.getFields().stream().anyMatch(field -> alias.equals(field.getQueryField()))) {
            throw new IllegalArgumentException("Поле уже есть в отчёте: " + alias);
        }
        ReportField field = new ReportField();
        field.setKind(ReportFieldKind.COLUMN);
        field.setQueryField(alias);
        field.setAggregation(ReportFieldAggregation.NONE);
        detail.addField(field);
        field.setPosition(detail.getFields().size() - 1);
        return field;
    }

    public static ReportBand ensureGroupFooter(ReportTemplate template, ReportBand groupHeader) {
        Objects.requireNonNull(template, "template");
        if (groupHeader == null || groupHeader.getKind() != ReportBandKind.GROUP_HEADER) {
            throw new IllegalArgumentException("Нужен заголовок группы");
        }
        return groupFooterOf(template, groupHeader).orElseThrow(() ->
                new IllegalArgumentException("Для группы не найден footer"));
    }

    public static void addOrder(ReportTemplate template, String columnName, org.ipro.reportstudio.dom.ReportOrderDirection direction) {
        Objects.requireNonNull(template, "template");
        if (columnName == null || columnName.isBlank()) throw new IllegalArgumentException("Нужно поле сортировки");
        if (template.getOrders().stream().anyMatch(order -> columnName.equals(order.getColumnName()))) {
            throw new IllegalArgumentException("Поле уже участвует в сортировке: " + columnName);
        }
        org.ipro.reportstudio.dom.ReportOrder order = new org.ipro.reportstudio.dom.ReportOrder();
        order.setColumnName(columnName);
        order.setDirection(direction == null ? org.ipro.reportstudio.dom.ReportOrderDirection.ASC : direction);
        order.setPosition(template.getOrders().size());
        template.addOrder(order);
    }

    /** Перемещает правило сортировки и нормализует его позиции. */
    public static void moveOrder(ReportTemplate template, org.ipro.reportstudio.dom.ReportOrder order, int delta) {
        if (template == null || order == null) return;
        int from = template.getOrders().indexOf(order);
        int to = Math.max(0, Math.min(template.getOrders().size() - 1, from + delta));
        if (from < 0 || from == to) return;
        java.util.Collections.swap(template.getOrders(), from, to);
        for (int i = 0; i < template.getOrders().size(); i++) template.getOrders().get(i).setPosition(i);
    }

    public static void removeOrder(ReportTemplate template, org.ipro.reportstudio.dom.ReportOrder order) {
        if (template == null || order == null) return;
        template.getOrders().remove(order);
        for (int i = 0; i < template.getOrders().size(); i++) template.getOrders().get(i).setPosition(i);
    }

    /** Изменяет только свойства, которыми управляет пользовательский режим. */
    public static void updateFieldProperties(ReportField field, String caption, boolean visible,
                                              Integer width, org.ipro.reportstudio.dom.ReportFieldAlignment alignment,
                                              String format, Boolean border) {
        Objects.requireNonNull(field, "field");
        field.setCaption(caption == null || caption.isBlank() ? null : caption.trim());
        field.setVisible(visible);
        field.setWidth(width);
        field.setAlignment(alignment);
        field.setFormat(format == null || format.isBlank() ? null : format.trim());
        field.setBorder(border);
    }

    public static void moveField(ReportBand band, ReportField field, int delta) {
        if (band == null || field == null || !band.getFields().contains(field) || delta == 0) return;
        int from = band.getFields().indexOf(field);
        int to = Math.max(0, Math.min(band.getFields().size() - 1, from + delta));
        if (from == to) return;
        band.getFields().remove(from);
        band.getFields().add(to, field);
        for (int i = 0; i < band.getFields().size(); i++) band.getFields().get(i).setPosition(i);
    }

    public static ReportField addFooterAggregate(ReportBand footer, String alias,
                                                  QueryField queryField,
                                                  ReportFieldAggregation requested) {
        if (footer == null || !footer.getKind().isFooterBand() || alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Для агрегата нужны footer и поле");
        }
        if (requested != ReportFieldAggregation.COUNT_ROWS
                && footer.getFields().stream().anyMatch(field -> alias.equals(field.getQueryField()))) {
            throw new IllegalArgumentException("Поле уже есть в footer: " + alias);
        }
        ReportField field = new ReportField();
        field.setKind(ReportFieldKind.COLUMN);
        field.setQueryField(requested == ReportFieldAggregation.COUNT_ROWS ? "" : alias);
        field.setAggregation(safeAggregation(queryField, requested));
        footer.addField(field);
        field.setPosition(footer.getFields().size() - 1);
        return field;
    }

    /** Заменяет исчезнувший alias на новый во всех ссылках layout. */
    public static int replaceFieldReference(ReportTemplate template, String oldAlias, String newAlias) {
        Objects.requireNonNull(template, "template");
        if (oldAlias == null || oldAlias.isBlank() || newAlias == null || newAlias.isBlank()) {
            throw new IllegalArgumentException("Нужны старое и новое поле");
        }
        if (oldAlias.equals(newAlias)) return 0;
        int changes = 0;
        for (ReportBand band : template.getBands()) {
            if (oldAlias.equals(band.getGroupField())) {
                band.setGroupField(newAlias);
                changes++;
            }
            for (ReportField field : band.getFields()) {
                if (oldAlias.equals(field.getQueryField())) {
                    field.setQueryField(newAlias);
                    changes++;
                }
            }
        }
        for (org.ipro.reportstudio.dom.ReportOrder order : template.getOrders()) {
            if (oldAlias.equals(order.getColumnName())) {
                order.setColumnName(newAlias);
                changes++;
            }
        }
        return changes;
    }

    public static void removeMissingFields(ReportTemplate template, List<String> missing) {
        if (template == null || missing == null || missing.isEmpty()) return;
        for (ReportBand band : List.copyOf(template.getBands())) {
            if (missing.contains(band.getGroupField())) band.setGroupField(null);
            band.getFields().removeIf(field -> missing.contains(field.getQueryField()));
        }
        template.getOrders().removeIf(order -> missing.contains(order.getColumnName()));
        for (int i = 0; i < template.getOrders().size(); i++) template.getOrders().get(i).setPosition(i);
    }

    public static ReportFieldAggregation safeAggregation(QueryField queryField,
                                                         ReportFieldAggregation requested) {
        if (queryField != null && !queryField.aggregatable()
                && requested != ReportFieldAggregation.COUNT
                && requested != ReportFieldAggregation.COUNT_ROWS) {
            return ReportFieldAggregation.COUNT;
        }
        return requested == null || requested == ReportFieldAggregation.NONE
                ? queryField != null && queryField.aggregatable()
                    ? ReportFieldAggregation.SUM : ReportFieldAggregation.COUNT
                : requested;
    }

    private static boolean usesAncestorField(String field, ReportBand target) {
        for (ReportBand current = target; current != null; current = current.getParent()) {
            if (Objects.equals(field, current.getGroupField())) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Optional<ReportBand> groupHeaderOf(ReportTemplate template, String field) {
        return template.getBands().stream()
                .filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER)
                .filter(b -> Objects.equals(field, b.getGroupField()))
                .findFirst();
    }

    private static java.util.Optional<ReportBand> groupFooterOf(ReportTemplate template, ReportBand header) {
        return template.getBands().stream()
                .filter(b -> b.getKind() == ReportBandKind.GROUP_FOOTER)
                .filter(b -> b.getParent() == header
                        || (b.getParent() == null && Objects.equals(b.getGroupField(), header.getGroupField())))
                .findFirst();
    }
}
