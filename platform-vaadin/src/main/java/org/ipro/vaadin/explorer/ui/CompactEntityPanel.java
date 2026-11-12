package org.ipro.vaadin.explorer.ui;

import com.vaadin.flow.component.ClickNotifier;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.HtmlContainer;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.StructuralAttribute.AccessMode;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.vaadin.explorer.EntitySummary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** UI-only presenter of the ready snapshot. No resolvers, factories, entity instances or writes. */
final class CompactEntityPanel extends Div {
    private final EntitySummary summary;
    private final EntityExplorerCardState state;
    private final Button entities;
    private final Div general = new Div();
    private final Div data = new Div();
    private final Div owners = new Div();
    private final Div forms = new Div();
    private final Div lifecycle = new Div();
    private final Div presentation = new Div();
    private final Div numbering = new Div();
    private final Button presentationTab = new Button("Представление", e -> chooseTab(CompactCardPlace.Tab.PRESENTATION));
    private final Button numberingTab = new Button("Нумерация", e -> chooseTab(CompactCardPlace.Tab.NUMBERING));
    private final ExplorerSourcePort sourceReader;
    private final ExplorerFormPreviewPort preview;
    private final Button formsTab = new Button("Формы", e -> chooseTab(CompactCardPlace.Tab.FORMS));
    private final Button lifecycleTab = new Button("Обработчики", e -> chooseTab(CompactCardPlace.Tab.LIFECYCLE));
    private final Button ownersTab = new Button("Родитель / Владельцы", e -> chooseTab(CompactCardPlace.Tab.OWNERS));
    private final Div inspector = new Div();
    private final Div notice = new Div();
    private final Span breadcrumb = new Span();
    private final Button generalTab = new Button("Основные", e -> chooseTab(CompactCardPlace.Tab.GENERAL));
    private final Button dataTab = new Button("Данные", e -> chooseTab(CompactCardPlace.Tab.DATA));
    private final SplitLayout split = new SplitLayout();
    private CompactCardPlace.DataTarget selected;
    private record Choice(CompactCardPlace.DataTarget target, FieldButton button) { }
    private final List<Choice> choices = new ArrayList<>();
    private final java.util.Map<CompactCardPlace.OwnedSection, Div> sectionBodies = new java.util.LinkedHashMap<>();
    private final java.util.Map<CompactCardPlace.OwnedSection, Button> sectionToggles = new java.util.LinkedHashMap<>();
    private boolean sourcesOpened;

    CompactEntityPanel(EntitySummary summary, EntityExplorerCardState state,
                             Runnable catalog, Consumer<String> technical) {
        this(summary, state, catalog, technical, ExplorerSourcePort.unavailable(), ExplorerFormPreviewPort.unavailable());
    }

    CompactEntityPanel(EntitySummary summary, EntityExplorerCardState state,
                             Runnable catalog, Consumer<String> technical, ExplorerSourcePort sourceReader, ExplorerFormPreviewPort preview) {
        this.summary = summary;
        this.state = state;
        this.sourceReader = sourceReader; this.preview = preview;
        addClassNames("entity-explorer-pilot");
        getElement().setAttribute("data-entity", summary.simpleName());
        entities = new Button("К реестру сущностей", e -> { if (catalog != null) catalog.run(); });
        entities.setVisible(catalog != null);
        entities.getElement().setAttribute("aria-label", "Вернуться к реестру сущностей");
        Span title = new Span(summary.displayName().value() + " (" + summary.simpleName() + ")");
        title.addClassName("explorer-entity-title");
        Span readOnly = new Span("Только просмотр");
        readOnly.addClassName("explorer-read-only");
        Div heading = new Div(new Span("Структура сущности"), entities, title, readOnly);
        heading.addClassName("explorer-pilot-heading");
        Span subtitle = new Span(overview("Тип сущности") + " · Подсистема: " + overview("Подсистема"));
        subtitle.addClassName("explorer-pilot-subtitle");
        Div header = new Div(heading, subtitle);
        header.addClassName("explorer-pilot-header");

        Div nav = new Div(generalTab, dataTab, ownersTab, presentationTab, formsTab, lifecycleTab, numberingTab, new Button("Техническая сводка", e -> technical.accept("overview")));
        nav.addClassName("explorer-pilot-nav");
        nav.getElement().setAttribute("aria-label", "Разделы сущности");
        generalTab.getElement().setAttribute("data-tab", "general");
        dataTab.getElement().setAttribute("data-tab", "data");
        buildGeneral();
        buildData();
        buildOwners();
        buildPresentation();
        buildNumbering();
        presentationTab.getElement().setAttribute("data-tab", "presentation");
        numberingTab.getElement().setAttribute("data-tab", "numbering");
        ownersTab.getElement().setAttribute("data-tab", "owners");
        formsTab.getElement().setAttribute("data-tab", "forms");
        lifecycleTab.getElement().setAttribute("data-tab", "lifecycle");
        forms.addClassName("explorer-behavior-page");
        lifecycle.addClassName("explorer-behavior-page");
        Div pages = new Div(general, data, owners, presentation, forms, lifecycle, numbering);
        pages.addClassName("explorer-pilot-pages");
        Div body = new Div(nav, pages);
        body.addClassName("explorer-pilot-body");
        breadcrumb.addClassName("explorer-pilot-breadcrumb");
        breadcrumb.getElement().setAttribute("aria-live", "polite");
        add(header, body, breadcrumb);

        CompactCardPlace saved = state.pilotPlaceOf(summary.entityClass());
        selected = saved.target();
        if (selected != null && choices.stream().noneMatch(c -> c.target().equals(selected))) {
            notice.setText("Сохранённый элемент недоступен. Выберите элемент из списка.");
            if (selected instanceof CompactCardPlace.SectionField field && sectionRow(field.section()) != null)
                selected = field.section();
            else selected = null;
        }
        if (selected instanceof CompactCardPlace.SectionField field) setSectionOpened(field.section(), true);
        chooseTab(saved.tab());
    }

    private void buildGeneral() {
        general.addClassName("explorer-pilot-general");
        ExplorerCardPropertyList properties = new ExplorerCardPropertyList();
        properties.property("name", "Имя в коде", summary.simpleName());
        properties.property("caption", "Название", summary.displayName().value());
        properties.property("kind", "Вид", overview("Тип сущности"));
        properties.property("base", "Базовый класс", summary.entityClass().getSuperclass().getSimpleName());
        properties.property("package", "Пакет", summary.entityClass().getPackageName());
        properties.property("subsystem", "Подсистема", overview("Подсистема"));
        ExplorerCardPropertyList sources = new ExplorerCardPropertyList();
        sources.property("caption-source", "Название", source(summary.displayName()));
        sources.property("class-source", "Класс", summary.entityClass().getName());
        for (var row : summary.overview()) if (List.of("Тип сущности", "Подсистема").contains(row.caption()))
            sources.property(row.key().kind().name(), row.caption(), source(row.value()));
        general.add(new H4("Основные"), properties, new Details("Источники", sources));
    }

    private void buildPresentation() {
        presentation.addClassName("explorer-pilot-general");
        ExplorerCardPropertyList properties = new ExplorerCardPropertyList();
        properties.property("list-title", "Заголовок списка", summary.displayName().value());
        properties.property("item-title", "Заголовок элемента", overview("Заголовок формы элемента"));
        properties.property("selection-title", "Заголовок выбора", overview("Заголовок формы выбора"));
        var facts = summary.structure().presentation();
        ExplorerCardPropertyList sources = new ExplorerCardPropertyList();
        if (facts == null) properties.property("presentation", "Представление экземпляра", "Источник недоступен");
        else {
            properties.property("icon", "Значок", facts.icon().isBlank() ? "Не настроен" : facts.icon());
            properties.property("instance-name", "Представление экземпляра", facts.strategy());
            if (!facts.methodSymbol().isBlank()) {
                properties.property("display-method", "Java-метод", facts.methodSymbol());
                Button code = new Button("Код getDisplayName()", e -> {
                    String[] symbol = facts.methodSymbol().split("#", 2);
                    ExplorerCardSourceView view = new ExplorerCardSourceView();
                    view.show(sourceReader.read(summary, new ExplorerSourcePort.Target(symbol[0], symbol[1])));
                    Div page = new Div();
                    page.addClassName("explorer-form-source-page");
                    Button back = new Button("Назад к представлению", event -> {
                        page.removeFromParent();
                        presentation.getChildren().forEach(c -> c.setVisible(true));
                    });
                    presentation.getChildren().forEach(c -> c.setVisible(false));
                    page.add(back, view);
                    presentation.add(page);
                    back.focus();
                });
                properties.property("display-code", "Исходник", code);
            }
            properties.property("display-sort", "Сортировка представления", facts.sortFields().isEmpty() ? "По первичному ключу" : String.join(", ", facts.sortFields()));
            sources.property("presentation-source", "Представление", facts.declaration().isBlank() ? "Платформенный fallback" : facts.declaration());
            sources.property("sort-source", "Сортировка", summary.entityClass().getName() + " · @EntityMetadata.displaySortFields");
            sources.property("icon-source", "Значок", summary.entityClass().getName() + " · @EntityMetadata.icon");
        }
        sources.property("list-source", "Заголовок списка", source(summary.displayName()));
        for (var row : summary.overview()) if (row.caption().startsWith("Заголовок формы"))
            sources.property(row.key().kind().name(), row.caption(), source(row.value()));
        presentation.add(new H4("Представление"), properties, new Details("Источники", sources));
    }

    private void buildNumbering() {
        numbering.addClassName("explorer-pilot-general");
        numbering.add(new H4("Нумерация"));
        if (summary.numbering().isEmpty()) numbering.add(new Span("Декларация нумерации не настроена"));
        for (var row : summary.numbering()) {
            ExplorerCardPropertyList properties = new ExplorerCardPropertyList();
            properties.property("numbered-field", "Нумеруемое поле", row.fieldName());
            var policy = row.definition();
            properties.property("numbering-role", "Роль", policy == null ? "Источник недоступен" : policy.role().name());
            properties.property("numbering-mode", "Режим", "Операционный режим не предоставлен");
            properties.property("manual", "Ручной ввод", row.manualAllowed() ? "Разрешён декларацией" : "Запрещён декларацией");
            properties.property("scope", "Разрезы", row.scope().equals("GLOBAL") ? "Глобально" : row.scope());
            properties.property("period", "Период", row.period());
            properties.property("date-field", "Поле даты", policy == null ? "Источник недоступен" : policy.dateField().isBlank() ? "Не применяется" : policy.dateField());
            properties.property("prefix", "Префикс", policy == null ? "Источник недоступен" : policy.prefix().isBlank() ? "Не задан" : policy.prefix());
            properties.property("pattern", "Шаблон", policy == null ? "Источник недоступен" : policy.pattern().isBlank() ? "Не задан" : policy.pattern());
            ExplorerCardPropertyList sources = new ExplorerCardPropertyList();
            sources.property("field-source", "Поле", source(row.value()));
            sources.property("policy-source", "Политика", row.policySource().isBlank() ? "Источник недоступен" : row.policySource());
            numbering.add(properties, new Details("Источники", sources));
        }
        Span note = new Span("Декларация после применения role-policy. Операционные переопределения и счётчики БД не загружаются.");
        note.addClassName("explorer-property-note");
        numbering.add(note);
    }

    private static String tabCaption(CompactCardPlace.Tab tab) {
        return switch (tab) {
            case GENERAL -> "Основные"; case DATA -> "Данные"; case OWNERS -> "Родитель / Владельцы";
            case PRESENTATION -> "Представление"; case FORMS -> "Формы";
            case LIFECYCLE -> "Обработчики"; case NUMBERING -> "Нумерация";
        };
    }

    private void buildData() {
        data.addClassName("explorer-pilot-data");
        Div list = new Div();
        list.addClassName("explorer-field-list");
        H4 title = new H4("Реквизиты (" + summary.structure().fields().size() + ")");
        Div grid = new Div();
        grid.addClassName("explorer-field-grid");
        grid.getElement().setAttribute("role", "grid");
        grid.getElement().setAttribute("aria-label", "Реквизиты сущности");
        grid.getElement().setAttribute("aria-rowcount", String.valueOf(summary.structure().fields().size() + 1));
        Div columns = new Div(cell("Имя", "columnheader"), cell("Подпись", "columnheader"),
            cell("Тип", "columnheader"), cell("*", "columnheader"));
        columns.addClassName("explorer-field-columns");
        columns.getElement().setAttribute("role", "row");
        grid.add(columns);
        for (var field : summary.structure().fields()) grid.add(fieldButton(field));
        Div scroll = new Div(grid);
        scroll.addClassName("explorer-field-scroll");
        if (!summary.structure().sections().isEmpty()) {
            Div sections = new Div(new H4("Табличные части"));
            sections.addClassName("explorer-pilot-sections");
            for (var section : summary.structure().sections()) {
                var target = sectionTarget(section);
                Button toggle = new Button("▶", e -> toggleSection(target));
                toggle.getElement().setAttribute("aria-label", "Раскрыть или свернуть " + section.value().value());
                toggle.getElement().setAttribute("data-section-toggle", section.identity().id());
                FieldButton label = new FieldButton();
                label.setText(section.value().value() + " (" + section.fields().size() + ")");
                label.getElement().setAttribute("title", section.value().value() + " · " + section.identity().rowType().getName());
                label.addClassName("explorer-section-label");
                label.getElement().setAttribute("data-section", section.identity().id());
                label.addClickListener(e -> selectTarget(target, false));
                registerChoice(target, label);
                Div heading = new Div(toggle, label);
                heading.addClassName("explorer-section-heading");
                Div children = new Div();
                children.setId("section-" + summary.simpleName() + "-" + section.identity().rowType().getSimpleName() + "-" + section.identity().ownerField());
                toggle.getElement().setAttribute("aria-controls", children.getId().orElseThrow());
                children.addClassName("explorer-section-fields");
                children.getElement().setAttribute("role", "grid");
                children.getElement().setAttribute("aria-label", "Реквизиты табчасти «" + section.value().value() + "»");
                children.getElement().setAttribute("aria-rowcount", String.valueOf(section.fields().size()));
                for (var field : section.fields()) children.add(fieldButton(field));
                sectionBodies.put(target, children);
                sectionToggles.put(target, toggle);
                setSectionOpened(target, state.sectionOpened(summary.entityClass(), target));
                sections.add(heading, children);
            }
            scroll.add(sections);
        }
        notice.addClassName("explorer-pilot-notice");
        if (summary.structure().fields().stream().anyMatch(field -> !field.published())) {
            notice.setText("Показаны все персистентные JPA-атрибуты. Поля без @FieldMetadata "
                + "известны структурно, но не входят в стандартные формы и грид.");
        }
        list.add(title, notice, scroll);
        inspector.addClassName("explorer-field-inspector");
        inspector.getElement().setAttribute("role", "region");
        inspector.getElement().setAttribute("aria-label", "Свойства выбранного реквизита");
        split.addClassName("explorer-data-split");
        split.addToPrimary(list);
        split.addToSecondary(inspector);
        ExplorerCardSequentialLayout.install(split, list, inspector);
        // A stable initial inspector width at both desktop control sizes; drag remains free.
        list.getStyle().set("flex", "1 1 0px");
        inspector.getStyle().set("flex", "0 0 336px");
        data.add(split);
    }

    private static Span cell(String text, String role) {
        Span cell = new Span(text);
        cell.getElement().setAttribute("role", role);
        cell.getElement().setAttribute("title", text);
        return cell;
    }

    private CompactCardPlace.OwnedSection sectionTarget(EntitySummary.OwnedSectionRow section) {
        return new CompactCardPlace.OwnedSection(section.identity().ownerField(), section.identity().rowType());
    }

    private CompactCardPlace.DataTarget fieldTarget(EntitySummary.StructuralFieldRow field) {
        var id = field.identity();
        return id.section() == null ? new CompactCardPlace.RootField(id.fieldName())
            : new CompactCardPlace.SectionField(new CompactCardPlace.OwnedSection(id.section().ownerField(), id.section().rowType()), id.fieldName());
    }

    private EntitySummary.OwnedSectionRow sectionRow(CompactCardPlace.OwnedSection target) {
        return summary.structure().sections().stream().filter(s -> sectionTarget(s).equals(target)).findFirst().orElse(null);
    }

    private FieldButton fieldButton(EntitySummary.StructuralFieldRow field) {
        FieldButton row = new FieldButton();
        row.addClassName("explorer-field-row");
        row.getElement().setAttribute("role", "row");
        row.getElement().setAttribute("data-field", field.identity().fieldName());
        row.getElement().setAttribute("data-published", String.valueOf(field.published()));
        row.getElement().setAttribute("data-context", field.identity().section() == null ? "root" : field.identity().section().id());
        if (!field.published()) {
            row.addClassName("explorer-field-unpublished");
        }
        row.getElement().setAttribute("aria-label", field.identity().fieldName() + " · " + field.value().value()
            + " · " + typeLabel(field) + (field.uiRequired().value() ? " · UI-заполнение обязательно" : "")
            + (field.published() ? "" : " · структурно известно, не опубликовано"));
        row.add(cell(field.identity().fieldName(), "gridcell"), cell(field.value().value(), "gridcell"),
            cell(typeLabel(field), "gridcell"), cell(field.uiRequired().value() ? "*" : "", "gridcell"));
        var target = fieldTarget(field);
        row.addClickListener(e -> selectTarget(target, false));
        registerChoice(target, row);
        return row;
    }

    private void registerChoice(CompactCardPlace.DataTarget target, FieldButton row) {
        row.getElement().setAttribute("type", "button");
        choices.add(new Choice(target, row));
        row.getElement().addEventListener("keydown", e -> move(target, e.getEventData().get("event.key").asText()))
            .addEventData("event.key")
            .setFilter("['ArrowDown','ArrowUp','Home','End'].includes(event.key) && (event.preventDefault(), true)");
    }

    private void setSectionOpened(CompactCardPlace.OwnedSection target, boolean opened) {
        sectionBodies.get(target).setVisible(opened);
        sectionToggles.get(target).setText(opened ? "▼" : "▶");
        sectionToggles.get(target).getElement().setAttribute("aria-expanded", String.valueOf(opened));
        state.rememberSectionOpened(summary.entityClass(), target, opened);
    }

    private void toggleSection(CompactCardPlace.OwnedSection target) {
        boolean opened = !state.sectionOpened(summary.entityClass(), target);
        setSectionOpened(target, opened);
        if (!opened && selected instanceof CompactCardPlace.SectionField field && field.section().equals(target))
            selectTarget(target, true);
        else if (opened) {
            String last = state.sectionField(summary.entityClass(), target);
            if (last != null) focusSectionField(target, last);
        }
    }

    void chooseTab(CompactCardPlace.Tab tab) {
        boolean isData = tab == CompactCardPlace.Tab.DATA;
        boolean isOwners = tab == CompactCardPlace.Tab.OWNERS;
        boolean isForms = tab == CompactCardPlace.Tab.FORMS;
        boolean isLifecycle = tab == CompactCardPlace.Tab.LIFECYCLE;
        general.setVisible(tab == CompactCardPlace.Tab.GENERAL);
        presentation.setVisible(tab == CompactCardPlace.Tab.PRESENTATION);
        numbering.setVisible(tab == CompactCardPlace.Tab.NUMBERING);
        presentationTab.getElement().setAttribute("aria-pressed", String.valueOf(tab == CompactCardPlace.Tab.PRESENTATION));
        numberingTab.getElement().setAttribute("aria-pressed", String.valueOf(tab == CompactCardPlace.Tab.NUMBERING));
        owners.setVisible(isOwners);
        data.setVisible(isData);
        forms.setVisible(isForms);
        lifecycle.setVisible(isLifecycle);
        generalTab.getElement().setAttribute("aria-pressed", String.valueOf(tab == CompactCardPlace.Tab.GENERAL));
        dataTab.getElement().setAttribute("aria-pressed", String.valueOf(isData));
        ownersTab.getElement().setAttribute("aria-pressed", String.valueOf(isOwners));
        formsTab.getElement().setAttribute("aria-pressed", String.valueOf(isForms));
        lifecycleTab.getElement().setAttribute("aria-pressed", String.valueOf(isLifecycle));
        if (isForms || isLifecycle) {
            state.rememberPilotPlace(new CompactCardPlace(summary.entityClass(), tab, null));
            Div page = isForms ? forms : lifecycle;
            if (page.getComponentCount() == 0) page.add(new ExplorerCardBehaviorPanel(summary, state, tab, sourceReader, preview, breadcrumb::setText));
            else breadcrumb.setText(summary.simpleName() + (isForms ? " / Формы / " : " / Обработчики / ")
                + state.behaviorSelection(summary.entityClass(), tab));
            return;
        }
        if (isData) {
            boolean wasDetail = split.getElement().hasAttribute("data-detail");
            if (selected == null && !choices.isEmpty()) selected = choices.getFirst().target();
            if (selected != null) selectTarget(selected, false);
            else {
                inspector.removeAll();
                inspector.add(new Span("Размеченные реквизиты отсутствуют"));
                state.rememberPilotPlace(new CompactCardPlace(summary.entityClass(), tab, null));
                breadcrumb.setText(summary.simpleName() + " / Реквизиты");
            }
            if (!wasDetail) split.getElement().removeAttribute("data-detail");
        } else {
            state.rememberPilotPlace(new CompactCardPlace(summary.entityClass(), tab, null));
            breadcrumb.setText(summary.simpleName() + " / " + tabCaption(tab));
        }
    }

    void focusField(String name) {
        summary.structure().fields().stream().filter(f -> f.identity().fieldName().equals(name)).findFirst()
            .ifPresent(field -> focusTarget(fieldTarget(field)));
    }

    void focusSection(CompactCardPlace.OwnedSection section) {
        if (sectionRow(section) != null) focusTarget(section);
    }

    void focusSectionField(CompactCardPlace.OwnedSection section, String name) {
        var row = sectionRow(section);
        if (row != null && row.fields().stream().anyMatch(f -> f.identity().fieldName().equals(name))) {
            setSectionOpened(section, true);
            focusTarget(new CompactCardPlace.SectionField(section, name));
        }
    }

    private void focusTarget(CompactCardPlace.DataTarget target) {
        selected = target;
        chooseTab(CompactCardPlace.Tab.DATA);
        selectTarget(target, true);
    }

    void setCatalogOpened(boolean opened) {
        entities.getElement().setAttribute("aria-expanded", String.valueOf(opened));
    }

    private void move(CompactCardPlace.DataTarget target, String key) {
        var visible = choices.stream().filter(c -> !(c.target() instanceof CompactCardPlace.SectionField f)
            || state.sectionOpened(summary.entityClass(), f.section())).toList();
        int index = java.util.stream.IntStream.range(0, visible.size()).filter(i -> visible.get(i).target().equals(target)).findFirst().orElse(0);
        int next = switch (key) {
            case "ArrowDown" -> Math.min(visible.size() - 1, index + 1);
            case "ArrowUp" -> Math.max(0, index - 1);
            case "Home" -> 0;
            case "End" -> visible.size() - 1;
            default -> index;
        };
        selectTarget(visible.get(next).target(), true);
    }

    private void selectTarget(CompactCardPlace.DataTarget target, boolean focus) {
        selected = target;
        for (var choice : choices) {
            boolean active = choice.target().equals(target);
            choice.button().getElement().setAttribute("aria-selected", String.valueOf(active));
            choice.button().getElement().setAttribute("tabindex", active ? "0" : "-1");
            if (active && focus) choice.button().getElement().executeJs(
                "this.scrollIntoView({block:'nearest',inline:'nearest'}); this.focus({preventScroll:true});");
        }
        state.rememberPilotPlace(new CompactCardPlace(summary.entityClass(), CompactCardPlace.Tab.DATA, target));
        if (target instanceof CompactCardPlace.OwnedSection section) {
            showSection(sectionRow(section));
        } else {
            EntitySummary.StructuralFieldRow field;
            if (target instanceof CompactCardPlace.SectionField owned) {
                state.rememberSectionField(summary.entityClass(), owned);
                var section = sectionRow(owned.section());
                field = section.fields().stream().filter(f -> f.identity().fieldName().equals(owned.fieldName())).findFirst().orElseThrow();
                breadcrumb.setText(summary.simpleName() + " / " + section.value().value() + " / " + owned.fieldName());
            } else {
                String name = ((CompactCardPlace.RootField) target).fieldName();
                field = summary.structure().fields().stream().filter(f -> f.identity().fieldName().equals(name)).findFirst().orElseThrow();
                breadcrumb.setText(summary.simpleName() + " / Реквизиты / " + name);
            }
            showInspector(field);
        }
        ExplorerCardSequentialLayout.showDetail(split);
    }

    private void showSection(EntitySummary.OwnedSectionRow section) {
        inspector.removeAll();
        H4 title = new H4("Табчасть: " + section.value().value());
        title.getElement().setAttribute("data-inspector-section", section.identity().id());
        ExplorerCardPropertyList properties = new ExplorerCardPropertyList();
        properties.property("row-type", "Класс строки", section.identity().rowType().getSimpleName());
        properties.property("owner", "Владелец", section.identity().ownerType().getSimpleName());
        properties.property("owner-field", "Поле связи", section.identity().ownerField());
        properties.property("field-count", "Реквизитов", String.valueOf(section.fields().size()));
        ExplorerCardPropertyList sources = new ExplorerCardPropertyList();
        sources.property("min-rows", "Минимум строк", String.valueOf(section.minRows().value()));
        sources.property("line-number", "Номер строки", section.lineNumberField() == null ? "Не настроен" : section.lineNumberField().value());
        sources.property("persistence", "Сохранение", section.persistence().value());
        sources.property("rls", "RLS строк", section.rlsPolicy().value());
        sources.property("order", "Порядок", String.valueOf(section.order().value()));
        sources.property("source", "Источник", source(section.value()));
        sources.property("ownership-source", "Связь владельца", ownershipSource(section));
        Details details = new Details("Сохранение и источники", sources);
        inspector.add(title, properties, details);
        breadcrumb.setText(summary.simpleName() + " / Табличные части / " + section.value().value());
    }

    private String ownershipSource(EntitySummary.OwnedSectionRow section) {
        return section.identity().rowType().getName() + " · @TableSectionMetadata(parentField = \"" + section.identity().ownerField() + "\")";
    }

    private void buildOwners() {
        owners.addClassName("explorer-pilot-general");
        ExplorerCardPropertyList properties = new ExplorerCardPropertyList();
        properties.property("object", "Объект", summary.simpleName());
        properties.property("hierarchy-parent", "Иерархический родитель", "Явный контракт не подключён");
        var owner = summary.structure().catalogOwner();
        properties.property("object-owners", "Владелец справочника", !summary.structure().available() ? "Сведения о владельце недоступны"
            : owner == null ? "Не объявлен" : owner.property() + " → " + owner.targetTypeId());
        if (owner != null) {
            properties.property("catalog-owner-contract", "Правило владельца", "Обязателен; выбирается при создании и не меняется после сохранения. Права не наследуются.");
            properties.property("catalog-owner-source", "Источник", "@CatalogOwner(property = \"" + owner.property() + "\") · " + summary.entityClass().getName());
        }
        owners.add(new H4("Родитель / Владельцы"), properties, new H4("Подчинённые строки"));
        if (summary.structure().sections().isEmpty()) owners.add(new Span("Подтверждённые табличные части отсутствуют"));
        for (var section : summary.structure().sections()) {
            ExplorerCardPropertyList owned = new ExplorerCardPropertyList();
            Button link = new Button(section.identity().rowType().getSimpleName(), e -> focusSection(sectionTarget(section)));
            owned.property("owned-section", "Табличная часть", link);
            String linkText = section.identity().ownerField() + " → " + summary.simpleName();
            if (section.fields().stream().anyMatch(f -> f.identity().fieldName().equals(section.identity().ownerField()))) {
                Button field = new Button(linkText,
                    e -> focusSectionField(sectionTarget(section), section.identity().ownerField()));
                owned.property("owned-link", "Поле связи", field);
            } else {
                owned.property("owned-link", "Поле связи", linkText + " · не размечено как реквизит");
            }
            owned.property("ownership-source", "Источник", ownershipSource(section));
            owners.add(owned);
        }
    }

    private void showInspector(EntitySummary.StructuralFieldRow field) {
        inspector.removeAll();
        H4 title = new H4(field.value().value() + " (" + field.identity().fieldName() + ")");
        title.getElement().setAttribute("data-inspector-field", field.identity().fieldName());
        ExplorerCardPropertyList properties = new ExplorerCardPropertyList();
        if (field.identity().section() != null) {
            var section = sectionRow(new CompactCardPlace.OwnedSection(field.identity().section().ownerField(), field.identity().section().rowType()));
            properties.property("context", "Контекст", summary.simpleName() + " / " + section.value().value());
            properties.property("owner-link", "Связь владельца", field.identity().fieldName().equals(section.identity().ownerField()) ? "Да" : "Нет");
        }
        properties.property("type", "Тип", typeLabel(field) + (field.lookup() == null ? "" : " → " + field.lookup().targetType().getSimpleName()));
        properties.property("size", field.javaType() == String.class ? "Длина значения" : "Размер значения", sizeText(field));
        properties.property("ui-required", "UI-заполнение", field.uiRequired().value() ? "Обязательно" : "Необязательно");
        properties.property("server-required", "Запись", field.serverRequired().value() ? "Значение обязательно" : "Обязательность не задана");
        properties.property("read-only", "Только чтение", field.readOnly().value() ? "Да" : "Нет");
        properties.property("hidden", "Скрыто", field.hidden().value() ? "Да" : "Нет");
        properties.property("publication", "Публикация UI",
            field.published() ? "@FieldMetadata" : "Структурно известно, не опубликовано");
        if (field.persistentKind() != null) {
            properties.property("persistence-kind", "JPA-атрибут", switch (field.persistentKind()) {
                case BASIC -> "Базовый";
                case ASSOCIATION_TO_ONE -> "Ссылка";
                case ASSOCIATION_TO_MANY -> "Коллекция";
            });
        }
        if (field.accessMode() != null) {
            properties.property("access-mode", "Доступ JPA",
                field.accessMode() == AccessMode.PROPERTY
                    ? "Свойство" : "Поле");
        }
        if (field.technicalPurpose() != null) {
            properties.property("technical-purpose", "Назначение", switch (field.technicalPurpose()) {
                case BUSINESS -> "Бизнес-реквизит";
                case IDENTIFIER -> "Идентификатор";
                case VERSION -> "Версия";
                case AUDIT -> "Аудит";
            });
        }
        properties.property("form", field.identity().section() == null ? "Форма" : "Форма строки", has(field, "FORM") ? "По метаданным" : "Не входит в metadata формы");
        properties.property("grid", field.identity().section() == null ? "Грид" : "Колонка табчасти", has(field, "GRID") ? "По метаданным" : "Не входит в metadata грида");
        properties.property("list", "Список", paths(field, "LIST"));
        properties.property("selection", "Выбор", paths(field, "SELECTION"));
        if (field.lookup() != null) {
            properties.property("lookup", "Цель выбора", field.lookup().targetType().getSimpleName());
            properties.property("lookup-variant", "Вариант", field.lookup().variant().isBlank() ? "По умолчанию" : field.lookup().variant());
        }
        ExplorerCardPropertyList sources = new ExplorerCardPropertyList();
        sources.property("label-source", "Подпись", source(field.value()));
        sources.property("publication-source", "Публикация UI",
            field.published() ? "@FieldMetadata · " + field.sourceSymbol() : "JPA metamodel · @FieldMetadata отсутствует");
        sources.property("type-source", "Тип", source(field.platformType().origin(), field.platformType().symbol()));
        sources.property("ui-source", "UI-заполнение", source(field.uiRequired().origin(), field.uiRequired().symbol()));
        sources.property("server-source", "Контракт записи", source(field.serverRequired().origin(), field.serverRequired().symbol()));
        sources.property("hidden-source", "Скрытость", source(field.hidden().origin(), field.hidden().symbol()));
        sources.property("read-only-source", "Только чтение", source(field.readOnly().origin(), field.readOnly().symbol()));
        sources.property("java-type", "Java-тип", field.javaType().getTypeName());
        sources.property("declaration", "Объявлено в", field.sourceSymbol() + (field.inherited() ? " · унаследовано" : ""));
        sources.property("order", "Порядок", field.order().value() + " · " + source(field.order().origin(), field.order().symbol()));
        for (var application : field.applications()) {
            sources.property("application-" + application.kind() + "-" + application.path(), application.kind().name(),
                application.path() + " · «" + application.caption().value() + "» · порядок " + application.order()
                    + " · " + source(application.caption()));
        }
        String formSource = (field.identity().section() == null ? summary.forms() : List.<EntitySummary.FormRow>of()).stream().filter(form -> form.formType().name().equals("ITEM"))
            .map(EntitySummary.FormRow::source).filter(value -> !value.isBlank()).distinct().collect(Collectors.joining(", "));
        sources.property("form-source", "Регистрация формы", formSource.isEmpty() ? "Декларация состава не предоставлена" : formSource);
        for (int index = 0; index < field.valueConstraints().size(); index++) {
            sources.property("size-source-" + index, "@Size", source(field.valueConstraints().get(index).declaration()));
        }
        if (field.lookup() != null) sources.property("lookup-source", "Цель выбора", source(field.lookup().value()));
        if (field.storage().column() != null) {
            var column = field.storage().column();
            sources.property("column-length", "JPA length", column.length().value() + " · " + source(column.length().origin(), column.length().symbol()));
            sources.property("column-precision", "JPA precision", column.precision().value() + " · " + source(column.precision().origin(), column.precision().symbol()));
            sources.property("column-scale", "JPA scale", column.scale().value() + " · " + source(column.scale().origin(), column.scale().symbol()));
        } else sources.property("column", "JPA Column", "Не объявлена");
        for (var nullable : field.storage().nullability()) {
            String caption = switch (nullable.kind()) {
                case COLUMN_NULLABLE -> "Column: nullable";
                case JOIN_COLUMN_NULLABLE -> "JoinColumn: nullable";
                case BASIC_OPTIONAL -> "Basic: optional";
                case MANY_TO_ONE_OPTIONAL -> "ManyToOne: optional";
                case ONE_TO_ONE_OPTIONAL -> "OneToOne: optional";
            };
            sources.property(nullable.kind().name(), caption, (nullable.allowsNull().value() ? "Допускает null" : "Не допускает null") + " · "
                + source(nullable.allowsNull().origin(), nullable.allowsNull().symbol()));
        }
        Details details = new Details("Источники и хранение", sources);
        details.setOpened(sourcesOpened);
        details.addOpenedChangeListener(e -> sourcesOpened = e.isOpened());
        Span storageNote = new Span("JPA: значения аннотаций, включая значения по умолчанию; не фактическая схема БД.");
        storageNote.addClassName("explorer-property-note");
        sources.add(storageNote);
        Span place = new Span("Место: " + summary.simpleName() + (field.identity().section() == null ? "" : "/" + field.identity().section().rowType().getSimpleName() + "[" + field.identity().section().ownerField() + "]") + "#" + field.identity().fieldName());
        place.addClassName("explorer-property-note");
        inspector.add(title, properties, details, place);
    }

    private static boolean has(EntitySummary.StructuralFieldRow field, String kind) {
        return field.applications().stream().anyMatch(a -> a.kind().name().equals(kind));
    }

    private static String paths(EntitySummary.StructuralFieldRow field, String kind) {
        String paths = field.applications().stream().filter(a -> a.kind().name().equals(kind))
            .map(a -> a.path() + " · позиция " + a.order()).collect(Collectors.joining(", "));
        return paths.isEmpty() ? "Не входит по метаданным" : paths;
    }

    private static String sizeText(EntitySummary.StructuralFieldRow field) {
        if (field.valueConstraints().isEmpty()) return "@Size не объявлена";
        return field.valueConstraints().stream().map(size -> {
            String range = size.min() == 0 ? "До " + size.max() : size.min() + "–" + size.max();
            String groups = size.groups().isEmpty() ? "" : "; группы: " + size.groups().stream().map(Class::getSimpleName).collect(Collectors.joining(", "));
            return range + " (@Size" + groups + ")";
        }).collect(Collectors.joining("; "));
    }

    private static String typeLabel(EntitySummary.StructuralFieldRow field) {
        return switch (field.platformType().value().name()) {
            case "TEXT", "TEXT_AREA", "EMAIL", "PASSWORD" -> "Строка";
            case "ENTITY_REFERENCE" -> "Ссылка";
            case "INTEGER", "DECIMAL" -> "Число";
            case "DATE" -> "Дата";
            case "DATETIME" -> "Дата и время";
            case "BOOLEAN" -> "Логический";
            case "ENUM" -> "Перечисление";
            default -> field.platformType().value().name();
        };
    }

    private String overview(String caption) {
        String value = summary.overview().stream().filter(row -> row.caption().equals(caption)).findFirst()
            .map(row -> row.value().value()).filter(candidate -> !candidate.isBlank()).orElse("Не задана");
        return switch (value) {
            case "CATALOG" -> "Справочник";
            case "DOCUMENT" -> "Документ";
            default -> value;
        };
    }

    private static String source(ResolvedValue value) {
        return (value.source().name().equals("OVERRIDE") ? "Переопределение · " : "") + source(value.origin(), value.symbol());
    }

    private static String source(FactOrigin origin, String symbol) {
        return switch (origin) {
            case EXPLICIT -> "Явное объявление";
            case BEAN_VALIDATION -> "Bean Validation";
            case JPA_MAPPING -> "JPA-маппинг";
            case JAVA_TYPE -> "Java-тип";
            case PLATFORM_DEFAULT -> "По умолчанию";
            case REGISTRATION -> "Регистрация";
            case DERIVED -> "Из метаданных";
            case UNKNOWN -> "Источник недоступен";
        } + (symbol.isBlank() ? "" : " · " + symbol);
    }

    @Tag("button")
    private static final class FieldButton extends HtmlContainer implements ClickNotifier<FieldButton> { }
}
