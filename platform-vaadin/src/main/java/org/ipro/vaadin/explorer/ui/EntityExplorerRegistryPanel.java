package org.ipro.vaadin.explorer.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.ExplorerSnapshot;

import java.util.*;
import java.util.function.Supplier;

/** Tree and overview from supplied immutable facts. State is owned by the caller, never by this component. */
@StyleSheet("context://VAADIN/static/ipro/entity-explorer-registry.css")
public final class EntityExplorerRegistryPanel extends Div {
    private record Choice(String id, String label) {}
    private ExplorerSnapshot snapshot;
    private final EntityExplorerRegistryState state;
    private final EntityExplorerRegistryActions actions;
    private final TextField search = new TextField("Наименование / имя в коде");
    private final Select<Choice> kinds = new Select<>(), subsystems = new Select<>(), exposures = new Select<>();
    private final Select<EntityExplorerRegistryState.Grouping> grouping = new Select<>();
    private final Checkbox onlyErrors = new Checkbox("Только ошибки");
    private final Checkbox serviceTypes = new Checkbox("Показать служебные типы");
    private final Details advanced = new Details();
    private final Span count = new Span(), status = new Span();
    private final Button reset = new Button("Сбросить фильтры", e -> resetFilters());
    private final Div tree = new Div(), overview = new Div(), body = new Div(), list = new Div();
    private final Div footer = new Div();
    private final MenuBar creation = new MenuBar();
    private boolean updating;
    private EntityExplorerRegistryModel.Projection projection;

    public EntityExplorerRegistryPanel(ExplorerSnapshot snapshot, EntityExplorerRegistryState state,
                                       EntityExplorerRegistryActions actions) {
        this.snapshot = Objects.requireNonNull(snapshot); this.state = Objects.requireNonNull(state);
        this.actions = Objects.requireNonNull(actions);
        addClassName("ipro-entity-registry");
        getElement().setAttribute("aria-label", "Реестр сущностей");
        var heading = new Div(new H2("Реестр сущностей"), count, createMenu());
        heading.addClassName("er-heading"); count.addClassName("er-count");
        count.getElement().setAttribute("role", "status"); count.getElement().setAttribute("aria-live", "polite");
        search.setPlaceholder("Например, Номенклатура или GroupNom"); search.setClearButtonVisible(true);
        search.setValueChangeMode(ValueChangeMode.EAGER);
        search.setValueChangeTimeout(160);
        kinds.setLabel("Вид сущности"); subsystems.setLabel("Область"); exposures.setLabel("Экспозиция");
        for (var select : List.of(kinds, subsystems, exposures)) select.setItemLabelGenerator(Choice::label);
        var filters = new Div(search, kinds, subsystems); filters.addClassName("er-filters");
        var advancedControls = new Div(onlyErrors, serviceTypes, exposures); advancedControls.addClassName("er-advanced-controls");
        advanced.setSummaryText("Дополнительные фильтры"); advanced.add(advancedControls); advanced.addClassName("er-advanced");
        var tools = new Div(advanced, reset); tools.addClassName("er-tools"); reset.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        grouping.setItems(EntityExplorerRegistryState.Grouping.values());
        grouping.setItemLabelGenerator(value -> value == EntityExplorerRegistryState.Grouping.KIND ? "По видам" : "По областям");
        grouping.setAriaLabel("Группировать сущности"); grouping.addClassName("er-grouping-select");
        var listHeading = new Div(new Span("Группировать"), grouping); listHeading.addClassName("er-list-heading");
        tree.addClassName("er-tree"); tree.getElement().setAttribute("role", "tree");
        tree.getElement().setAttribute("aria-label", "Сущности по группам");
        list.add(listHeading, tree); list.addClassName("er-list");
        overview.addClassName("er-overview"); body.add(list, overview); body.addClassName("er-body");
        status.addClassName("er-action-status"); status.getElement().setAttribute("role", "status");
        footer.addClassName("er-footer");
        add(heading, filters, tools, status, body, footer);
        configureValues();
        search.addValueChangeListener(event -> changed(() -> state.setQuery(event.getValue())));
        kinds.addValueChangeListener(event -> changed(() -> state.setKind(kindOf(event.getValue()))));
        subsystems.addValueChangeListener(event -> changed(() -> state.setSubsystem(idOf(event.getValue()))));
        exposures.addValueChangeListener(event -> changed(() -> state.setExposure(idOf(event.getValue()))));
        onlyErrors.addValueChangeListener(event -> changed(() -> state.setOnlyErrors(event.getValue())));
        serviceTypes.addValueChangeListener(event -> changed(() -> state.setShowServiceTypes(event.getValue())));
        grouping.addValueChangeListener(event -> changed(() -> state.setGrouping(event.getValue())));
        render();
        addAttachListener(event -> installClientBehavior());
        addDetachListener(event -> getElement().executeJs("this.__erResize?.disconnect(); this.__erKeys?.abort();"));
    }

    /** A new revision supplied by the host; no service, resolver or database lookup is performed. */
    public void setSnapshot(ExplorerSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot); configureValues(); render();
    }
    public void refresh() { configureValues(); render(); }

    private void changed(Runnable mutation) {
        if (updating) return;
        mutation.run(); render();
    }
    private static String idOf(Choice choice) { return choice == null || choice.id().equals("*") ? null : choice.id(); }
    private static EntityKind kindOf(Choice choice) { String id = idOf(choice); return id == null ? null : EntityKind.valueOf(id); }
    private void configureValues() {
        updating = true;
        try {
            search.setValue(state.query());
            var kindChoices = new ArrayList<Choice>(); kindChoices.add(new Choice("*", "Все виды"));
            EntityExplorerRegistryModel.kinds().forEach(kind -> kindChoices.add(new Choice(kind.name(), EntityExplorerLabels.kind(kind, false))));
            kinds.setItems(kindChoices);
            kinds.setValue(kindChoices.stream().filter(choice -> Objects.equals(idOf(choice), state.kind() == null ? null : state.kind().name())).findFirst().orElseThrow());
            Map<String, String> subsystemLabels = new TreeMap<>();
            snapshot.roots().forEach(entry -> entry.subsystem().ifPresent(sub -> subsystemLabels.put(sub.id(), sub.label().value())));
            var subsystemChoices = new ArrayList<Choice>(); subsystemChoices.add(new Choice("*", "Все подсистемы"));
            subsystemLabels.entrySet().stream().sorted(Map.Entry.<String, String>comparingByValue(String.CASE_INSENSITIVE_ORDER).thenComparing(Map.Entry.comparingByKey()))
                .forEach(sub -> subsystemChoices.add(new Choice(sub.getKey(), sub.getValue())));
            subsystemChoices.add(new Choice(EntityExplorerRegistryState.NO_SUBSYSTEM, "Без области"));
            // Keep a stale filter visible after refresh instead of silently broadening the result.
            if (state.subsystem() != null && subsystemChoices.stream().noneMatch(choice -> choice.id().equals(state.subsystem())))
                subsystemChoices.add(new Choice(state.subsystem(), "Недоступная область · " + state.subsystem()));
            subsystems.setItems(subsystemChoices);
            subsystems.setValue(subsystemChoices.stream().filter(choice -> Objects.equals(idOf(choice), state.subsystem())).findFirst().orElseThrow());
            var exposureChoices = new ArrayList<Choice>(); exposureChoices.add(new Choice("*", "Все экспозиции"));
            snapshot.roots().stream().filter(entry -> entry.descriptor() != null).map(entry -> entry.descriptor().exposure().name()).distinct().sorted()
                .forEach(exposure -> exposureChoices.add(new Choice(exposure, EntityExplorerLabels.exposure(exposure))));
            if (snapshot.roots().stream().anyMatch(entry -> entry.descriptor() == null))
                exposureChoices.add(new Choice(EntityExplorerRegistryState.UNKNOWN_EXPOSURE, "Экспозиция неизвестна"));
            if (state.exposure() != null && exposureChoices.stream().noneMatch(choice -> choice.id().equals(state.exposure())))
                exposureChoices.add(new Choice(state.exposure(), EntityExplorerLabels.exposure(state.exposure())));
            exposures.setItems(exposureChoices);
            exposures.setValue(exposureChoices.stream().filter(choice -> Objects.equals(idOf(choice), state.exposure())).findFirst().orElseThrow());
            grouping.setValue(state.grouping()); onlyErrors.setValue(state.onlyErrors()); serviceTypes.setValue(state.showServiceTypes());
        } finally { updating = false; }
    }
    private void resetFilters() { state.resetFilters(); configureValues(); render(); search.focus(); }

    private MenuBar createMenu() {
        var menu = creation; menu.removeAll(); menu.addClassName("er-create"); menu.getElement().setAttribute("aria-label", "Создание сущности");
        var create = menu.addItem("Создать новый");
        for (var kind : EntityExplorerRegistryModel.kinds()) {
            var availability = actions.createAvailability().apply(kind);
            var label = new Div(new Span(EntityExplorerLabels.kind(kind, false)));
            label.addClassName("ipro-er-create-item");
            if (!availability.allowed()) { var reason = new Span(availability.reason()); reason.addClassName("er-menu-reason"); label.add(reason); }
            var item = create.getSubMenu().addItem(label, event -> {
                var current = actions.createAvailability().apply(kind);
                if (current.allowed()) actions.create().accept(kind); else status.setText(current.reason());
            });
            if (!availability.allowed()) item.getElement().setAttribute("aria-disabled", "true");
        }
        return menu;
    }
    private void render() {
        createMenu();
        projection = EntityExplorerRegistryModel.project(snapshot, state);
        if (state.selectedType() != null && projection.entries().stream().noneMatch(entry -> entry.typeFqn().equals(state.selectedType())))
            state.select(projection.entries().isEmpty() ? null : projection.entries().getFirst().typeFqn());
        count.setText(state.hasFilters() ? "Найдено " + projection.entries().size() + " из " + projection.total()
            : EntityExplorerLabels.entities(projection.entries().size()));
        reset.setVisible(state.hasFilters());
        int extraFilters = (state.onlyErrors() ? 1 : 0) + (state.showServiceTypes() ? 1 : 0) + (state.exposure() != null ? 1 : 0);
        advanced.setSummaryText("Дополнительные фильтры" + (extraFilters == 0 ? "" : " · " + extraFilters));
        tree.removeAll();
        for (var group : projection.groups()) tree.add(group(group));
        if (projection.entries().isEmpty()) {
            var empty = new Div(new H3("Ничего не найдено"), new Paragraph("Измените условия поиска или сбросьте фильтры."));
            var clear = new Button("Сбросить фильтры", event -> resetFilters()); empty.add(clear); empty.addClassName("er-empty-list"); tree.add(empty);
        }
        renderOverview(); renderFooter();
        getElement().setAttribute("data-overview", Boolean.toString(state.overview() && state.selectedType() != null));
        tree.getElement().executeJs("this.querySelector('[aria-selected=true]')?.setAttribute('tabindex','0');");
    }
    private Div group(EntityExplorerRegistryModel.Group group) {
        var wrapper = new Div(); wrapper.addClassName("er-group");
        wrapper.getElement().setAttribute("role", "treeitem"); wrapper.getElement().setAttribute("tabindex", "-1");
        wrapper.getElement().setAttribute("aria-label", group.label() + ", " + EntityExplorerLabels.entities(group.entries().size()));
        boolean expanded = !state.collapsed(group.id());
        wrapper.getElement().setAttribute("aria-expanded", Boolean.toString(expanded));
        wrapper.getElement().setAttribute("data-group-id", group.id());
        var header = new NativeButton(); header.addClassName("er-group-heading"); header.getElement().setAttribute("tabindex", "-1");
        var chevron = new Span("›"); chevron.addClassName("er-chevron"); chevron.getElement().setAttribute("aria-hidden", "true");
        var amount = new Span(Integer.toString(group.entries().size())); amount.addClassName("er-group-count");
        header.add(chevron, new Span(group.label()), amount);
        var children = new Div(); children.addClassName("er-group-children"); children.getElement().setAttribute("role", "group"); children.setVisible(expanded);
        header.addClickListener(event -> {
            boolean open = state.collapsed(group.id()); state.setCollapsed(group.id(), !open);
            wrapper.getElement().setAttribute("aria-expanded", Boolean.toString(open)); children.setVisible(open);
        });
        for (var entry : group.entries()) children.add(entity(entry));
        wrapper.add(header, children);
        if (projection.groups().getFirst().id().equals(group.id()) && state.selectedType() == null
                || !expanded && group.entries().stream().anyMatch(entry -> entry.typeFqn().equals(state.selectedType())))
            wrapper.getElement().setAttribute("tabindex", "0");
        return wrapper;
    }
    private NativeButton entity(ExplorerSnapshot.Entry entry) {
        var button = new NativeButton(); button.addClassName("er-entity");
        boolean selected = entry.typeFqn().equals(state.selectedType());
        button.getElement().setAttribute("role", "treeitem"); button.getElement().setAttribute("aria-selected", Boolean.toString(selected));
        button.getElement().setAttribute("tabindex", selected ? "0" : "-1");
        button.getElement().setAttribute("data-type", entry.typeFqn());
        var icon = new Span(entry.kind() == EntityKind.DOCUMENT ? "▤" : entry.kind() == EntityKind.INFORMATION_REGISTER ? "▦" : "◇");
        icon.addClassName("er-type-icon"); icon.getElement().setAttribute("aria-hidden", "true");
        var name = new Span(entry.displayName().value()); name.addClassName("er-entity-name");
        var code = new Span(entry.simpleName()); code.addClassName("er-entity-code");
        var text = new Div(name, code); text.addClassName("er-entity-text");
        button.add(icon, text);
        if (!entry.countsKnown() || entry.errorCount() > 0 || entry.warningCount() > 0 || entry.state() == ExplorerSnapshot.EntryState.BUILD_FAILED) {
            var diagnostic = new Span(entry.state() == ExplorerSnapshot.EntryState.BUILD_FAILED ? "! Сбой"
                : !entry.countsKnown() ? "?" : entry.errorCount() > 0 ? "! " + entry.errorCount() + " ош." : "△ " + entry.warningCount() + " пред.");
            diagnostic.addClassName("er-diagnostic");
            if (entry.state() == ExplorerSnapshot.EntryState.BUILD_FAILED || entry.countsKnown() && entry.errorCount() > 0) diagnostic.addClassName("er-error");
            else if (entry.countsKnown()) diagnostic.addClassName("er-warning");
            diagnostic.getElement().setAttribute("aria-label", diagnosticText(entry)); button.add(diagnostic);
        }
        button.addClickListener(event -> {
            state.select(entry.typeFqn()); state.setOverview(true); render();
            getElement().executeJs("if(this.hasAttribute('data-narrow')) requestAnimationFrame(()=>this.querySelector('.er-back')?.focus()); else this.querySelector('.er-tree [aria-selected=true]')?.focus({preventScroll:true});");
        });
        return button;
    }
    private void renderOverview() {
        overview.removeAll();
        var selected = projection.entries().stream().filter(entry -> entry.typeFqn().equals(state.selectedType())).findFirst();
        if (selected.isEmpty()) {
            var symbol = new Span("◇"); symbol.addClassName("er-empty-symbol"); symbol.getElement().setAttribute("aria-hidden", "true");
            var empty = new Div(symbol, new H3(projection.entries().isEmpty() ? "Нет сущностей для просмотра" : "Выберите сущность"),
                new Paragraph(projection.entries().isEmpty() ? "Обзор появится после изменения условий поиска." : "В дереве слева выберите тип, чтобы увидеть краткий состав и доступные действия."));
            empty.addClassName("er-empty-overview"); overview.add(empty); return;
        }
        var entry = selected.orElseThrow();
        var back = new Button("‹ К списку сущностей", event -> {
            state.setOverview(false); getElement().setAttribute("data-overview", "false");
            tree.getElement().executeJs("requestAnimationFrame(()=>this.querySelector('[aria-selected=true]')?.focus({preventScroll:true}));");
        }); back.addClassName("er-back"); back.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        var eyebrow = new Span(EntityExplorerLabels.kind(entry.kind(), false) + " · " + entry.subsystem().map(sub -> sub.label().value()).orElse("Без области"));
        eyebrow.addClassName("er-eyebrow");
        var title = new H3(entry.displayName().value()); title.addClassName("er-title");
        var code = new Span(entry.simpleName()); code.addClassName("er-subtitle");
        var buttons = new Div(); buttons.addClassName("er-actions");
        buttons.add(action("Открыть карточку", true, () -> actions.cardAvailability().apply(entry.type()), () -> actions.openCard().accept(entry.type())),
            action("Изменить структуру…", false, () -> actions.modifyAvailability().apply(entry.type()), () -> actions.modify().accept(entry.type())));
        overview.add(back, eyebrow, title, code, buttons);
        var facts = new Div(); facts.addClassName("er-properties");
        property(facts, "Ключ", entry.publishedKey().orElse("Не опубликован"));
        property(facts, "Экспозиция", EntityExplorerLabels.exposure(entry.descriptor() == null ? null : entry.descriptor().exposure().name()));
        overview.add(facts, new H4("Кратко о структуре"));
        var composition = new Div(); composition.addClassName("er-properties");
        var summary = snapshot.summaryOf(entry.type());
        if (summary.isPresent() && summary.get().structure().available()) {
            var structure = summary.get().structure();
            property(composition, "Реквизиты", brief(structure.fields().stream().map(field -> field.value().value()).toList()));
            property(composition, "Табличные части", brief(entry.ownedSections().stream().map(section -> section.label().value()).toList()));
        } else {
            property(composition, "Состав", entry.state() == ExplorerSnapshot.EntryState.BUILD_FAILED ? "Сводка не собрана: " + entry.failureReason() : "Сведения о структуре недоступны");
        }
        property(composition, "Диагностика", diagnosticText(entry)); overview.add(composition);
        var technical = new Div(); technical.addClassName("er-properties");
        property(technical, "Класс", entry.typeFqn());
        property(technical, "Источник подписи", entry.displayName().origin().name() + (entry.displayName().symbol().isBlank() ? "" : " · " + entry.displayName().symbol()));
        property(technical, "Адрес типа", entry.publishedKey().map(key -> org.ipro.form.link.EntityExplorerAddress.format(key, null)).orElse("Нет опубликованного адреса"));
        var details = new Details("Технические сведения", technical); details.addClassName("er-technical"); overview.add(details);
    }
    private Div action(String label, boolean primary, Supplier<ExplorerActionAvailability> available, Runnable action) {
        var availability = available.get(); var button = new Button(label, event -> {
            var current = available.get(); if (current.allowed()) action.run(); else status.setText(current.reason());
        });
        if (primary) button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        button.setEnabled(availability.allowed()); var box = new Div(button); box.addClassName("er-action");
        if (!availability.allowed()) { var reason = new Span(availability.reason()); reason.addClassName("er-action-reason"); box.add(reason); }
        return box;
    }
    private void renderFooter() {
        footer.removeAll(); int total = snapshot.unassignedDiagnostics().size();
        boolean visible = total > 0 || actions.diagnosticsAvailability().allowed();
        if (visible) {
            var button = new Button("Общие диагностики · " + total, event -> {
                if (actions.diagnosticsAvailability().allowed()) actions.diagnostics().run();
            }); button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            button.setEnabled(actions.diagnosticsAvailability().allowed()); footer.add(button);
            if (!actions.diagnosticsAvailability().allowed()) footer.add(new Span(actions.diagnosticsAvailability().reason()));
        }
        footer.setVisible(visible);
    }
    private static void property(Div parent, String label, String value) {
        var row = new Div(new Span(label), new Span(value)); row.addClassName("er-property"); parent.add(row);
    }
    private static String brief(List<String> values) {
        if (values.isEmpty()) return "Нет";
        String result = String.join(", ", values.subList(0, Math.min(4, values.size())));
        return values.size() > 4 ? result + "; ещё " + (values.size() - 4) : result;
    }
    static String diagnosticText(ExplorerSnapshot.Entry entry) {
        if (entry.state() == ExplorerSnapshot.EntryState.BUILD_FAILED) return "Ошибка сборки сводки; счётчики недоступны";
        if (!entry.countsKnown()) return "Диагностика недоступна";
        if (entry.errorCount() == 0 && entry.warningCount() == 0) return "Ошибок и предупреждений нет";
        return "Ошибки: " + entry.errorCount() + "; предупреждения: " + entry.warningCount();
    }
    private void installClientBehavior() {
        getElement().executeJs("""
            this.__erResize?.disconnect(); this.__erKeys?.abort();
            this.__erResize = new ResizeObserver(entries => {
              const w=entries[0].contentRect.width;
              if(w>0) this.toggleAttribute('data-narrow', w<650);
            }); this.__erResize.observe(this);
            this.__erKeys=new AbortController(); const tree=this.querySelector('.er-tree');
            tree.addEventListener('focusin', e=>{
              const item=e.target.closest('[role=treeitem]'); if(!item)return;
              tree.querySelectorAll('[role=treeitem]').forEach(row=>row.tabIndex=row===item?0:-1);
            }, {signal:this.__erKeys.signal});
            tree.addEventListener('keydown',e=>{
              const item=e.target.closest('[role=treeitem]'); if(!item)return;
              const rows=[...tree.querySelectorAll('[role=treeitem]')].filter(row=>row.getClientRects().length);
              const i=rows.indexOf(item); let target;
              if(e.key==='ArrowDown')target=rows[Math.min(rows.length-1,i+1)];
              else if(e.key==='ArrowUp')target=rows[Math.max(0,i-1)];
              else if(e.key==='Home')target=rows[0]; else if(e.key==='End')target=rows.at(-1);
              else if(e.key==='ArrowRight' && item.hasAttribute('aria-expanded')){
                if(item.getAttribute('aria-expanded')==='false')item.querySelector('.er-group-heading').click();
                else target=item.querySelector('[role=group] [role=treeitem]');
              }else if(e.key==='ArrowLeft'){
                if(item.getAttribute('aria-expanded')==='true')item.querySelector('.er-group-heading').click();
                else target=item.parentElement.closest('[role=treeitem]');
              }else if((e.key==='Enter'||e.key===' ') && item.hasAttribute('aria-expanded'))item.querySelector('.er-group-heading').click();
              else return;
              e.preventDefault();target?.focus({preventScroll:false});
            },{signal:this.__erKeys.signal});
            """);
    }
}
