package org.ip.views.reportstudio;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterTreeEditor;
import org.ipro.filtergrid.filter.FilterTreeJson;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportOrder;
import org.ipro.reportstudio.dom.ReportOrderDirection;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.layout.ReportLayoutOperations;
import org.ipro.reportstudio.query.QueryFieldFilterFieldResolver;

import java.util.List;
import java.util.Objects;

/**
 * Простой пользовательский режим вкладки «Макет»: поля, группировки с итогами, сортировка,
 * визуальный отбор и свойства выбранного поля.
 *
 * <p><b>Откуда он взялся.</b> Это представление жило внутри variant-вьюхи (D3.6.4). Здесь оно
 * вынесено в самостоятельный компонент, который знает только про {@link Context} и не имеет
 * доступа ни к вьюхе, ни к каноническому редактору структуры. Так у режимов один владелец
 * представлений (каноническая вьюха), а не два.</p>
 *
 * <p><b>Что компонент не делает.</b> Он не меняет модель сам: каждое действие идёт через
 * {@link ReportLayoutOperations}, а уведомление владельца — единственным вызовом
 * {@link Context#modelChanged()}. Программное обновление ({@link #refresh()}) владельца
 * не уведомляет: загрузка шаблона и публикация схемы правкой не считаются.</p>
 *
 * <p><b>Две правки относительно варианта</b> (обе — перенос отклонённой операции):
 * неудачное добавление поля и невалидный визуальный отбор больше не отмечают отчёт
 * изменённым, потому что модель в этих случаях не менялась.</p>
 */
class ReportUserLayoutEditor extends VerticalLayout {

    /**
     * Текст предупреждения о настройках, переживших смену запроса. Перенесён из variant-вьюхи
     * без изменения: там он был частью простого режима и жил над списками.
     */
    static final String ORPHANED_CHANGES_MESSAGE =
            "В запросе изменились поля. Недоступные настройки отмечены в списках; "
                    + "их можно удалить через окно проверки запроса.";

    /** Всё, что режиму нужно от владельца: модель, схема, отбор и два уведомления. */
    interface Context {

        ReportTemplate template();

        List<QueryField> schemaFields();

        String visualFilterJson();

        void visualFilterJson(String json);

        /** Создание пары групп: инвариант пары принадлежит редактору структуры, не режиму. */
        void addGroupPair(String fieldName);

        /** Пользователь изменил макет: владелец решает про dirty и синхронизацию ADVANCED. */
        void modelChanged();

        void notify(String message);
    }

    private final Context context;

    private ComboBox<QueryField> userField;
    private ComboBox<QueryField> userGroupField;
    private ComboBox<QueryField> userTotalField;
    private ComboBox<ReportFieldAggregation> userTotalAggregation;
    private ComboBox<ReportBand> userGroupTotalGroup;
    private Grid<ReportField> userFieldsGrid;
    private Grid<ReportBand> userGroupsGrid;
    private Grid<ReportField> userTotalsGrid;
    private ComboBox<QueryField> userSortField;
    private ComboBox<ReportOrderDirection> userSortDirection;
    private Grid<ReportOrder> userSortGrid;
    private TextField userCaption;
    private IntegerField userWidth;
    private ComboBox<ReportFieldAlignment> userAlignment;
    private TextField userFormat;
    private Checkbox userVisible;
    private Checkbox userBorder;
    private ReportField selectedUserField;
    private Span orphanedChangesNotice;
    private Details filtersSection;
    private Div filterHost;
    private FilterTreeEditor userFilterEditor;

    /**
     * Подпись схемы, с которой построен редактор отбора. Резолвер библиотеки захватывает список
     * полей в конструкторе, поэтому при смене схемы редактор отбора пересобирается: иначе после
     * применения запроса в отборе остались бы поля прежнего запроса.
     */
    private String filterSchemaSignature = "";

    ReportUserLayoutEditor(Context context) {
        this.context = Objects.requireNonNull(context, "context");
        setPadding(true);
        setSpacing(true);
        setWidthFull();
        setHeightFull();
        getStyle().set("overflow", "auto");
        build();
        refresh();
    }

    // ------------------------------------------------------------ сборка

    private void build() {
        Span title = new Span("Настройка макета отчёта");
        title.getStyle().set("font-size", "var(--lumo-font-size-l)")
                .set("font-weight", "600");
        Span intro = new Span("Здесь можно выбрать поля, группировки, итоги и сортировку. "
                + "Все изменения сохраняются в том же отчёте, что и в расширенном режиме.");
        intro.getStyle().set("color", "var(--lumo-secondary-text-color)");

        add(title, intro, fieldsSection(), groupsSection(), filtersSection(),
                sortingSection(), appearanceSection(), pageHintSection(), expertNote());
    }

    private Details fieldsSection() {
        userField = new ComboBox<>("Поле");
        userField.setItemLabelGenerator(this::userFieldLabel);
        userField.setWidthFull();
        Button addField = new Button("Добавить поле", event -> addUserField());
        HorizontalLayout fieldRow = new HorizontalLayout(userField, addField);
        fieldRow.setWidthFull();
        fieldRow.setAlignItems(Alignment.END);

        userFieldsGrid = new Grid<>(ReportField.class, false);
        userFieldsGrid.addColumn(field -> {
                    QueryField queryField = findQueryField(field.getQueryField());
                    return queryField == null ? "Недоступно: " + field.getQueryField() : userFieldLabel(queryField);
                }).setHeader("Поле").setAutoWidth(true);
        userFieldsGrid.addColumn(field -> Objects.requireNonNullElse(field.getCaption(), "По умолчанию"))
                .setHeader("Заголовок").setAutoWidth(true);
        userFieldsGrid.addColumn(field -> field.isVisible() ? "Да" : "Нет")
                .setHeader("Видно").setAutoWidth(true);
        userFieldsGrid.addItemClickListener(event -> selectUserField(event.getItem()));
        userFieldsGrid.addComponentColumn(field -> {
            HorizontalLayout actions = new HorizontalLayout(
                    new Button("↑", event -> moveUserField(field, -1)),
                    new Button("↓", event -> moveUserField(field, 1)),
                    new Button("Удалить", event -> removeUserField(field)));
            actions.setSpacing(false);
            return actions;
        }).setHeader("").setFlexGrow(0);
        userFieldsGrid.setHeight("150px");

        userCaption = new TextField("Заголовок");
        userWidth = new IntegerField("Ширина, px");
        userWidth.setMin(1);
        userAlignment = new ComboBox<>("Выравнивание");
        userAlignment.setItems(ReportFieldAlignment.values());
        userAlignment.setItemLabelGenerator(this::alignmentLabel);
        userFormat = new TextField("Формат");
        userVisible = new Checkbox("Показывать");
        userBorder = new Checkbox("Граница");
        Button applyProperties = new Button("Применить свойства", event -> applyUserFieldProperties());
        HorizontalLayout propertyRow = new HorizontalLayout(userCaption, userWidth, userAlignment,
                userFormat, userVisible, userBorder, applyProperties);
        propertyRow.setWidthFull();
        propertyRow.setWrap(true);
        propertyRow.setAlignItems(Alignment.END);
        Details properties = new Details("Свойства выбранного поля", propertyRow);
        properties.setOpened(true);

        Details fields = new Details("Поля отчёта",
                new VerticalLayout(fieldRow, userFieldsGrid, properties));
        fields.setOpened(true);
        return fields;
    }

    private Details groupsSection() {
        userGroupField = new ComboBox<>("Группировать по");
        userGroupField.setItemLabelGenerator(this::userFieldLabel);
        userGroupField.setWidthFull();
        Button addGroup = new Button("Добавить группировку", event -> addUserGroup());
        HorizontalLayout groupRow = new HorizontalLayout(userGroupField, addGroup);
        groupRow.setWidthFull();
        groupRow.setAlignItems(Alignment.END);

        userGroupTotalGroup = new ComboBox<>("Группа для итога");
        userGroupTotalGroup.setItemLabelGenerator(band -> userFieldLabel(findQueryField(band.getGroupField())));
        userGroupTotalGroup.setWidthFull();
        userTotalField = new ComboBox<>("Поле итога");
        userTotalField.setItemLabelGenerator(this::userFieldLabel);
        userTotalField.setWidthFull();
        userTotalAggregation = new ComboBox<>("Итог");
        userTotalAggregation.setItems(ReportFieldAggregation.SUM, ReportFieldAggregation.COUNT,
                ReportFieldAggregation.COUNT_ROWS, ReportFieldAggregation.AVG,
                ReportFieldAggregation.MIN, ReportFieldAggregation.MAX);
        userTotalAggregation.setItemLabelGenerator(this::aggregationLabel);
        userTotalAggregation.setValue(ReportFieldAggregation.SUM);
        Button addGroupTotal = new Button("Добавить итог группы", event -> addUserGroupTotal());
        Button addTotal = new Button("Добавить общий итог", event -> addUserTotal());
        HorizontalLayout totalRow = new HorizontalLayout(userTotalField, userTotalAggregation, addTotal);
        totalRow.setWidthFull();
        totalRow.setAlignItems(Alignment.END);

        userGroupsGrid = new Grid<>(ReportBand.class, false);
        userGroupsGrid.addColumn(band -> {
                    QueryField queryField = findQueryField(band.getGroupField());
                    return queryField == null ? "Недоступно: " + band.getGroupField() : userFieldLabel(queryField);
                })
                .setHeader("Группировка").setAutoWidth(true);
        userGroupsGrid.addComponentColumn(band -> new Button("Удалить", event -> removeUserGroup(band)))
                .setHeader("").setFlexGrow(0);
        userGroupsGrid.setHeight("150px");

        userTotalsGrid = new Grid<>(ReportField.class, false);
        userTotalsGrid.addColumn(field -> {
                    String target = aggregateTargetLabel(field);
                    return target == null ? aggregationLabel(field.getAggregation())
                            : aggregationLabel(field.getAggregation()) + " · " + target;
                })
                .setHeader("Итог").setAutoWidth(true);
        userTotalsGrid.addComponentColumn(field -> new Button("Удалить", event -> removeUserTotal(field)))
                .setHeader("").setFlexGrow(0);
        userTotalsGrid.setHeight("150px");

        HorizontalLayout groupTotalRow = new HorizontalLayout(userGroupTotalGroup,
                userTotalField, userTotalAggregation, addGroupTotal);
        groupTotalRow.setWidthFull();
        groupTotalRow.setAlignItems(Alignment.END);

        Details groups = new Details("Группировки и итоги", new VerticalLayout(
                groupRow, userGroupsGrid, groupTotalRow, totalRow, userTotalsGrid));
        groups.setOpened(true);
        return groups;
    }

    private Details filtersSection() {
        // Редактор отбора пересобирается при смене схемы, поэтому живёт в контейнере.
        filterHost = new Div();
        filterHost.setWidthFull();
        filtersSection = new Details("Отбор данных", filterHost);
        filtersSection.setOpened(true);
        return filtersSection;
    }

    private Details sortingSection() {
        userSortField = new ComboBox<>("Поле сортировки");
        userSortField.setItemLabelGenerator(this::userFieldLabel);
        userSortField.setWidthFull();
        userSortDirection = new ComboBox<>("Направление");
        userSortDirection.setItems(ReportOrderDirection.values());
        userSortDirection.setItemLabelGenerator(direction ->
                direction == ReportOrderDirection.DESC ? "По убыванию" : "По возрастанию");
        userSortDirection.setValue(ReportOrderDirection.ASC);
        Button addSort = new Button("Добавить сортировку", event -> addUserSort());

        userSortGrid = new Grid<>(ReportOrder.class, false);
        userSortGrid.addColumn(order -> {
                    QueryField queryField = findQueryField(order.getColumnName());
                    return (queryField == null ? "Недоступно: " + order.getColumnName() : userFieldLabel(queryField))
                            + " · "
                            + (order.directionOrDefault() == ReportOrderDirection.DESC
                                    ? "По убыванию" : "По возрастанию");
                })
                .setHeader("Сортировка").setAutoWidth(true);
        userSortGrid.addComponentColumn(order -> {
            HorizontalLayout actions = new HorizontalLayout(
                    new Button("↑", event -> moveUserSort(order, -1)),
                    new Button("↓", event -> moveUserSort(order, 1)),
                    new Button("Удалить", event -> removeUserSort(order)));
            actions.setSpacing(false);
            return actions;
        }).setHeader("").setFlexGrow(0);
        userSortGrid.setHeight("150px");
        userSortGrid.setEmptyStateText("Нет правил сортировки");

        HorizontalLayout sortRow = new HorizontalLayout(userSortField, userSortDirection, addSort);
        sortRow.setWidthFull();
        sortRow.setAlignItems(Alignment.END);
        Details sorting = new Details("Сортировка", new VerticalLayout(sortRow, userSortGrid));
        sorting.setOpened(true);
        return sorting;
    }

    private Details appearanceSection() {
        Details appearance = new Details("Оформление колонок", new Span(
                "Выберите поле в списке выше, чтобы изменить заголовок, видимость, ширину, "
                        + "выравнивание, формат и границы."));
        appearance.setOpened(true);
        return appearance;
    }

    private Details pageHintSection() {
        Details pageHint = new Details("Настройки страницы", new Span(
                "Наименование, формат, ориентация и шапка/подвал страницы находятся на вкладке «Страница»."));
        pageHint.setOpened(false);
        return pageHint;
    }

    private Span expertNote() {
        Span expert = new Span("Расширенный режим сохраняет полный контроль: формулы, alias, "
                + "вложенные группы и технические настройки.");
        expert.getStyle().set("color", "var(--lumo-secondary-text-color)");
        return expert;
    }

    // ------------------------------------------------------------ обновление

    /**
     * Перечитывает модель и схему. Вызывается владельцем после загрузки шаблона, публикации
     * схемы и любой правки макета. Пользовательское состояние выбора сбрасывается, если поле
     * выбора исчезло из шаблона, — иначе свойства правились бы у поля чужого отчёта.
     */
    void refresh() {
        ReportTemplate template = context.template();
        List<QueryField> schema = context.schemaFields();
        userField.setItems(schema);
        userGroupField.setItems(schema);
        userTotalField.setItems(schema);
        userSortField.setItems(schema);
        if (template == null) {
            userFieldsGrid.setItems(List.of());
            userGroupsGrid.setItems(List.of());
            userTotalsGrid.setItems(List.of());
            userSortGrid.setItems(List.of());
            userGroupTotalGroup.setItems(List.of());
            clearUserFieldSelection();
            return;
        }
        ReportBand detail = ReportLayoutOperations.findBand(template, ReportBandKind.DETAIL);
        List<ReportField> detailFields = detail == null ? List.of() : detail.getFields();
        userFieldsGrid.setItems(detailFields);
        userGroupsGrid.setItems(template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.GROUP_HEADER).toList());
        userGroupTotalGroup.setItems(template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.GROUP_HEADER).toList());
        userTotalsGrid.setItems(template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.REPORT_FOOTER
                        || band.getKind() == ReportBandKind.GROUP_FOOTER)
                .flatMap(band -> band.getFields().stream()).toList());
        userSortGrid.setItems(template.getOrders());
        if (selectedUserField != null && !detailFields.contains(selectedUserField)) {
            clearUserFieldSelection();
        }
        refreshFilterEditor(schema);
    }

    // ------------------------------------------------------------ недоступные настройки

    /**
     * Показывает предупреждение о недоступных настройках: после смены запроса в списках остаются
     * строки, помеченные «Недоступно», и пользователю нужно сказать, где их разбирать.
     *
     * <p>Текст и место (над списками) перенесены из variant-вьюхи; в отличие от неё предупреждение
     * не остаётся висеть после того, как настройки разобраны: владелец снимает его через
     * {@link #clearOrphanedChanges()}.</p>
     */
    void showOrphanedChanges() {
        clearOrphanedChanges();
        orphanedChangesNotice = new Span(ORPHANED_CHANGES_MESSAGE);
        orphanedChangesNotice.getStyle().set("color", "var(--lumo-error-text-color)");
        addComponentAsFirst(orphanedChangesNotice);
    }

    /** Снимает предупреждение: недоступных настроек в списках больше нет. */
    void clearOrphanedChanges() {
        if (orphanedChangesNotice != null) {
            remove(orphanedChangesNotice);
            orphanedChangesNotice = null;
        }
    }

    /**
     * Есть ли в настройках ссылки на поля, которых нет в схеме: именно такие строки списки
     * подписывают «Недоступно». Условие одно и для списков, и для предупреждения — иначе
     * предупреждение говорило бы о пометках, которых на экране нет, или молчало о видимых.
     */
    boolean hasUnavailableReferences() {
        return referencedAliases().stream().anyMatch(alias -> findQueryField(alias) == null);
    }

    /**
     * Поле агрегата в подписи итога. {@code null} — у агрегата нет поля запроса: «количество
     * строк» не ссылается на колонку ({@code ReportLayoutOperations.addFooterAggregate} очищает ей
     * alias), поэтому список больше не подписывает её «Недоступно: ». До этого пустой alias было
     * не отличить от исчезнувшей колонки.
     */
    String aggregateTargetLabel(ReportField field) {
        String alias = field.getQueryField();
        if (alias == null || alias.isBlank()) {
            return null;
        }
        QueryField queryField = findQueryField(alias);
        return queryField == null ? "Недоступно: " + alias : userFieldLabel(queryField);
    }

    /** Все alias, на которые ссылаются настройки простого режима (поля, группы, итоги, сортировка). */
    private List<String> referencedAliases() {
        ReportTemplate template = context.template();
        if (template == null) {
            return List.of();
        }
        List<String> aliases = new java.util.ArrayList<>();
        ReportBand detail = ReportLayoutOperations.findBand(template, ReportBandKind.DETAIL);
        if (detail != null) {
            detail.getFields().forEach(field -> aliases.add(field.getQueryField()));
        }
        for (ReportBand band : template.getBands()) {
            if (band.getKind() == ReportBandKind.GROUP_HEADER) {
                aliases.add(band.getGroupField());
            }
            if (band.getKind() == ReportBandKind.REPORT_FOOTER
                    || band.getKind() == ReportBandKind.GROUP_FOOTER) {
                band.getFields().forEach(field -> aliases.add(field.getQueryField()));
            }
        }
        template.getOrders().forEach(order -> aliases.add(order.getColumnName()));
        return aliases.stream().filter(alias -> alias != null && !alias.isBlank()).toList();
    }

    /**
     * Пересобирает визуальный отбор ровно тогда, когда сменился состав схемы: резолвер
     * библиотеки захватывает поля в конструкторе, поэтому «обновить список» у него нельзя.
     */
    private void refreshFilterEditor(List<QueryField> schema) {
        String signature = schema.stream().map(field -> field.name() + ":" + field.javaType()).reduce("", String::concat);
        if (userFilterEditor != null && signature.equals(filterSchemaSignature)) {
            return;
        }
        filterSchemaSignature = signature;
        userFilterEditor = new FilterTreeEditor(
                new QueryFieldFilterFieldResolver(schema),
                readVisualFilter(),
                this::onUserFilterChanged);
        filterHost.removeAll();
        filterHost.add(userFilterEditor);
    }

    private FilterNode readVisualFilter() {
        String json = context.visualFilterJson();
        if (json == null) {
            return null;
        }
        try {
            return FilterTreeJson.read(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (RuntimeException | java.io.IOException error) {
            return null;
        }
    }

    /**
     * Запись отбора в шаблон. Невалидный отбор не переносится в модель и не отмечает отчёт
     * изменённым: пользователь видит ошибки в самом редакторе, а сохранять нечего.
     */
    private void onUserFilterChanged(FilterNode value) {
        if (context.template() == null || userFilterEditor == null) {
            return;
        }
        if (!userFilterEditor.validationErrors().isEmpty()) {
            return;
        }
        context.visualFilterJson(value == null ? null : FilterTreeJson.write(value).toString());
        context.modelChanged();
    }

    private void clearUserFieldSelection() {
        selectedUserField = null;
        userCaption.clear();
        userWidth.clear();
        userAlignment.clear();
        userFormat.clear();
        userVisible.clear();
        userBorder.clear();
    }

    // ------------------------------------------------------------ действия пользователя

    private void addUserField() {
        ReportTemplate template = context.template();
        if (template == null || userField.getValue() == null) {
            return;
        }
        ReportBand detail = ReportLayoutOperations.ensureBand(template, ReportBandKind.DETAIL);
        try {
            ReportLayoutOperations.addDetailColumn(detail, userField.getValue().name(), userField.getValue());
        } catch (IllegalArgumentException error) {
            // Отклонённая правка не меняет модель, поэтому не может делать отчёт изменённым.
            context.notify(error.getMessage());
            return;
        }
        context.modelChanged();
    }

    private void addUserGroup() {
        if (context.template() == null || userGroupField.getValue() == null) {
            return;
        }
        context.addGroupPair(userGroupField.getValue().name());
        context.modelChanged();
    }

    private void removeUserGroup(ReportBand band) {
        ReportLayoutOperations.removeGroup(context.template(), band);
        context.modelChanged();
    }

    private void addUserGroupTotal() {
        ReportTemplate template = context.template();
        if (template == null || userGroupTotalGroup.getValue() == null
                || (userTotalField.getValue() == null
                && userTotalAggregation.getValue() != ReportFieldAggregation.COUNT_ROWS)) {
            return;
        }
        ReportBand footer = ReportLayoutOperations.ensureGroupFooter(template, userGroupTotalGroup.getValue());
        ReportLayoutOperations.addFooterAggregate(footer, aggregateAlias(), userTotalField.getValue(),
                userTotalAggregation.getValue());
        context.modelChanged();
    }

    private void addUserTotal() {
        ReportTemplate template = context.template();
        if (template == null || (userTotalField.getValue() == null
                && userTotalAggregation.getValue() != ReportFieldAggregation.COUNT_ROWS)) {
            return;
        }
        ReportBand footer = ReportLayoutOperations.ensureBand(template, ReportBandKind.REPORT_FOOTER);
        ReportLayoutOperations.addFooterAggregate(footer, aggregateAlias(), userTotalField.getValue(),
                userTotalAggregation.getValue());
        context.modelChanged();
    }

    /** Алиас агрегата «количество строк» не привязан к полю запроса — так его понимает модель. */
    private String aggregateAlias() {
        return userTotalField.getValue() == null ? "__reportstudio_row_marker" : userTotalField.getValue().name();
    }

    private void removeUserTotal(ReportField field) {
        if (field != null && field.getBand() != null) {
            ReportLayoutOperations.removeField(field.getBand(), field);
            context.modelChanged();
        }
    }

    private void selectUserField(ReportField field) {
        selectedUserField = field;
        userCaption.setValue(Objects.requireNonNullElse(field.getCaption(), ""));
        userWidth.setValue(field.getWidth());
        userAlignment.setValue(field.getAlignment());
        userFormat.setValue(Objects.requireNonNullElse(field.getFormat(), ""));
        userVisible.setValue(field.isVisible());
        userBorder.setValue(field.getBorder() == null || field.getBorder());
    }

    private void applyUserFieldProperties() {
        if (selectedUserField == null) {
            return;
        }
        ReportLayoutOperations.updateFieldProperties(selectedUserField, userCaption.getValue(),
                userVisible.getValue(), userWidth.getValue(), userAlignment.getValue(),
                userFormat.getValue(), userBorder.getValue());
        context.modelChanged();
    }

    private void moveUserField(ReportField field, int delta) {
        if (field != null && field.getBand() != null) {
            ReportLayoutOperations.moveField(field.getBand(), field, delta);
            context.modelChanged();
        }
    }

    private void removeUserField(ReportField field) {
        if (field != null && field.getBand() != null) {
            ReportLayoutOperations.removeField(field.getBand(), field);
            context.modelChanged();
        }
    }

    private void addUserSort() {
        ReportTemplate template = context.template();
        if (template == null || userSortField.getValue() == null) {
            return;
        }
        try {
            ReportLayoutOperations.addOrder(template, userSortField.getValue().name(),
                    userSortDirection.getValue());
        } catch (IllegalArgumentException error) {
            context.notify(error.getMessage());
            return;
        }
        context.modelChanged();
    }

    private void removeUserSort(ReportOrder order) {
        ReportLayoutOperations.removeOrder(context.template(), order);
        context.modelChanged();
    }

    private void moveUserSort(ReportOrder order, int delta) {
        ReportLayoutOperations.moveOrder(context.template(), order, delta);
        context.modelChanged();
    }

    // ------------------------------------------------------------ подписи

    private String userFieldLabel(QueryField field) {
        return field == null ? "" : Objects.requireNonNullElse(field.caption(), field.name());
    }

    private QueryField findQueryField(String alias) {
        return context.schemaFields().stream()
                .filter(field -> Objects.equals(field.name(), alias))
                .findFirst()
                .orElse(null);
    }

    private String alignmentLabel(ReportFieldAlignment alignment) {
        return switch (alignment) {
            case LEFT -> "Слева";
            case CENTER -> "По центру";
            case RIGHT -> "Справа";
        };
    }

    private String aggregationLabel(ReportFieldAggregation aggregation) {
        return switch (aggregation) {
            case SUM -> "Сумма";
            case COUNT -> "Количество значений";
            case COUNT_ROWS -> "Количество строк";
            case AVG -> "Среднее";
            case MIN -> "Минимум";
            case MAX -> "Максимум";
            case NONE -> "—";
        };
    }

    // ------------------------------------------------------------ тестовые швы

    ComboBox<QueryField> userFieldCombo() {
        return userField;
    }

    Grid<ReportField> userFieldsGrid() {
        return userFieldsGrid;
    }

    /**
     * Тестовый шов: список итогов. В отличие от списка полей он держит снимок модели, поэтому
     * на нём видно, перечитала ли панель настройки после чистки.
     */
    Grid<ReportField> userTotalsGrid() {
        return userTotalsGrid;
    }

    FilterTreeEditor userFilterEditor() {
        return userFilterEditor;
    }

    ReportField selectedUserField() {
        return selectedUserField;
    }

    /** Тестовый шов: показано ли предупреждение о недоступных настройках. */
    boolean hasOrphanedChangesNotice() {
        return orphanedChangesNotice != null;
    }
}
