package org.ip.views.admin;

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.*;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftProblem;
import org.ipro.design.DraftSuggestions;
import org.ipro.design.DraftText;
import org.ipro.design.EntityDraft;
import org.ipro.design.EntityProperties;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.ReferenceTargetDescriptor;
import org.ipro.design.SectionDraft;
import org.ipro.design.OwnerDraft;
import org.ipro.design.json.DraftJsonCodec;
import org.ipro.design.json.DraftJsonException;
import org.ipro.design.generation.GenerationDiagnostic;
import org.ipro.design.generation.GenerationFile;
import org.ipro.design.generation.GenerationPlan;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.RecipeRef;
import org.ipro.form.Dirtyable;
import org.ipro.form.Savable;

import java.util.*;
import java.util.function.*;
import java.util.concurrent.atomic.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One Workspace-owned draft. No route, persistence service, Savable or runtime summary mutation. */
public class EntityDraftEditorView extends Div implements Dirtyable {
    private final EntityDraftAccess access;
    private DraftEditorSession session;
    private String owner;
    private Runnable changed = () -> {};
    private Supplier<ProjectDesignContext> contextSource = EntityDraftEditorView::uncapturedContext;
    private Map<String, String> subsystemCaptions = Map.of();
    private Runnable catalogRefresh = () -> {};
    private final Span catalogNotice = new Span(), fieldContext = new Span();
    private boolean rendering;
    private boolean kindChosen;
    private boolean confirmationOpened;
    private boolean problemListOpened;
    private UUID documentEpoch = UUID.randomUUID();
    private final DraftJsonCodec json = new DraftJsonCodec();
    private final Span transferNotice = new Span();
    private final Anchor download = new Anchor();
    private Upload currentUpload;
    private JsonExport pendingExport;
    private int downloadPolls;
    private Dialog javaPreview;
    private JavaExport javaExport;
    private final Div generationProblems = new Div();
    private final Span heading = new Span(), status = new Span(), breadcrumb = new Span();
    private final Div general = new Div(), data = new Div(), fieldRows = new Div(), inspector = new Div(), problems = new Div();
    private final Div ownerPanel = new Div();
    private UUID ownerPanelEpoch = UUID.randomUUID();
    private SplitLayout split = new SplitLayout();
    private final Button generalTab = new Button("Основные", e -> page(DraftPlace.Page.GENERAL));
    private final Button dataTab = new Button("Данные", e -> page(DraftPlace.Page.DATA));
    private final Button addField = new Button("+ Реквизит", e -> command(() -> {
        session.addField("STRING"); renderRows(); renderInspector(); showPage(); split.getElement().setAttribute("data-detail", ""); focus("name");
    }));
    private final Button up = new Button("↑", e -> move(-1));
    private final Button down = new Button("↓", e -> move(1));
    private final Button remove = new Button("Удалить", e -> removeSelected());
    private final Button addSection = new Button("+ Табчасть", e -> command(() -> {
        session.addSection(); renderRows(); renderInspector(); showPage(); split.getElement().setAttribute("data-detail", ""); focus("section.label");
    }));
    private final Map<String, Component> inputs = new LinkedHashMap<>();
    private final List<Runnable> suggestions = new ArrayList<>();
    private Runnable refreshRlsChoices = () -> {};
    private final Map<UUID, NativeButton> rows = new LinkedHashMap<>();
    private final Map<String, NativeButton> builtinRows = new LinkedHashMap<>();
    private final Map<UUID, NativeButton> sectionRows = new LinkedHashMap<>();

    public EntityDraftEditorView(EntityDraftAccess access) {
        this.access = access;
        addClassNames("entity-explorer-pilot", "entity-draft-editor", "form-1c");
        setSizeFull();
        // The app does not enable Push. A scoped RPC refreshes the title after the download response.
        getElement().addEventListener("draft-download-poll", e -> {
            if (!permitted()) return;
            refreshStatus(); changed.run();
            if (pendingExport != null && downloadPolls++ < 120) scheduleDownloadPoll();
        });
        fieldRows.addAttachListener(e -> installFieldKeyboard());
        addAttachListener(e -> activate());
        addDetachListener(e -> closeJavaPreview());
    }

    void initialize(Runnable changed) { this.changed = Objects.requireNonNull(changed); activate(); }
    /** P8.3 supplies catalog facts through this seam; no second metadata scanner here. */
    void setContextSource(Supplier<ProjectDesignContext> source) { contextSource = Objects.requireNonNull(source); }
    void setCatalogSource(Supplier<DraftProjectCatalog> source) {
        contextSource = () -> { var catalog = source.get(); subsystemCaptions = catalog.subsystems(); return catalog.context(); };
    }
    void setCatalogRefresh(Runnable refresh) { catalogRefresh = refresh; }

    public void activate() {
        String principal = access.principal();
        if (!access.allows() || principal == null) { revoke(); return; }
        if (session == null || !principal.equals(owner)) {
            documentEpoch = UUID.randomUUID();
            owner = principal; session = new DraftEditorSession(contextSource.get()); kindChosen = false;
            build();
        } else {
            ProjectDesignContext context = contextSource.get(); boolean different = !session.context().equals(context);
            session.refreshContext(context); if (different) session.validate();
            if (different) renderOwner();
            refreshSuggestions(); refreshStatus();
        }
        changed.run();
    }
    private boolean permitted() {
        if (!access.allows() || !Objects.equals(owner, access.principal()) || session == null) { activate(); return false; }
        return true;
    }
    private void revoke() {
        confirmationOpened = false;
        closeJavaPreview();
        documentEpoch = UUID.randomUUID();
        session = null; owner = null; inputs.clear(); suggestions.clear(); rows.clear(); builtinRows.clear();
        removeAll(); add(new Span("Редактор черновика недоступен")); changed.run();
    }
    public String tabTitle() { return session == null ? "Черновик · Недоступен" : !kindChosen ? "Черновик · Выбор вида" : session.title(); }
    @Override public boolean isDirty() { return session != null && session.dirty(); }
    @Override public String getCloseConfirmMessage() { return "Есть невыгруженные изменения черновика. Закрыть без выгрузки JSON?"; }
    DraftEditorSession session() { return session; }

    private void build() {
        confirmationOpened = false;
        closeJavaPreview();
        pendingExport = null;
        problemListOpened = false;
        removeAll(); general.removeAll(); inspector.removeAll(); fieldRows.removeAll(); problems.removeAll();
        inputs.clear(); suggestions.clear(); rows.clear(); builtinRows.clear();
        heading.addClassName("explorer-entity-title"); status.addClassName("draft-status");
        Button fresh = new Button("Новый", e -> newDraft());
        Button validate = new Button("Проверить", e -> command(() -> session.validate()));
        Button refresh = new Button("Обновить каталог", e -> command(() -> { catalogRefresh.run(); session.refreshContext(contextSource.get()); }));
        Button export = new Button("Выгрузить JSON", e -> exportJson());
        Button preview = ownedButton("Показать Java", this::showJava);
        validate.setEnabled(kindChosen); preview.setEnabled(kindChosen); export.setEnabled(kindChosen);
        Upload upload = jsonUpload();
        download.setVisible(false);
        Div toolbar = new Div(heading, fresh, upload, export, validate, preview, refresh, status, download); toolbar.addClassName("draft-toolbar");
        transferNotice.setText(""); transferNotice.addClassName("draft-transfer-notice"); transferNotice.getElement().setAttribute("role", "status");
        Div nav = new Div(generalTab, dataTab); nav.addClassName("draft-nav");
        general.addClassName("draft-general"); data.addClassName("draft-data");
        renderGeneral();
        up.setAriaLabel("Переместить реквизит вверх"); down.setAriaLabel("Переместить реквизит вниз");
        Button root = new Button(session.rootCaption(), e -> selectBuiltin(session.defaultBuiltin())); root.setTooltipText("Добавлять реквизиты: " + session.rootCaption());
        Div commands = new Div(addField, addSection, up, down, remove, root, fieldContext); commands.addClassName("draft-field-commands");
        Div columns = new Div(new Span("Имя"), new Span("Подпись"), new Span("Тип")); columns.addClassName("draft-field-columns");
        fieldRows.addClassName("draft-field-scroll"); fieldRows.getElement().setAttribute("role", "listbox");
        fieldRows.getElement().setAttribute("aria-label", "Реквизиты: " + session.rootCaption());
        fieldRows.getElement().setAttribute("aria-description", "Стрелки и Home/End перемещают фокус; Enter открывает свойства строки");

        Div list = new Div(commands, columns, fieldRows); list.addClassName("draft-field-list");
        inspector.addClassName("draft-inspector");
        split = new SplitLayout(); split.addClassName("draft-split"); split.addToPrimary(list);
        ExplorerSequentialLayout.install(split, list, inspector);
        data.removeAll(); data.add(split);
        Div pages = new Div(general, data); pages.addClassName("draft-pages");
        Div body = new Div(nav, pages); body.addClassName("draft-body");
        body.setVisible(kindChosen);
        Div start = new Div(new H4("Выберите вид нового черновика")); start.addClassName("draft-kind-choice"); start.setVisible(!kindChosen);
        UUID epoch = documentEpoch;
        for (var descriptor : RecipeCatalog.descriptors().stream().filter(d -> d.draftSupported() && d.generatorImplemented()).toList()) {
            String caption = recipeCaption(descriptor);
            start.add(new Button(caption, e -> {
                if (permitted() && epoch.equals(documentEpoch) && !kindChosen) confirmNewDraft(descriptor.ref());
            }));
        }
        breadcrumb.addClassName("draft-breadcrumb");
        problems.addClassName("draft-problems");
        generationProblems.getElement().setAttribute("role", "status");
        add(toolbar, transferNotice, start, body, problems, generationProblems, breadcrumb);
        renderRows(); renderInspector(); showPage(); refreshSuggestions(); refreshStatus();
    }

    private void installFieldKeyboard() {
        fieldRows.getElement().executeJs("""
            this.__draftKeys?.abort();
            this.__draftKeys = new AbortController();
            this.addEventListener('keydown', event => {
              const current = event.target.closest('.draft-field-row');
              if (!current || !['ArrowUp','ArrowDown','Home','End'].includes(event.key)) return;
              const rows = Array.from(this.querySelectorAll('.draft-field-row'));
              const index = rows.indexOf(current);
              const next = event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1
                : Math.max(0, Math.min(rows.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)));
              event.preventDefault();
              rows.forEach(row => row.tabIndex = -1);
              const target = rows[next]; target.tabIndex = 0; target.focus({preventScroll:true});
              const row = target.getBoundingClientRect(), box = this.getBoundingClientRect();
              if (row.top < box.top) this.scrollTop -= box.top - row.top;
              else if (row.bottom > box.bottom) this.scrollTop += row.bottom - box.bottom;
            }, {signal:this.__draftKeys.signal});
            """);
    }

    private void renderGeneral() {
        refreshRlsChoices = () -> {};
        general.removeAll();
        var properties = new ExplorerPropertyList();
        text(properties, "className", "Java-класс", session.draft().entity().className(), value -> entity("className", value));
        text(properties, "label", "Название", session.draft().entity().label(), value -> entity("label", value));
        if (session.register()) {
            Select<String> period = new Select<>(); period.setItems(session.descriptor().periodModes().stream().map(mode -> mode.id()).toList()); period.setValue(session.draft().register().period());
            period.setAriaLabel("Период регистра");
            period.setItemLabelGenerator(v -> session.descriptor().periodModes().stream().filter(mode -> mode.id().equals(v)).map(mode -> mode.label()).findFirst().orElse(v));
            period.addValueChangeListener(e -> edit(e.getSource(), () -> { session.period(e.getValue()); renderRows(); renderInspector(); }, true));
            properties.property("register.period", "Период", period); registerInput("register.period", period);
        }
        general.add(properties);
        if (RecipeCatalog.INDEPENDENT_REGISTER_RLS.equals(session.draft().recipe())) renderRls();
        var advanced = new ExplorerPropertyList();
        auto(advanced, "packageName", "Пакет", () -> session.draft().entity().packageName(),
            () -> DraftSuggestions.rootPackage(session.draft(), session.context()), value -> entityAuto("packageName", value));
        auto(advanced, "tableName", "Таблица", () -> session.draft().entity().tableName(),
            () -> DraftSuggestions.rootTable(session.draft()), value -> entityAuto("tableName", value));
        auto(advanced, "titles.list", "Заголовок списка", () -> session.draft().entity().titles().list(),
            () -> session.draft().entity().titles().list().resolve(session.draft().entity().label()), value -> entityAuto("titles.list", value));
        auto(advanced, "titles.item", "Заголовок элемента", () -> session.draft().entity().titles().item(),
            () -> session.draft().entity().titles().item().resolve(session.draft().entity().label()), value -> entityAuto("titles.item", value));
        auto(advanced, "titles.selection", "Заголовок выбора", () -> session.draft().entity().titles().selection(),
            () -> session.draft().entity().titles().selection().resolve(session.draft().entity().label()), value -> entityAuto("titles.selection", value));
        String existingSubsystem = session.draft().entity().subsystemId();
        if (existingSubsystem != null) {
            ComboBox<String> subsystem = new ComboBox<>(); subsystem.setAriaLabel("Подсистема");
            subsystem.setItems(existingSubsystem); subsystem.setValue(existingSubsystem); subsystem.setClearButtonVisible(true);
            subsystem.addValueChangeListener(e -> edit(e.getSource(), () -> entity("subsystemId", Objects.requireNonNullElse(e.getValue(), "")), true));
            registerInput("subsystemId", subsystem); advanced.property("subsystemId", "Подсистема · не поддерживается профилем", subsystem);
            advanced.property("subsystem.reason", "", "Импортированное значение сохранено. Текущий Java-профиль не представляет подсистему; её можно явно удалить.");
        }
        Select<String> icon = new Select<>(); var icons = new TreeSet<>(Arrays.stream(VaadinIcon.values()).map(Enum::name).toList());
        icons.add(session.draft().entity().icon()); icon.setItems(icons); icon.setValue(session.draft().entity().icon()); icon.setAriaLabel("Значок");
        icon.addValueChangeListener(e -> edit(e.getSource(), () -> entity("icon", e.getValue()), false));
        registerInput("icon", icon); advanced.property("icon", "Значок", icon);
        Details extra = new Details("Дополнительно", advanced); extra.addClassName("draft-advanced"); general.add(extra);
        general.add(ownerPanel); renderOwner();
        var descriptor = session.descriptor();
        Details recipe = new Details(descriptor.displayName() + " · параметры рецепта", new Span(descriptor.description()),
            new Span("База: " + descriptor.effectiveProfile(session.draft()).baseType()),
            new Span("Нумерация: " + session.builtins().stream().map(RecipeCatalog.InheritedField::numberingRole).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(", "))),
            new Span("Профиль: " + descriptor.persistenceProfile() + " · Запись: " + descriptor.writeProfile()),
            new Span(String.join("; ", descriptor.constraints())), new Span("Описание профиля не выдаёт прав. C/D проверяются отдельно."));
        recipe.addClassName("draft-recipe"); general.add(recipe);
        catalogNotice.addClassName("explorer-property-note"); general.add(catalogNotice);
    }
    private static String recipeCaption(org.ipro.design.RecipeDescriptor descriptor) {
        if (RecipeCatalog.INDEPENDENT_REGISTER_RLS.equals(descriptor.ref())) return "Регистр сведений с RLS";
        return switch(descriptor.kind()) { case "DOCUMENT" -> "Документ"; case "INFORMATION_REGISTER" -> "Регистр сведений"; default -> "Справочник"; };
    }
    private void renderRls() {
        var policy=session.draft().register().rls();
        var properties=new ExplorerPropertyList();
        text(properties,"register.rls.dimension","Измерение доступа",policy==null ? "" : policy.dimension(),
            value -> session.rls(value,session.draft().register().rls()==null ? null : session.draft().register().rls().fieldId()));
        ComboBox<UUID> field=new ComboBox<>();field.setAriaLabel("Ссылочное измерение доступа");
        field.setItemLabelGenerator(id -> session.draft().fields().stream().filter(f -> f.id().equals(id)).map(f -> f.name()+" · "+f.label()).findFirst().orElse("Недоступный реквизит: "+id));
        refreshRlsChoices=() -> {
            boolean previous=rendering; rendering=true;
            try {
                var current=session.draft().register().rls();var ids=new LinkedHashSet<UUID>();
                session.draft().fields().stream().filter(f -> "REFERENCE".equals(f.type()) && session.draft().register().dimensions().contains(f.id())).forEach(f -> ids.add(f.id()));
                if(current!=null && current.fieldId()!=null) ids.add(current.fieldId());
                field.setItems(ids);field.setValue(current==null ? null : current.fieldId());
            } finally {rendering=previous;}
        };
        refreshRlsChoices.run();field.setClearButtonVisible(true);
        field.addValueChangeListener(e -> edit(e.getSource(),() -> session.rls(session.draft().register().rls()==null ? "" : session.draft().register().rls().dimension(),e.getValue()),false));
        registerInput("register.rls.fieldId",field);properties.property("register.rls.fieldId","Ссылочное измерение доступа",field);
        properties.property("register.rls.contract","Доступ к строкам","Политика использует id выбранной ссылки (Long). Права выдаёт приложение; генератор их не создаёт.");
        general.add(new Details("Доступ к строкам",properties));
    }

    private void entity(String key, String value) {
        session.editEntity(e -> new EntityProperties(key.equals("className") ? value : e.className(), e.packageName(),
            key.equals("label") ? value : e.label(), e.titles(), e.tableName(),
            key.equals("subsystemId") ? (value.isEmpty() ? null : value) : e.subsystemId(), key.equals("icon") ? value : e.icon()));
    }

    private boolean allowedOwner(String id) {
        var context = session.context(); var target = context.referenceTargets().get(id);
        var facts = context.runtimeSnapshot().types().get(id);
        return context.runtimeSnapshot().complete() && target != null && target.supported()
            && facts != null && "CATALOG".equals(facts.kind()) && "STANDARD_ROOT".equals(facts.exposure());
    }
    private record OwnerSuggestion(Runnable delegate) implements Runnable { public void run() { delegate.run(); } }
    private void renderOwner() {
        UUID epoch = ownerPanelEpoch = UUID.randomUUID();
        boolean previous = rendering; rendering = true;
        try {
            suggestions.removeIf(s -> s instanceof OwnerSuggestion);
            inputs.keySet().removeIf(k -> k.equals("owner") || k.startsWith("owner.")); ownerPanel.removeAll();
            ownerPanel.add(new H4("Владелец справочника"));
            var current = session.draft().owner();
            boolean supported = session.supports(org.ipro.design.RecipeDescriptor.EditorConstruction.OWNER);
            ownerPanel.setVisible(supported || current != null);
            if (!supported && current == null) return;
            Checkbox enabled = new Checkbox("Подчинённый справочник"); enabled.setValue(current != null); registerInput("owner", enabled);
            enabled.addValueChangeListener(e -> edit(e.getSource(), () -> {
                session.editOwner(e.getValue() ? new OwnerDraft("", "counterparty", DraftText.auto(), "Контрагент") : null);
                renderOwner();
            }, true));
            if (!supported) enabled.setLabel("Удалите недопустимого владельца этого рецепта");
            ownerPanel.add(enabled);
            if (current == null) return;
            var properties = new ExplorerPropertyList();
            ComboBox<String> picker = new ComboBox<>(); picker.setAriaLabel("Тип владельца"); picker.setClearButtonVisible(true);
            var choices = new TreeSet<String>();
            session.context().referenceTargets().keySet().stream().filter(this::allowedOwner).forEach(choices::add);
            if (!current.targetTypeId().isEmpty()) choices.add(current.targetTypeId());
            picker.setItems(choices); picker.setItemLabelGenerator(id -> {
                var target = session.context().referenceTargets().get(id);
                return (allowedOwner(id) ? "" : "Недоступно · ") + (target == null ? id : target.caption() + " · " + id);
            });
            picker.setValue(current.targetTypeId().isEmpty() ? null : current.targetTypeId());
            picker.addValueChangeListener(e -> edit(e.getSource(), () -> updateOwner(epoch, "target", e.getValue() == null ? "" : e.getValue(), null), true));
            registerInput("owner.targetTypeId", picker); properties.property("owner.targetTypeId", "Тип владельца", picker);
            Span reason = new Span();
            Runnable updateReason = () -> {
                var id = session.draft().owner().targetTypeId(); var descriptor = session.context().referenceTargets().get(id);
                reason.setText(id.isEmpty() || allowedOwner(id) ? "" : "Цель недоступна: нужен существующий справочник с Long ID, представлением и выбором. "
                    + (descriptor == null ? "Тип отсутствует в текущем каталоге; значение сохранено." : descriptor.reason()));
            };
            suggestions.add(new OwnerSuggestion(updateReason)); updateReason.run(); properties.property("owner.availability", "", reason);
            text(properties, "owner.fieldName", "Имя поля", current.fieldName(), value -> updateOwner(epoch, "field", value, null));
            text(properties, "owner.label", "Подпись", current.label(), value -> updateOwner(epoch, "label", value, null));
            int start = suggestions.size();
            auto(properties, "owner.columnName", "Колонка", () -> session.draft().owner().columnName(),
                () -> DraftSuggestions.ownerColumn(session.draft().owner()), value -> updateOwner(epoch, "column", null, value));
            for (int i = start; i < suggestions.size(); i++) suggestions.set(i, new OwnerSuggestion(suggestions.get(i)));
            properties.property("owner.contract", "Правило", "Обязателен. Выбирается при создании; после сохранения не меняется. Права не наследуются.");
            ownerPanel.add(properties);
            if (!supported) properties.setEnabled(false);
        } finally { rendering = previous; }
    }
    private void updateOwner(UUID epoch, String key, String value, DraftText column) {
        var old = session.draft().owner(); if (!ownerPanelEpoch.equals(epoch) || old == null
            || !RecipeCatalog.STANDARD_CATALOG_V2.equals(session.draft().recipe())) return;
        session.editOwner(new OwnerDraft(key.equals("target") ? value : old.targetTypeId(), key.equals("field") ? value : old.fieldName(),
            key.equals("column") ? column : old.columnName(), key.equals("label") ? value : old.label()));
    }
    private void entityAuto(String key, DraftText value) {
        session.editEntity(e -> new EntityProperties(e.className(), key.equals("packageName") ? value : e.packageName(), e.label(),
            new EntityProperties.Titles(key.equals("titles.list") ? value : e.titles().list(), key.equals("titles.item") ? value : e.titles().item(),
                key.equals("titles.selection") ? value : e.titles().selection()), key.equals("tableName") ? value : e.tableName(), e.subsystemId(), e.icon()));
    }

    private TextField text(ExplorerPropertyList properties, String key, String caption, String value, Consumer<String> setter) {
        TextField input = new TextField(); input.setAriaLabel(caption); input.setValue(value); input.setWidthFull();
        input.setValueChangeMode(ValueChangeMode.EAGER);
        input.getElement().setAttribute("data-draft-input", key);
        input.addValueChangeListener(e -> edit(e.getSource(), () -> setter.accept(e.getValue()), false));
        input.addBlurListener(e -> { if (permitted()) { session.validate(); refreshSuggestions(); refreshStatus(); } });
        registerInput(key, input); properties.property(key, caption, input); return input;
    }
    private void auto(ExplorerPropertyList properties, String key, String caption, Supplier<DraftText> source, Supplier<String> effective, Consumer<DraftText> setter) {
        var slot = new ExplorerPropertyList();
        TextField input = text(slot, key, caption, effective.get(), value -> setter.accept(DraftText.explicit(value)));
        Button reset = ownedButton("Авто", () -> setter.accept(DraftText.auto()));
        reset.getElement().setAttribute("data-draft-reset", key);
        reset.setAriaLabel(caption + ": по предложению"); reset.setTooltipText("Следовать предложению");
        Div value = new Div(input, reset); value.addClassName("draft-auto"); properties.property(key, caption, value);
        Runnable refresh = () -> {
            if (!input.getElement().getProperty("focused", false)) input.setValue(effective.get());
            boolean auto = source.get().mode() == DraftText.Mode.AUTO;
            reset.setEnabled(!auto); reset.setText(auto ? "Авто" : "↶ Авто");
            input.getElement().setAttribute("data-mode", source.get().mode().name());
        };
        suggestions.add(refresh); refresh.run();
    }

    private void renderRows() {
        fieldRows.removeAll(); rows.clear(); builtinRows.clear(); sectionRows.clear();
        for (var inherited : session.builtins()) {
            NativeButton row = row(inherited.name(), inherited.label(), (inherited.type() == RecipeCatalog.FieldType.DATE ? "Дата" : "Текст") + " · база");
            row.addClassName("draft-inherited"); row.addClickListener(e -> selectBuiltin(inherited.key()));
            row.getElement().setAttribute("data-builtin", inherited.key()); builtinRows.put(inherited.key(), row); fieldRows.add(row);
        }
        fieldRows(null, session.draft().fields());
        for (var section : session.draft().sections()) {
            NativeButton toggle = new NativeButton(session.expanded(section.id()) ? "▾" : "▸");
            toggle.getElement().setAttribute("aria-label", "Свернуть или раскрыть табчасть");
            toggle.getElement().setAttribute("aria-expanded", Boolean.toString(session.expanded(section.id())));
            toggle.addClassName("draft-section-toggle");
            toggle.addClickListener(e -> command(() -> { session.toggleSection(section.id()); renderRows(); renderInspector(); }));
            NativeButton row = row(section.rowClassName(), section.label(), "Табчасть");
            row.getElement().setAttribute("data-section-id", section.id().toString());
            row.addClickListener(e -> command(() -> { session.selectSection(section.id()); renderInspector(); showPage(); ExplorerSequentialLayout.showDetail(split); }));
            sectionRows.put(section.id(), row); Div header = new Div(toggle, row); header.addClassName("draft-section-header"); fieldRows.add(header);
            if (session.expanded(section.id())) fieldRows(section.id(), section.fields());
        }
        if (session.draft().fields().isEmpty() && session.draft().sections().isEmpty()) {
            Span empty = new Span("Собственных реквизитов пока нет. Добавьте первый."); empty.addClassName("draft-empty"); fieldRows.add(empty);
        }
        updateRows();
        scrollSelectionIntoView();
    }
    private void fieldRows(UUID section, List<FieldDraft> fields) {
        for (var field : fields) {
            NativeButton row = row(field.name(), field.label(), typeCaption(field.type()));
            row.addClickListener(e -> selectField(section, field.id())); row.getElement().setAttribute("data-field-id", field.id().toString());
            if (section != null) row.addClassName("draft-section-field");
            rows.put(field.id(), row); fieldRows.add(row);
        }
    }

    private void scrollSelectionIntoView() {
        Component selected = session.place().fieldId() != null ? rows.get(session.place().fieldId()) : session.place().sectionId() != null ? sectionRows.get(session.place().sectionId()) : builtinRows.get(session.place().builtinKey());
        if (selected != null) selected.getElement().executeJs("""
            requestAnimationFrame(() => {
              const list = this.closest('.draft-field-scroll');
              if (!list || !this.getClientRects().length) return;
              const row = this.getBoundingClientRect(), box = list.getBoundingClientRect();
              if (row.top < box.top) list.scrollTop -= box.top - row.top;
              else if (row.bottom > box.bottom) list.scrollTop += row.bottom - box.bottom;
            });
            """);
    }
    private NativeButton row(String name, String label, String type) {
        NativeButton button = new NativeButton(); button.add(new Span(name.isEmpty() ? "Без имени" : name), new Span(label), new Span(type));
        button.addClassName("draft-field-row"); button.getElement().setAttribute("role", "option"); button.getElement().setAttribute("tabindex", "-1"); return button;
    }
    private void updateRows() {
        List<FieldDraft> allFields = new ArrayList<>(session.draft().fields()); session.draft().sections().forEach(s -> allFields.addAll(s.fields()));
        for (var f : allFields) {
            NativeButton row = rows.get(f.id()); if (row == null) continue;
            var labels = row.getChildren().filter(c -> c instanceof Span).map(c -> (Span)c).toList();
            labels.get(0).setText(f.name().isEmpty() ? "Без имени" : f.name()); labels.get(1).setText(f.label()); labels.get(2).setText(typeCaption(f.type()));
            Span indicators;
            if (labels.size() < 4) { indicators = new Span(); indicators.addClassName("draft-field-indicators"); row.add(indicators); }
            else indicators = labels.get(3);
            var a = f.applications();
            String role = session.register() ? session.descriptor().fieldRoles().stream().filter(r -> r.id().equals(session.role(f.id()))).map(r -> r.label()).findFirst().orElse("Роль не назначена") : "Реквизит";
            String flags = role + " · " + (f.serverRequired() ? "Запись: обязательно" : "Запись: необязательно")
                + " · Форма " + (a.form() ? "+" : "−") + " · Список " + (a.list() ? "+" : "−")
                + " · Выбор " + (a.selection() ? "+" : "−") + " · Таблица " + (a.table() ? "+" : "−");
            indicators.setText(flags); indicators.getElement().setAttribute("aria-label", flags);
            row.getElement().setAttribute("aria-selected", Boolean.toString(f.id().equals(session.place().fieldId())));
            row.getElement().setAttribute("title", f.name() + " · " + f.label() + " · " + typeCaption(f.type()));
        }
        builtinRows.forEach((key, row) -> row.getElement().setAttribute("aria-selected", Boolean.toString(key.equals(session.place().builtinKey()))));
        for (var s : session.draft().sections()) {
            NativeButton row = sectionRows.get(s.id()); if (row == null) continue;
            var labels = row.getChildren().filter(c -> c instanceof Span).map(c -> (Span)c).toList();
            labels.get(0).setText(s.rowClassName().isEmpty() ? "Новая табчасть" : s.rowClassName()); labels.get(1).setText(s.label());
            row.getElement().setAttribute("aria-selected", Boolean.toString(s.id().equals(session.place().sectionId()) && session.place().fieldId() == null));
            row.getElement().setAttribute("title", s.rowClassName() + " · " + s.label());
        }
        // One list tab stop; arrow navigation stays local and Enter uses the native button action.
        List<NativeButton> visible = fieldRows.getChildren().flatMap(c -> c instanceof NativeButton b ? java.util.stream.Stream.of(b)
            : c.getChildren().filter(NativeButton.class::isInstance).map(NativeButton.class::cast).filter(b -> b.hasClassName("draft-field-row"))).toList();
        NativeButton tabStop = visible.stream().filter(b -> "true".equals(b.getElement().getAttribute("aria-selected"))).findFirst().orElse(visible.isEmpty() ? null : visible.getFirst());
        visible.forEach(b -> b.getElement().setAttribute("tabindex", b == tabStop ? "0" : "-1"));
        boolean own = session.place().fieldId() != null, section = session.place().sectionId() != null && !own;
        int index = own ? session.fields(session.place().sectionId()).indexOf(session.selectedField()) : section ? session.draft().sections().indexOf(session.section(session.place().sectionId())) : -1;
        int count = own ? session.fields(session.place().sectionId()).size() : session.draft().sections().size();
        up.setEnabled((own || section) && index > 0); down.setEnabled((own || section) && index + 1 < count); remove.setEnabled(own || section);
        up.setAriaLabel(section ? "Переместить табчасть вверх" : "Переместить реквизит вверх"); down.setAriaLabel(section ? "Переместить табчасть вниз" : "Переместить реквизит вниз");
        fieldContext.setText("В: " + (session.place().sectionId() == null ? session.rootCaption() : sectionCaption(session.section(session.place().sectionId()))));
        fieldContext.getElement().setAttribute("title", fieldContext.getText());
        addField.setEnabled(allFields.size() < RecipeCatalog.MAX_FIELDS);
        addSection.setVisible(session.supports(org.ipro.design.RecipeDescriptor.EditorConstruction.SECTIONS));
        addSection.setEnabled(addSection.isVisible() && session.draft().sections().size() < RecipeCatalog.MAX_SECTIONS);
    }
    private void selectField(UUID section, UUID id) { command(() -> { session.selectField(section, id); renderInspector(); showPage(); ExplorerSequentialLayout.showDetail(split); }); }
    private void selectBuiltin(String key) { command(() -> { session.selectBuiltin(key); renderInspector(); showPage(); ExplorerSequentialLayout.showDetail(split); }); }
    private void renderInspector() {
        inspector.removeAll();
        // Main property inputs stay mounted; only the selected field's controls are replaced.
        inputs.keySet().removeIf(key -> !key.equals("owner") && !key.startsWith("owner.") && !key.startsWith("register.rls.") && !Set.of("className", "packageName", "label", "titles.list", "titles.item", "titles.selection", "tableName", "subsystemId", "icon", "register.period").contains(key));
        suggestions.removeIf(r -> r instanceof FieldSuggestion);
        boolean previous = rendering; rendering = true;
        try {
            var inherited = session.builtins().stream().filter(f -> f.key().equals(session.place().builtinKey())).findFirst();
            if (inherited.isPresent()) {
                var f = inherited.get(); var properties = new ExplorerPropertyList();
                inspector.add(new H4(f.name() + " · " + f.label()));
                properties.property("inherited", "Источник", "Из базы выбранного профиля"); properties.property("type", "Тип", switch(f.type()) { case DATE -> "LocalDate"; case DATETIME -> "LocalDateTime"; default -> typeCaption(f.type().name()); });
                if (f.length() > 0) properties.property("length", "Длина", Integer.toString(f.length())); properties.property("required", "Запись", "Обязательно");
                if (f.numberingRole() != null) properties.property("numbering", "Нумерация", f.numberingRole());
                inspector.add(properties, new Span("Параметры унаследованы и в этом рецепте не редактируются.")); return;
            }
            if (session.place().sectionId() != null && session.place().fieldId() == null) { renderSectionInspector(); return; }
            if (session.place().fieldId() == null) { inspector.add(new Span("Выберите реквизит или добавьте собственный.")); return; }
            var f = session.selectedField(); UUID id = f.id();
            inspector.add(new H4(fieldPath(f)));
            var properties = new ExplorerPropertyList();
            if (session.register()) {
                Select<String> role = new Select<>(); var roles = new ArrayList<>(List.of("UNASSIGNED")); roles.addAll(session.descriptor().fieldRoles().stream().map(r -> r.id()).toList()); role.setItems(roles);
                role.setAriaLabel("Роль реквизита"); role.setValue(session.role(id));
                role.setItemLabelGenerator(v -> session.descriptor().fieldRoles().stream().filter(r -> r.id().equals(v)).map(r -> r.label()).findFirst().orElse("Назначьте роль"));
                role.addValueChangeListener(e -> edit(e.getSource(), () -> { session.role(id, e.getValue()); renderInspector(); }, true));
                registerInput("register.role", role); properties.property("register.role", "Роль", role);
                properties.property("register.role.help", "", session.descriptor().fieldRoles().stream()
                    .filter(r -> r.id().equals(session.role(id))).map(r -> r.description()).findFirst().orElse("Каждому полю назначается одна роль"));
            }
            text(properties, "name", "Имя", f.name(), value -> session.editField(id, old -> old.withName(value)));
            // Field label uses a separate key to keep the root label input mounted.
            text(properties, "field.label", "Подпись", f.label(), value -> session.editField(id, old -> copy(old, "label", value)));
            Select<String> type = new Select<>();
            var allowedTypes = session.descriptor().fieldTypes(session.role(id)).stream().map(Enum::name).toList();
            var types = new ArrayList<>(allowedTypes);
            if (!types.contains(f.type())) types.add(f.type()); type.setItems(types); type.setItemLabelGenerator(value -> (allowedTypes.contains(value) ? "" : "Недоступно · ") + typeCaption(value)); type.setValue(f.type()); type.setAriaLabel("Тип реквизита");
            type.addValueChangeListener(e -> edit(e.getSource(), () -> { session.editField(id, old -> old.withType(e.getValue())); renderInspector(); }, true));
            properties.property("type", "Тип", type); registerInput("type", type);
            type.getElement().setAttribute("data-draft-input", "type");
            boolean string = f.type().equals("STRING"), decimal = f.type().equals("DECIMAL");
            constraint(properties, id, "length", "Длина", f.constraints().length(), string);
            constraint(properties, id, "precision", "Точность", f.constraints().precision(), decimal);
            constraint(properties, id, "scale", "Дробных разрядов", f.constraints().scale(), decimal);
            Checkbox required = new Checkbox(); required.setAriaLabel("Обязателен при записи"); required.setValue(f.serverRequired());
            required.addValueChangeListener(e -> edit(e.getSource(), () -> session.editField(id, old -> new FieldDraft(old.id(), old.name(), old.label(), old.type(), old.columnName(), e.getValue(), old.uiRequired(), old.constraints(), old.reference(), old.applications())), true));
            registerInput("serverRequired", required);
            properties.property("serverRequired", "Обязателен при записи", required);
            Select<FieldDraft.UiRequired> ui = new Select<>(); ui.setItems(FieldDraft.UiRequired.values()); ui.setValue(f.uiRequired()); ui.setAriaLabel("Обязательность UI");
            ui.setItemLabelGenerator(v -> switch (v) { case AUTO -> "Авто"; case REQUIRED -> "Обязательно"; case OPTIONAL -> "Необязательно"; });
            ui.addValueChangeListener(e -> edit(e.getSource(), () -> session.editField(id, old -> new FieldDraft(old.id(), old.name(), old.label(), old.type(), old.columnName(), old.serverRequired(), e.getValue(), old.constraints(), old.reference(), old.applications())), true));
            var uiProperties = new ExplorerPropertyList();
            uiProperties.property("uiRequired", "Обязательность UI", ui);
            uiProperties.property("uiRequired.help", "", "AUTO: форма следует обязательности при записи. Настройка UI не меняет nullable/validation.");
            Details uiExtra = new Details("Дополнительно · форма", uiProperties);
            registerInput("uiRequired", ui);
            // A small wrapper marks field-specific suggestion bindings for disposal on selection.
            int start = suggestions.size();
            auto(properties, "columnName", "Колонка", () -> session.selectedField().columnName(), () -> DraftSuggestions.column(session.selectedField()),
                value -> session.editField(id, old -> new FieldDraft(old.id(), old.name(), old.label(), old.type(), value, old.serverRequired(), old.uiRequired(), old.constraints(), old.reference(), old.applications())));
            for (int i = start; i < suggestions.size(); i++) suggestions.set(i, new FieldSuggestion(suggestions.get(i)));
            if (f.type().equals("REFERENCE") || f.reference() != null) {
                reference(properties, id);
            }
            Div apps = new Div(); apps.addClassName("draft-applications");
            application(apps, id, "form", "Форма", f.applications().form());
            boolean row = session.place().sectionId() != null;
            if (!row || f.applications().list()) application(apps, id, "list", row ? "Список · неприменимо" : "Список", f.applications().list());
            if (!row || f.applications().selection()) application(apps, id, "selection", row ? "Выбор · неприменимо" : "Выбор", f.applications().selection());
            if (row || f.applications().table()) application(apps, id, "table", row ? "Таблица секции" : "Таблица · неприменимо", f.applications().table());
            properties.property("applications", "Применение", apps); inspector.add(properties, uiExtra);
        } finally { rendering = previous; }
    }
    private record FieldSuggestion(Runnable delegate) implements Runnable { public void run() { delegate.run(); } }
    private String sectionCaption(SectionDraft s) { return s.label().isEmpty() ? s.rowClassName().isEmpty() ? "Новая табчасть" : s.rowClassName() : s.label(); }
    private String fieldPath(FieldDraft f) { return (session.place().sectionId() == null ? session.rootCaption() : sectionCaption(session.section(session.place().sectionId()))) + " / " + (f.name().isEmpty() ? "Новый реквизит" : f.name()); }
    private void renderSectionInspector() {
        var s = session.section(session.place().sectionId()); UUID id = s.id();
        inspector.add(new H4("Табчасть / " + sectionCaption(s)));
        var properties = new ExplorerPropertyList();
        text(properties, "section.label", "Название табчасти", s.label(), value -> session.editSection(id, old -> new SectionDraft(old.id(), value, old.rowClassName(), old.packageName(), old.tableName(), old.parentFieldName(), old.parentColumnName(), old.fields())));
        text(properties, "rowClassName", "Класс строки", s.rowClassName(), value -> session.editSection(id, old -> new SectionDraft(old.id(), old.label(), value, old.packageName(), old.tableName(), old.parentFieldName(), old.parentColumnName(), old.fields())));
        int start = suggestions.size();
        auto(properties, "section.packageName", "Пакет строки", () -> session.section(id).packageName(),
            () -> DraftSuggestions.rowPackage(session.section(id), DraftSuggestions.rootPackage(session.draft(), session.context())), value -> sectionAuto(id, "packageName", value));
        auto(properties, "section.tableName", "Таблица строки", () -> session.section(id).tableName(), () -> DraftSuggestions.rowTable(session.section(id)), value -> sectionAuto(id, "tableName", value));
        auto(properties, "parentFieldName", "Связь строки с корнем", () -> session.section(id).parentFieldName(), () -> DraftSuggestions.parentField(session.section(id)), value -> sectionAuto(id, "parentFieldName", value));
        auto(properties, "parentColumnName", "Колонка связи", () -> session.section(id).parentColumnName(), () -> DraftSuggestions.parentColumn(session.section(id)), value -> sectionAuto(id, "parentColumnName", value));
        for (int i = start; i < suggestions.size(); i++) suggestions.set(i, new FieldSuggestion(suggestions.get(i)));
        properties.property("owner", "Корень агрегата", session.draft().entity().className().isEmpty() ? session.document() ? "Этот документ" : "Этот справочник" : session.draft().entity().className());
        properties.property("policy", "Режим", "MUTABLE_REPLACE_ALL · INHERIT_ROOT");
        inspector.add(properties, new Span("Строка принадлежит этому " + (session.document() ? "документу" : "справочнику") + ". Связь с корнем и номер строки создаются автоматически и не выводятся в форму строки. Владелец справочника и обычные ссылки строки задаются отдельно."));
    }
    private void sectionAuto(UUID id, String key, DraftText value) {
        session.editSection(id, s -> new SectionDraft(s.id(), s.label(), s.rowClassName(), key.equals("packageName") ? value : s.packageName(),
            key.equals("tableName") ? value : s.tableName(), key.equals("parentFieldName") ? value : s.parentFieldName(), key.equals("parentColumnName") ? value : s.parentColumnName(), s.fields()));
    }
    private void reference(ExplorerPropertyList properties, UUID id) {
        ComboBox<String> picker = new ComboBox<>(); picker.setAriaLabel("Тип ссылки"); picker.setPlaceholder("Выберите существующий тип"); picker.setClearButtonVisible(true);
        picker.setItemLabelGenerator(type -> {
            var descriptor = session.context().referenceTargets().get(type);
            return descriptor == null ? "Недоступно · " + type : (descriptor.supported() ? "" : "Недоступно · ") + descriptor.caption() + " · " + type;
        });
        picker.addValueChangeListener(e -> edit(e.getSource(), () -> session.editField(id, old -> new FieldDraft(old.id(), old.name(), old.label(), old.type(), old.columnName(), old.serverRequired(), old.uiRequired(), old.constraints(),
            e.getValue() == null ? null : new ReferenceDraft(e.getValue()), old.applications())), true));
        registerInput("reference.typeId", picker); properties.property("reference.typeId", "Тип ссылки", picker);
        Details blocked = new Details(); blocked.addClassName("draft-blocked-targets");
        Runnable update = () -> {
            Set<String> allowed = new TreeSet<>();
            session.context().referenceTargets().values().stream().filter(ReferenceTargetDescriptor::supported).forEach(t -> allowed.add(t.typeId()));
            var f = session.selectedField(); String selected = f.reference() == null ? null : f.reference().typeId();
            if (selected != null) allowed.add(selected); picker.setItems(allowed); picker.setValue(selected);
            var unavailable = session.context().referenceTargets().values().stream().filter(t -> !t.supported()).sorted(Comparator.comparing(ReferenceTargetDescriptor::caption)).toList();
            blocked.removeAll(); blocked.setSummaryText("Недоступные типы (" + unavailable.size() + ")");
            Div list = new Div();
            for (var target : unavailable) { Div line = new Div(new Span(target.caption() + " · " + target.typeId()), new Span(target.reason())); line.addClassName("draft-unavailable-target"); list.add(line); }
            if (selected != null && !session.context().referenceTargets().containsKey(selected)) list.add(new Span(selected + " — тип отсутствует в текущем каталоге; значение сохранено"));
            blocked.add(list);
            blocked.setVisible(!unavailable.isEmpty() || selected != null && !session.context().referenceTargets().containsKey(selected));
        };
        suggestions.add(new FieldSuggestion(update)); update.run(); properties.property("reference.availability", "", blocked);
    }
    private void constraint(ExplorerPropertyList properties, UUID id, String key, String caption, String value, boolean applicable) {
        if (!applicable && value.isEmpty()) return;
        text(properties, "constraints." + key, caption + (applicable ? "" : " · неприменимо"), value, raw -> session.editField(id, old -> copy(old, key, raw)));
        if (!applicable) properties.property("clear." + key, "", ownedButton("Очистить", () -> {
            session.editField(id, old -> copy(old, key, "")); renderInspector();
        }));
    }
    private void application(Div host, UUID id, String key, String caption, boolean value) {
        Checkbox checkbox = new Checkbox(caption); checkbox.setValue(value);
        session.descriptor().fixedPublications().ifPresent(fixed -> {
            boolean expected = switch(key) { case "form" -> fixed.form(); case "list" -> fixed.list(); case "selection" -> fixed.selection(); default -> fixed.table(); };
            if (value == expected) checkbox.setEnabled(false);
        });
        checkbox.addValueChangeListener(e -> edit(e.getSource(), () -> session.editField(id, old -> {
            var a = old.applications(); return new FieldDraft(old.id(), old.name(), old.label(), old.type(), old.columnName(), old.serverRequired(), old.uiRequired(), old.constraints(), old.reference(),
                new FieldApplications(key.equals("form") ? e.getValue() : a.form(), key.equals("list") ? e.getValue() : a.list(), key.equals("selection") ? e.getValue() : a.selection(), key.equals("table") ? e.getValue() : a.table()));
        }), true)); registerInput("applications." + key, checkbox); host.add(checkbox);
    }
    private FieldDraft copy(FieldDraft f, String key, String value) {
        var c = f.constraints(); return new FieldDraft(f.id(), f.name(), key.equals("label") ? value : f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(),
            new ConstraintDraft(key.equals("length") ? value : c.length(), key.equals("precision") ? value : c.precision(), key.equals("scale") ? value : c.scale()), f.reference(), f.applications());
    }

    private void registerInput(String key, Component input) {
        input.getElement().setAttribute("data-draft-input", key);
        input.getElement().setAttribute("data-draft-document", documentEpoch.toString());
        inputs.put(key, input);
    }
    private Button ownedButton(String caption, Runnable action) {
        UUID document = documentEpoch;
        return new Button(caption, event -> {
            if (permitted() && document.equals(documentEpoch)) command(action);
        });
    }
    private void edit(Component source, Runnable mutation, boolean validate) {
        if (rendering || !permitted() || !kindChosen || !documentEpoch.toString().equals(source.getElement().getAttribute("data-draft-document"))) return;
        if (inputs.get(source.getElement().getAttribute("data-draft-input")) != source) return;
        mutation.run(); session.validate(); refreshSuggestions(); updateRows(); refreshStatus(); changed.run();
    }
    private void command(Runnable action) {
        if (!permitted() || !kindChosen) return;
        boolean previous = rendering; rendering = true;
        try { action.run(); } finally { rendering = previous; }
        session.validate(); refreshSuggestions(); updateRows(); refreshStatus(); changed.run();
    }
    private void move(int delta) { command(() -> { session.moveSelected(delta); renderRows(); }); }
    private void removeSelected() {
        if (!permitted() || confirmationOpened) return;
        UUID sectionId = session.place().sectionId(), fieldId = session.place().fieldId(), documentId = documentEpoch;
        if (sectionId != null && fieldId == null && !session.section(sectionId).fields().isEmpty()) {
            ConfirmDialog dialog = new ConfirmDialog(); confirmationOpened = true;
            dialog.setHeader("Удалить табчасть?"); dialog.setText("Будет удалена табчасть «" + sectionCaption(session.section(sectionId)) + "» и её реквизиты: " + session.section(sectionId).fields().size());
            dialog.setCancelable(true); dialog.setCancelText("Отмена"); dialog.setConfirmText("Удалить");
            dialog.addConfirmListener(e -> {
                if (permitted() && documentEpoch.equals(documentId) && session.draft().sections().stream().anyMatch(s -> s.id().equals(sectionId))) {
                    command(() -> { session.selectSection(sectionId); session.removeSelected(); renderRows(); renderInspector(); });
                }
            });
            dialog.addOpenedChangeListener(e -> { if (!e.isOpened()) confirmationOpened = false; }); dialog.open();
        } else if (sectionId != null || fieldId != null) command(() -> { session.removeSelected(); renderRows(); renderInspector(); });
    }
    private void page(DraftPlace.Page page) { command(() -> { session.page(page); if(page==DraftPlace.Page.GENERAL) refreshRlsChoices.run(); showPage(); }); }
    private void showPage() {
        boolean basic = session.place().page() == DraftPlace.Page.GENERAL;
        general.setVisible(basic); data.setVisible(!basic);
        generalTab.getElement().setAttribute("aria-pressed", Boolean.toString(basic)); dataTab.getElement().setAttribute("aria-pressed", Boolean.toString(!basic));
    }
    private void refreshSuggestions() {
        boolean previous = rendering; rendering = true;
        try { suggestions.forEach(Runnable::run); } finally { rendering = previous; }
    }
    private void refreshStatus() {
        refreshGeneration();
        heading.setText("Черновик " + switch(session.descriptor().kind()) { case "DOCUMENT" -> "документа"; case "INFORMATION_REGISTER" -> "регистра"; default -> "справочника"; }
            + " · " + session.descriptor().persistenceProfile() + (session.register() ? " / " + session.draft().register().period() : ""));
        if (!kindChosen) heading.setText("Новый черновик · выберите вид");
        catalogNotice.setText(session.context().existingTypes().complete()
            ? "Каталог проекта: " + session.context().existingTypes().values().size() + " типов. " + (session.context().existingTables().complete() ? "Проверяются объявленные имена хранения." : session.context().existingTables().reason() + ". Проверка хранения неполна.")
            : "Проверка по каталогу проекта пока недоступна. Классы и таблицы ещё не созданы.");
        String path = session.place().page() == DraftPlace.Page.GENERAL ? "Основные" : "Данные";
        if (session.place().page() == DraftPlace.Page.DATA && session.place().fieldId() != null) {
            String name = session.selectedField().name().isEmpty() ? "Новый реквизит" : session.selectedField().name();
            inspector.getChildren().filter(c -> c instanceof H4).map(c -> (H4)c).findFirst().ifPresent(title -> title.setText(fieldPath(session.selectedField())));
            path += " / " + (session.place().sectionId() == null ? "" : sectionCaption(session.section(session.place().sectionId())) + " / ") + name;
        } else if (session.place().page() == DraftPlace.Page.DATA && session.place().sectionId() != null) {
            String caption = sectionCaption(session.section(session.place().sectionId())); path += " / " + caption;
            inspector.getChildren().filter(c -> c instanceof H4).map(c -> (H4)c).findFirst().ifPresent(title -> title.setText("Табчасть / " + caption));
        }
        breadcrumb.setText("Черновик / " + path + " · Классы и таблицы ещё не созданы");
        breadcrumb.getElement().setAttribute("title", breadcrumb.getText());
        problems.removeAll();
        inputs.values().forEach(c -> { if (c instanceof HasValidation validation) validation.setInvalid(false); });
        var result = session.validation();
        if (result.isEmpty()) { status.setText("Структура не проверена"); return; }
        var errors = result.get().problems().stream().filter(p -> p.severity() == DraftProblem.Severity.ERROR).toList();
        long warnings = result.get().problems().size() - errors.size();
        status.setText((errors.isEmpty() ? "Структура проверена" : "Ошибок: " + errors.size()) + (warnings == 0 ? "" : " · Предупреждений: " + warnings));
        for (var p : result.get().problems()) {
            String key = problemInput(p);
            boolean selected = p.target().sectionId() == null && p.target().fieldId() == null
                || Objects.equals(p.target().sectionId(), session.place().sectionId()) && Objects.equals(p.target().fieldId(), session.place().fieldId());
            Component input = inputs.get(key);
            if (selected && input instanceof HasValidation v && p.severity() == DraftProblem.Severity.ERROR) { v.setInvalid(true); v.setErrorMessage(problemMessage(p)); }
        }
        if (!result.get().problems().isEmpty()) {
            Details details = new Details(); details.setSummaryText("Проблемы структуры (" + result.get().problems().size() + ")");
            details.setOpened(problemListOpened);
            details.addOpenedChangeListener(event -> problemListOpened = event.isOpened());
            Div list = new Div();
            for (var p : result.get().problems()) {
                Button link = new Button(problemText(p), e -> navigateProblem(p)); link.addClassName("draft-problem"); list.add(link);
            }
            details.add(list); problems.add(details);
        }
        session.readiness().ifPresent(readiness -> readiness.diagnostics().forEach(diagnostic -> {
            var target = diagnostic.target();
            boolean selected = target.fieldId() == null || target.fieldId().equals(session.place().fieldId());
            var input = inputs.get(problemInput(target));
            if (selected && input instanceof HasValidation v) { v.setInvalid(true); v.setErrorMessage(generationMessage(diagnostic)); }
        }));
    }
    private String problemText(DraftProblem p) {
        return problemText(p.target(), problemMessage(p));
    }
    private String problemText(DraftProblem.Target address, String message) {
        String target = address.sectionId() == null ? session.rootCaption() : session.draft().sections().stream().filter(s -> s.id().equals(address.sectionId())).map(this::sectionCaption).findFirst().orElse("Табчасть");
        if (address.fieldId() != null) target += " / " + session.fields(address.sectionId()).stream().filter(f -> f.id().equals(address.fieldId())).map(f -> f.name().isEmpty() ? "Новый реквизит" : f.name()).findFirst().orElse("Реквизит");
        String property = switch (address.propertyKey()) {
            case "className" -> "Java-класс"; case "packageName" -> "Пакет"; case "label" -> "Название"; case "name" -> "Имя";
            case "tableName" -> "Таблица"; case "columnName" -> "Колонка"; case "type" -> "Тип"; case "subsystemId" -> "Подсистема";
            case "icon" -> "Значок"; case "constraints.length" -> "Длина"; case "constraints.precision" -> "Точность"; case "constraints.scale" -> "Дробных разрядов";
            case "context.existingTypes" -> "Каталог классов"; case "context.existingTables" -> "Каталог таблиц";
            case "reference.typeId" -> "Тип ссылки"; case "applications.form" -> "Применение в форме"; case "uiRequired" -> "Обязательность UI";
            case "applications.list" -> "Публикация в списке"; case "applications.selection" -> "Публикация в выборе"; case "applications.table" -> "Публикация в таблице";
            case "register.period" -> "Период регистра"; case "register.writeProfile" -> "Профиль записи";
            case "register.rls.dimension" -> "Имя измерения доступа"; case "register.rls.fieldId" -> "Ссылочное измерение доступа";
            case "register.rls" -> "Доступ к строкам";
            case "titles.list" -> "Заголовок списка"; case "titles.item" -> "Заголовок элемента"; case "titles.selection" -> "Заголовок выбора";
            case "rowClassName" -> "Класс строки"; case "parentFieldName" -> "Связь строки с корнем"; case "parentColumnName" -> "Колонка связи";
            case "recipe" -> "Рецепт"; case "sections" -> "Табчасть"; default -> "Структура";
        };
        return target + " / " + property + ": " + message;
    }
    private String problemMessage(DraftProblem p) {
        return switch (p.code()) {
            case VALUE_REQUIRED -> "Заполните значение"; case INVALID_JAVA_IDENTIFIER -> "Неверное Java-имя"; case JAVA_KEYWORD -> "Зарезервированное слово Java";
            case INVALID_PACKAGE -> "Неверное имя пакета"; case DUPLICATE_NAME -> "Имя повторяется"; case RESERVED_FIELD -> "Имя занято стандартной базой или техническим полем";
            case DUPLICATE_TYPE_NAME -> "Класс уже объявлен"; case INVALID_STORAGE_NAME -> "Неверное SQL-имя"; case DUPLICATE_STORAGE_NAME -> "SQL-имя уже занято";
            case INVALID_CONSTRAINT -> "Неверное числовое ограничение"; case INAPPLICABLE_CONSTRAINT -> "Параметр неприменим к типу";
            case UNSUPPORTED_FIELD_TYPE -> "Тип не поддержан"; case REFERENCE_REQUIRED -> "Укажите тип ссылки"; case REFERENCE_UNAVAILABLE -> "Тип ссылки недоступен";
            case PROJECT_FACT_UNKNOWN -> "Факт проекта пока не подтверждён"; case INVALID_APPLICATION -> "Недопустимое применение";
            case SUBSYSTEM_UNAVAILABLE -> "Подсистема недоступна"; case ICON_UNAVAILABLE -> "Значок недоступен"; case UI_REQUIRED_MISMATCH -> "При записи значение обязательно";
            case UI_REQUIRED_SERVER_OPTIONAL -> "Обязательность формы не подтверждена ограничениями записи";
            case REGISTER_ROLE_MISSING -> "Назначьте измерения и ресурсы; каждое поле должно иметь одну роль";
            case REGISTER_ROLE_DUPLICATE -> "Поле не может повторяться или одновременно быть измерением и ресурсом";
            case REGISTER_ROLE_UNKNOWN -> "Роль ссылается на отсутствующее поле";
            case REGISTER_DIMENSION_REQUIRED -> "Измерение должно быть обязательным при записи";
            case REGISTER_RLS_INVALID -> "Укажите имя измерения доступа и ссылочное бизнес-измерение с Long-id";
            case REGISTER_FIELD_ROLE_UNSUPPORTED -> "Тип поля не поддерживается для выбранной роли";
            case REGISTER_PERIOD_UNSUPPORTED -> "Поддерживаются режимы NONE и DAY";
            case REGISTER_WRITE_PROFILE_UNSUPPORTED -> "Выбранный профиль записи регистра не поддерживается";
            case REGISTER_SECTION_UNSUPPORTED -> "Выбранный профиль регистра не поддерживает эту конструкцию";
            case OWNER_UNSUPPORTED_RECIPE -> "Владелец разрешён только у справочника";
            case RECIPE_DRAFT_UNSUPPORTED -> "Для этого рецепта ещё не определён контракт черновика";
            case RECIPE_PREREQUISITE_UNAVAILABLE -> "Не выполнено условие рецепта";
            case APPLICATION_PROJECTION_UNREPRESENTABLE -> "Применение реквизита не представимо в этом рецепте";
            case STYLE_CONVENTION -> "Проверьте регистр имени"; default -> "Проверьте структуру";
        };
    }
    private void navigateProblem(DraftProblem p) {
        navigateProblem(p.target());
    }
    private void navigateProblem(DraftProblem.Target target) {
        if (!permitted() || !target.draftId().equals(session.draft().draftId())) return;
        command(() -> {
            if (target.fieldId() == null && target.sectionId() == null) session.page(DraftPlace.Page.GENERAL);
            else {
                if (target.fieldId() == null) session.selectSection(target.sectionId()); else session.selectField(target.sectionId(), target.fieldId());
                renderRows(); renderInspector(); split.getElement().setAttribute("data-detail", "");
            }
            showPage(); focus(problemInput(target));
        });
    }
    private String problemInput(DraftProblem p) {
        return problemInput(p.target());
    }
    private String problemInput(DraftProblem.Target target) {
        String key = target.propertyKey();
        if (target.fieldId() != null && key.equals("label")) return "field.label";
        if (target.sectionId() != null && target.fieldId() == null && Set.of("label", "packageName", "tableName").contains(key)) return "section." + key;
        return key;
    }
    private void showJava() {
        if (!permitted() || confirmationOpened) return;
        closeJavaPreview();
        session.refreshContext(contextSource.get());
        var result = session.previewJava();
        problemListOpened = true;
        refreshStatus();
        if (result.isEmpty()) { transferNotice.setText("Java не сформирован: исправьте проблемы структуры"); return; }
        if (result.orElseThrow().plan().isEmpty()) { transferNotice.setText("Java не сформирован: ограничения первого генератора"); return; }
        showJavaPreview(result.orElseThrow().plan().orElseThrow());
    }

    /** Package-private seam for E4.B N-file preview tests before the E4.A generator exists. */
    void previewJava(GenerationPlan plan) {
        if (!permitted() || confirmationOpened) return;
        closeJavaPreview();
        session.previewJava(plan);
        problemListOpened = true;
        refreshStatus();
        showJavaPreview(plan);
    }

    private void showJavaPreview(GenerationPlan plan) {
        var first = plan.files().getFirst();
        var dialog = new Dialog(); javaPreview = dialog;
        var exported = new JavaExport(documentEpoch, session.revision(), plan); javaExport = exported;
        dialog.setHeaderTitle("Java · " + plan.source().entity().className());
        dialog.setWidth("900px"); dialog.setMaxWidth("95vw");
        TextArea source = new TextArea("Исходник"); source.setValue(first.content()); source.setReadOnly(true);
        source.setWidthFull(); source.setHeight("60vh"); source.getStyle().set("font-family", "monospace");
        source.getElement().setAttribute("data-java-source", "");
        var files = new Div(); files.addClassName("draft-java-files"); files.getElement().setAttribute("data-java-files", "");
        var currentPath = new Span(first.relativePath()); currentPath.getElement().setAttribute("data-java-current", "");
        for (GenerationFile file : plan.files()) {
            var fileButton = new Button(file.operation() + " · " + file.relativePath());
            fileButton.addClassName("draft-java-file");
            fileButton.getElement().setAttribute("data-java-path", file.relativePath());
            fileButton.addClickListener(e -> { source.setValue(file.content()); currentPath.setText(file.relativePath()); });
            files.add(fileButton);
        }
        var count = new Span("Файлов: " + plan.files().size()); count.getElement().setAttribute("data-java-count", "");
        var caption = new Div(currentPath, count); caption.addClassName("draft-java-caption");
        dialog.add(files, caption, new Span("Исходник сформирован · Проверка компиляцией ещё не выполнена"), source);
        Anchor javaDownload = new Anchor();
        javaDownload.setText(plan.files().size() == 1 ? "Скачать Java" : "Скачать ZIP");
        javaDownload.setHref(javaDownloadHandler(exported));
        javaDownload.getElement().setAttribute("download", JavaBundleArchive.fileName(plan));
        dialog.getFooter().add(javaDownload, new Button("Закрыть", e -> dialog.close()));
        dialog.addOpenedChangeListener(e -> { if (!e.isOpened()) {
            if (javaPreview == dialog) { javaPreview = null; javaExport = null; }
            remove(dialog);
        } });
        add(dialog); dialog.open();
        transferNotice.setText(plan.files().size() == 1 ? "Исходник сформирован" : "Комплект Java сформирован");
    }
    private void closeJavaPreview() {
        javaExport = null;
        if (javaPreview != null) { var old = javaPreview; javaPreview = null; old.close(); remove(old); }
    }
    record JavaExport(UUID epoch, long revision, GenerationPlan plan) { }
    JavaExport javaExport() { return javaExport; }
    DownloadHandler javaDownloadHandler(JavaExport exported) {
        return event -> {
            AtomicReference<IOException> failure = new AtomicReference<>();
            // Hold the session lock through validation, bundle construction and delivery so an
            // import, edit or principal change cannot interleave between the check and write.
            event.getUI().accessSynchronously(() -> {
                if (!permitted() || !exported.epoch().equals(documentEpoch)) {
                    event.getResponse().setStatus(403); return;
                }
                session.refreshContext(contextSource.get());
                var current = session.generation().flatMap(result -> result.plan());
                if (javaExport != exported || javaPreview == null || !javaPreview.isOpened()
                        || exported.revision() != session.revision()
                        || current.orElse(null) != exported.plan()
                        || !exported.plan().appliesTo(session.draft(), session.context())
                        || !EntityJavaGenerator.generatorVersion(exported.plan().recipe()).equals(exported.plan().generatorVersion())) {
                    refreshStatus();
                    transferNotice.setText("План Java устарел. Повторите показ Java");
                    event.getResponse().setStatus(409); return;
                }
                final byte[] bytes;
                try {
                    bytes = JavaBundleArchive.bytes(exported.plan());
                } catch (IOException failed) {
                    transferNotice.setText("Не удалось сформировать комплект Java. Повторите показ Java");
                    event.getResponse().setStatus(409); return;
                }
                event.setFileName(JavaBundleArchive.fileName(exported.plan()));
                event.setContentType(JavaBundleArchive.contentType(exported.plan()));
                event.setContentLength(bytes.length);
                event.getResponse().setHeader("Cache-Control", "no-store");
                try {
                    event.getOutputStream().write(bytes); event.getOutputStream().flush();
                    // Java delivery is not a JSON checkpoint. Never call session.exported here.
                } catch (IOException failed) {
                    transferNotice.setText(exported.plan().files().size() == 1
                            ? "Не удалось выдать Java. Повторите скачивание из актуального preview"
                            : "Не удалось выдать архив Java. Повторите скачивание из актуального preview");
                    failure.set(failed);
                }
            });
            if (failure.get() != null) throw failure.get();
        };
    }
    private void refreshGeneration() {
        generationProblems.removeAll();
        var result = session.generation();
        if (result.isEmpty()) {
            closeJavaPreview();
            if (transferNotice.getText().equals("Исходник сформирован")
                    || transferNotice.getText().equals("Комплект Java сформирован")) {
                transferNotice.setText("Черновик или каталог изменён. Повторите показ Java");
            }
        }
        var diagnostics = result.map(r -> r.diagnostics()).orElseGet(() -> session.readiness().map(r -> r.diagnostics()).orElse(List.of()));
        if (diagnostics.isEmpty()) {
            if (session.readiness().map(r -> r.ready()).orElse(false)) generationProblems.add(new Span(
                result.isPresent() ? "Java сформирован · C/D ещё не проверены" : "Генерация доступна · C/D ещё не проверены"));
            return;
        }
        generationProblems.add(new Span("Ограничения выбранного профиля"));
        UUID epoch = documentEpoch;
        var draft = session.draft(); var context = session.context();
        for (var diagnostic : diagnostics) {
            Button link = new Button(problemText(diagnostic.target(), generationMessage(diagnostic)), e -> {
                if (permitted() && epoch.equals(documentEpoch) && draft.equals(session.draft()) && context.equals(session.context()))
                    navigateProblem(diagnostic.target());
            });
            link.addClassName("draft-problem");
            link.getElement().setAttribute("data-generation-code", diagnostic.code().name());
            link.getElement().setAttribute("data-generation-target", diagnostic.target().propertyKey());
            generationProblems.add(link);
        }
    }
    private String generationMessage(GenerationDiagnostic diagnostic) {
        return switch (diagnostic.code()) {
            case UNSUPPORTED_RECIPE -> "Для выбранного рецепта генератор не реализован";
            case UNSUPPORTED_CONSTRUCT -> {
                if ("RUNTIME_PREREQUISITE".equals(diagnostic.parameters().get("construct"))) yield switch(diagnostic.parameters().get("prerequisite")) {
                    case "REGISTER_ENGINE_OPERATIONS" -> "Не подтверждена доступность операций регистра. Обновите каталог после подключения движка.";
                    case "REGISTER_FACTS" -> "Не подтверждено описание регистров в каталоге платформы.";
                    case "ET6_ET7_REGISTER_CLASSIFICATION" -> "Не подтверждена классификация регистров в платформе.";
                    default -> "Не подтверждено обязательное условие генерации: " + diagnostic.parameters().get("prerequisite");
                };
                if ("REGISTER_APPLICATIONS".equals(diagnostic.parameters().get("construct")))
                    yield "Профиль регистра фиксирует публикацию в форме, списке и выборе; публикация в таблице неприменима. Исходное значение сохранено.";
                yield switch (diagnostic.target().propertyKey()) {
                case "subsystemId" -> "Генератор поддерживает сущность без подсистемы";
                case "sections" -> "Выбранный профиль не поддерживает табчасти";
                default -> "Конструкция не поддерживается выбранным профилем генерации";
                };
            }
            case STALE_CHECKED_STRUCTURE -> "Проверка устарела. Повторите показ Java";
            case UNREPRESENTABLE_REQUIRED_MODE -> "Обязательное при записи поле нельзя объявить необязательным в форме";
            case PROJECTION_UNREPRESENTABLE -> "Ввод не представим выбранным профилем генерации";
        };
    }
    private void focus(String key) {
        Component input = inputs.get(key);
        for (Component parent = input; parent != null; parent = parent.getParent().orElse(null))
            if (parent instanceof Details details) details.setOpened(true);
        if (input instanceof Focusable<?> f) f.focus();
    }
    private void newDraft() {
        if (!permitted() || confirmationOpened) return;
        var chooser = new Dialog(); chooser.setHeaderTitle("Новый черновик");
        chooser.add(new Span("Выберите вид нового черновика"));
        UUID epoch = documentEpoch;
        for (var descriptor : RecipeCatalog.descriptors().stream().filter(d -> d.draftSupported() && d.generatorImplemented()).toList()) {
            var ref = descriptor.ref();
            var choice = new Button(recipeCaption(descriptor), e -> {
                if (!permitted() || !epoch.equals(documentEpoch) || !chooser.isOpened()) return;
                chooser.close(); confirmNewDraft(ref);
            });
            chooser.add(choice);
        }
        chooser.getFooter().add(new Button("Отмена", e -> chooser.close()));
        chooser.addOpenedChangeListener(e -> { if (!e.isOpened()) { if (epoch.equals(documentEpoch)) confirmationOpened = false; remove(chooser); } });
        confirmationOpened = true; add(chooser); chooser.open();
    }
    private void confirmNewDraft(RecipeRef recipe) {
        if (!session.dirty()) { replaceDraft(recipe); return; }
        ConfirmDialog dialog = new ConfirmDialog(); confirmationOpened = true;
        dialog.setHeader("Новый черновик"); dialog.setText("Текущий черновик не выгружен. Заменить его новым?");
        dialog.setCancelable(true); dialog.setCancelText("Отмена"); dialog.setConfirmText("Заменить");
        UUID replacingId = documentEpoch;
        dialog.addConfirmListener(e -> { if (permitted() && documentEpoch.equals(replacingId)) replaceDraft(recipe); });
        dialog.addOpenedChangeListener(e -> { if (!e.isOpened()) { if (documentEpoch.equals(replacingId)) confirmationOpened = false; remove(dialog); } });
        add(dialog); dialog.open();
    }
    private void replaceDraft(RecipeRef recipe) { documentEpoch = UUID.randomUUID(); kindChosen = true; session.newDraft(recipe); split.getElement().removeAttribute("data-detail"); build(); changed.run(); }

    private Upload jsonUpload() {
        UUID epoch = documentEpoch;
        Upload upload = new Upload(uploadHandler(epoch));
        currentUpload = upload;
        upload.addClassName("draft-json-upload"); upload.setDropAllowed(false); upload.setMaxFiles(1);
        upload.setMaxFileSize(DraftJsonCodec.MAX_BYTES); upload.setAcceptedFileTypes(".json", "application/json");
        upload.setUploadButton(new Button("Загрузить JSON"));
        upload.addAllFinishedListener(e -> upload.clearFileList());
        upload.addFileRejectedListener(e -> { if (permitted() && epoch.equals(documentEpoch)) transferNotice.setText("JSON не загружен: выберите один файл .json до 1 MiB"); });
        return upload;
    }
    UploadHandler uploadHandler(UUID epoch) {
        return new UploadHandler() {
            @Override public long getFileSizeMax() { return DraftJsonCodec.MAX_BYTES; }
            @Override public long getRequestSizeMax() { return DraftJsonCodec.MAX_BYTES + 65_536; }
            @Override public long getFileCountMax() { return 1; }
            @Override public void handleUploadRequest(UploadEvent event) throws IOException {
                AtomicLong revision = new AtomicLong(-1);
                event.getUI().accessSynchronously(() -> {
                    if (permitted() && epoch.equals(documentEpoch) && !confirmationOpened) revision.set(session.revision());
                });
                if (revision.get() < 0) { event.reject("Загрузка недоступна"); return; }
                try {
                    EntityDraft candidate = json.decode(event.getInputStream());
                    event.getUI().accessSynchronously(() -> acceptImport(candidate, epoch, revision.get()));
                } catch (DraftJsonException | IOException failed) {
                    event.getUI().accessSynchronously(() -> {
                        if (permitted() && epoch.equals(documentEpoch)) {
                            currentUpload.clearFileList();
                            transferNotice.setText("JSON не загружен. " + (failed instanceof DraftJsonException ? failed.getMessage() : "Ошибка чтения файла"));
                        }
                    });
                    event.reject("JSON не загружен");
                }
            }
        };
    }
    ConfirmDialog acceptImport(EntityDraft candidate, UUID epoch, long expectedRevision) {
        if (!permitted() || !epoch.equals(documentEpoch)) return null;
        // all-finished RPC can be blocked by a modal opened in this request; clear before opening it.
        currentUpload.clearFileList();
        if (confirmationOpened || expectedRevision != session.revision()) {
            transferNotice.setText("Черновик изменился во время загрузки. Повторите загрузку JSON."); return null;
        }
        Runnable replace = () -> {
            documentEpoch = UUID.randomUUID(); kindChosen = true; session.imported(candidate); session.validate();
            split.getElement().removeAttribute("data-detail"); build(); changed.run();
            transferNotice.setText(session.validation().orElseThrow().checkedStructure().isPresent() ? "JSON загружен" : "JSON загружен. Проверьте проблемы структуры");
        };
        if (!session.dirty()) { replace.run(); return null; }
        ConfirmDialog dialog = new ConfirmDialog(); confirmationOpened = true;
        dialog.setHeader("Загрузить JSON"); dialog.setText("Текущий черновик не выгружен. Заменить его загруженным?");
        dialog.setCancelable(true); dialog.setCancelText("Отмена"); dialog.setConfirmText("Заменить");
        dialog.addConfirmListener(e -> {
            if (permitted() && epoch.equals(documentEpoch) && expectedRevision == session.revision()) replace.run();
        });
        dialog.addOpenedChangeListener(e -> { if (!e.isOpened()) confirmationOpened = false; }); dialog.open();
        return dialog;
    }
    record JsonExport(UUID epoch, long revision, String fileName, byte[] bytes) { }
    private String jsonFileName() {
        String name = session.draft().entity().className();
        return javax.lang.model.SourceVersion.isIdentifier(name) && !javax.lang.model.SourceVersion.isKeyword(name)
            ? name + ".json" : "entity-draft-" + session.draft().draftId() + ".json";
    }
    UUID documentEpoch() { return documentEpoch; }
    JsonExport prepareExport() {
        if (!permitted() || !kindChosen) return null;
        session.validate(); refreshStatus();
        byte[] bytes = json.encode(session.draft());
        transferNotice.setText(session.validation().orElseThrow().checkedStructure().isPresent() ? "JSON готовится к выдаче" : "Черновик содержит ошибки; JSON готовится к выдаче");
        return new JsonExport(documentEpoch, session.revision(), jsonFileName(), bytes);
    }
    void exportDelivered(JsonExport exported) {
        if (!permitted() || !exported.epoch().equals(documentEpoch)) return;
        if (pendingExport == exported) pendingExport = null;
        session.exported(exported.revision()); refreshStatus(); changed.run();
        transferNotice.setText(session.dirty() ? "JSON выдан браузеру. Есть новые невыгруженные изменения" : "JSON выдан браузеру");
    }
    private void exportJson() {
        try {
            JsonExport exported = prepareExport(); if (exported == null) return;
            pendingExport = exported; downloadPolls = 0;
            download.setHref(downloadHandler(exported));
            download.setVisible(true); download.getStyle().set("display", "none");
            download.getElement().executeJs("this.click()");
            scheduleDownloadPoll();
        } catch (DraftJsonException failed) { transferNotice.setText(failed.getMessage()); }
    }
    DownloadHandler downloadHandler(JsonExport exported) {
        return event -> {
                AtomicBoolean allowed = new AtomicBoolean();
                event.getUI().accessSynchronously(() -> allowed.set(permitted() && exported.epoch().equals(documentEpoch)));
                if (!allowed.get()) { event.getResponse().setStatus(403); return; }
                event.setFileName(exported.fileName()); event.setContentType("application/json; charset=UTF-8");
                event.setContentLength(exported.bytes().length); event.getResponse().setHeader("Cache-Control", "no-store");
                try {
                    event.getOutputStream().write(exported.bytes()); event.getOutputStream().flush();
                    event.getUI().accessSynchronously(() -> exportDelivered(exported));
                } catch (IOException failed) {
                    event.getUI().accessSynchronously(() -> {
                        if (permitted() && exported.epoch().equals(documentEpoch)) {
                            if (pendingExport == exported) pendingExport = null;
                            transferNotice.setText("Не удалось выдать JSON. Черновик остаётся невыгруженным");
                        }
                    });
                    throw failed;
                }
        };
    }
    private void scheduleDownloadPoll() {
        getElement().executeJs("setTimeout(() => { if (this.isConnected) this.dispatchEvent(new Event('draft-download-poll')); }, 500)");
    }
    private static String typeCaption(String type) {
        return switch (type) { case "STRING" -> "Текст"; case "INTEGER" -> "Integer"; case "LONG" -> "Long"; case "DECIMAL" -> "Число";
            case "BOOLEAN" -> "Да / нет"; case "DATE" -> "Дата"; case "DATETIME" -> "Дата и время"; case "REFERENCE" -> "Ссылка"; default -> type; };
    }
    private static ProjectDesignContext uncapturedContext() {
        var unknown = ProjectDesignContext.Names.unknown("catalogNotCaptured");
        return new ProjectDesignContext("application", "uncaptured-v1", null, unknown, unknown, unknown,
            ProjectDesignContext.Names.known(new HashSet<>(Arrays.stream(VaadinIcon.values()).map(Enum::name).toList())), Map.of(), false);
    }
}
