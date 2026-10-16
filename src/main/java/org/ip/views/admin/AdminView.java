package org.ip.views.admin;

import java.util.Map;
import java.util.HashMap;

import jakarta.persistence.EntityManagerFactory;
import org.ipro.metadata.annotation.EntityMetadata;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.spring.annotation.SpringComponent;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessService.EffectiveGrant;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.c5.C5Operation;
import org.ip.application.catalog.NamedOperationOwner;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ip.service.AccessGrantAdminService.UngrantedDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * «Администрирование» (после разделения): только Доступ (RLS), Настройки,
 * Нумерация и опциональный редактор профилей интерфейса. Всё, что касается телеметрии (журнал, ошибки, трассировка,
 * агрегаты, история изменений), переехало в отдельный вид
 * {@link DiagnosticsView} («Диагностика»).
 */
@SpringComponent
@Scope("prototype")
public class AdminView extends VerticalLayout implements org.ipro.form.Dirtyable, org.ipro.form.Savable {

    @Autowired
    private ObjectProvider<PermissionWorkspace> permissionWorkspaces;
    private PermissionWorkspace permissionWorkspace;

    private final AccessGrantAdminService accessGrantAdminService;
    private final SettingsAdminTab settingsTab;
    private final NumberingAdminTab numberingTab;
    private final ObjectProvider<NavigationProfilesAdminTab> navigationTabs;
    private final boolean navigationEnabled;
    private NavigationProfilesAdminTab navigationTab;
    private boolean uiBuilt;
    private final Map<String, String> entityLabels = new HashMap<>();

    private final Grid<DimensionValueAccessRow> accessGrid = new Grid<>();
    private final VerticalLayout accessTab = new VerticalLayout();

    private final ComboBox<String> accessDimension = new ComboBox<>("Измерение");
    private final Span accessDimensionKey = new Span();
    private final ComboBox<AccessGrant.SubjectType> accessSubjectType = new ComboBox<>("Тип субъекта");
    private final ComboBox<String> accessSubjectKey = new ComboBox<>("Пользователь/роль");
    private final java.util.List<DimensionValueAccessRow> accessRows = new java.util.ArrayList<>();
    private final Checkbox singleGrantRead = new Checkbox("");
    private final Checkbox singleGrantUpdate = new Checkbox("");
    private final Checkbox singleGrantDelete = new Checkbox("");
    private final Span singleGrantUpdateLabel = new Span("Изменение:");
    private final Span singleGrantDeleteLabel = new Span("Удаление:");
    private HorizontalLayout singleGrantRowLayout;
    private final Button saveAccessButton = new Button("Сохранить", new Icon(VaadinIcon.CHECK), e -> saveAccessMatrix());
    private final Button effectiveRightsButton = new Button("Эффективные права",
            new Icon(VaadinIcon.EYE), e -> openEffectiveRightsDialog());
    /** Отчёт «определение зарегистрировано, грантов нет»: см. refreshUngrantedReport. */
    private final Grid<UngrantedDefinition> ungrantedGrid = new Grid<>();
    private final VerticalLayout ungrantedSection = new VerticalLayout();
    private boolean accessSaveInProgress;
    /** Названия именованных операций, объявленные их владельцами, по permission id. */
    private final Map<String, String> operationTitles = new HashMap<>();
    /** Что текущее измерение вообще может выражать: из этого живёт включённость чекбоксов. */
    private GrantFlags expressibleFlagsOfDimension = new GrantFlags(true, true, true);

    public AdminView(@Autowired AccessGrantAdminService accessGrantAdminService,
                     @Autowired SettingsAdminTab settingsTab,
                     @Autowired NumberingAdminTab numberingTab,
                     @Autowired EntityManagerFactory entityManagerFactory,
                     @Autowired java.util.List<NamedOperationOwner> namedOperationOwners,
                     @Autowired ObjectProvider<NavigationProfilesAdminTab> navigationTabs,
                     @Value("${platform.navigation.enabled:false}") boolean navigationEnabled) {
        this.accessGrantAdminService = accessGrantAdminService;
        this.navigationTabs = navigationTabs;
        this.navigationEnabled = navigationEnabled;
        for (NamedOperationOwner owner : namedOperationOwners) {
            operationTitles.put(owner.permissionId(), owner.title());
        }
        this.settingsTab = settingsTab;
        this.numberingTab = numberingTab;
        for (var entity : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            EntityMetadata metadata = type.getAnnotation(EntityMetadata.class);
            String title = metadata == null || metadata.listFormTitle().isBlank()
                ? type.getSimpleName() : metadata.listFormTitle();
            String label = title.equals(type.getSimpleName()) ? title : title + " (" + type.getSimpleName() + ")";
            entityLabels.put(type.getName(), label);
            entityLabels.put(entity.getName(), label);
        }
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (isAdmin()) {
            if (!uiBuilt) {
                buildUi();
                uiBuilt = true;
            }
        } else {
            removeAll();
            uiBuilt = false;
            add(new H3("Доступно только администратору"));
        }
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }

    private void buildUi() {
        add(new H3("Администрирование"));

        Tab accessItem = new Tab(new Span("Доступ (RLS)"), new Icon(VaadinIcon.KEY));
        Tab workspaceItem = new Tab(new Span("Пользователи и права — новый экран"), new Icon(VaadinIcon.USERS));
        Tab settingsItem = new Tab(new Span("Настройки"), new Icon(VaadinIcon.COGS));
        Tab numberingItem = new Tab(new Span("Нумерация"), new Icon(VaadinIcon.HASH));
        Tabs tabs = new Tabs(accessItem, workspaceItem, settingsItem, numberingItem);
        Tab navigationItem = navigationEnabled ? new Tab(new Span("Интерфейс"), new Icon(VaadinIcon.MENU)) : null;
        if (navigationEnabled) {
            navigationTab = navigationTabs.getObject();
            tabs.add(navigationItem);
        }
        add(tabs);

        buildAccessTab();

        add(accessTab, settingsTab, numberingTab);
        if (navigationTab != null) add(navigationTab);
        show(accessTab);

        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == workspaceItem) {
                if (permissionWorkspace == null) {
                    try {
                        permissionWorkspace = permissionWorkspaces.getObject();
                        add(permissionWorkspace);
                        setFlexGrow(1, permissionWorkspace);
                    } catch (RuntimeException failure) {
                        org.slf4j.LoggerFactory.getLogger(AdminView.class).error("Cannot open permission workspace", failure);
                        Notification.show("Не удалось открыть новый экран прав: " + failure.getMessage());
                        tabs.setSelectedTab(accessItem);
                        return;
                    }
                }
                show(permissionWorkspace);
                permissionWorkspace.reloadActive();
            } else if (navigationItem != null && e.getSelectedTab() == navigationItem) {
                show(navigationTab);
                navigationTab.refresh();
            } else if (e.getSelectedTab() == settingsItem) {
                show(settingsTab);
                settingsTab.refresh();
            } else if (e.getSelectedTab() == numberingItem) {
                show(numberingTab);
                numberingTab.refresh();
            } else {
                show(accessTab);
            }
        });
    }

    private void show(VerticalLayout active) {
        accessTab.setVisible(false);
        settingsTab.setVisible(false);
        numberingTab.setVisible(false);
        if (navigationTab != null) navigationTab.setVisible(false);
        if (permissionWorkspace != null) permissionWorkspace.setVisible(false);
        active.setVisible(true);
    }

    @Override public boolean isDirty() { return permissionWorkspace != null && permissionWorkspace.isDirty(); }
    @Override public String getCloseConfirmMessage() {
        return permissionWorkspace == null ? "" : permissionWorkspace.getCloseConfirmMessage();
    }
    @Override public boolean doSave() { return permissionWorkspace == null || permissionWorkspace.doSave(); }

    // ------------------------------------------------------ доступ (RLS)

    /** Строка матрицы: запись измерения + текущее (редактируемое) состояние трёх флагов. */
    private static final class DimensionValueAccessRow {
        final AccessGrantAdminService.ValueRow value;
        boolean read;
        boolean update;
        boolean delete;

        DimensionValueAccessRow(AccessGrantAdminService.ValueRow value, GrantFlags flags) {
            this.value = value;
            this.read = flags.read();
            this.update = flags.update();
            this.delete = flags.delete();
        }
    }

    private void buildAccessTab() {
        accessDimension.setLabel("Объект доступа / измерение");
        accessDimension.setItemLabelGenerator(this::accessDimensionLabel);
        accessDimension.setWidth("min(100%, 32rem)");
        accessDimension.getStyle().set("--vaadin-combo-box-overlay-width", "min(90vw, 65rem)");
        accessDimensionKey.getStyle().set("overflow-wrap", "anywhere");
        accessDimension.setHelperComponent(accessDimensionKey);
        var dimensions = accessGrantAdminService.availableDimensions();
        accessDimension.setItems(dimensions.stream()
            .sorted(java.util.Comparator.comparing(this::accessDimensionLabel).thenComparing(key -> key))
            .toList());
        accessDimension.setAllowCustomValue(false);
        accessDimension.addValueChangeListener(e -> accessDimensionKey.setText(
            e.getValue() == null ? "" : e.getValue()));
        if (!dimensions.isEmpty()) {
            accessDimension.setValue(dimensions.get(0));
        }

        accessSubjectType.setItems(AccessGrant.SubjectType.values());
        accessSubjectType.setItemLabelGenerator(t ->
                t == AccessGrant.SubjectType.USER ? "Пользователь" : "Роль");
        accessSubjectType.setValue(AccessGrant.SubjectType.USER);
        accessSubjectType.setAllowCustomValue(false);

        accessSubjectKey.setAllowCustomValue(false);
        refreshSubjectKeyItems();

        accessDimension.addValueChangeListener(e -> loadAccessMatrix());
        accessSubjectType.addValueChangeListener(e -> {
            accessSubjectKey.clear();
            refreshSubjectKeyItems();
            loadAccessMatrix();
        });
        accessSubjectKey.addValueChangeListener(e -> loadAccessMatrix());

        Button saveButton = saveAccessButton;
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        accessGrid.addColumn(r -> r.value.code()).setHeader("Код").setAutoWidth(true);
        accessGrid.addColumn(r -> r.value.name()).setHeader("Наименование").setFlexGrow(1);
        accessGrid.addComponentColumn(this::buildReadCheckbox).setHeader("Чтение").setWidth("100px").setFlexGrow(0);
        accessGrid.addComponentColumn(this::buildUpdateCheckbox).setHeader("Изменение").setWidth("110px").setFlexGrow(0);
        accessGrid.addComponentColumn(this::buildDeleteCheckbox).setHeader("Удаление").setWidth("100px").setFlexGrow(0);

        // Отчёт «определение зарегистрировано, грантов нет» (C5.8): зарегистрированный ключ
        // без единой решающей строки закрыт для всех, включая администратора. Без отчёта это
        // было видно только тому, кто сам догадается открыть ключ; отчёт обновляется после
        // каждого сохранения, поэтому выданное право тут же перестаёт в нём числиться.
        ungrantedGrid.addColumn(d -> accessDimensionLabel(d.key())).setHeader("Определение")
                .setAutoWidth(true).setFlexGrow(1);
        ungrantedGrid.addColumn(d -> formatUngrantedOperations(d.operations()))
                .setHeader("Что решает").setAutoWidth(true).setFlexGrow(0);
        ungrantedGrid.setAllRowsVisible(true);
        ungrantedGrid.setWidthFull();
        Span ungrantedTitle = new Span("Определения без выданных грантов");
        ungrantedTitle.getStyle().set("font-weight", "600");
        Span ungrantedHint = new Span("Никому не выдано — включая администратора: такие определения "
                + "сейчас закрыты. Если это не задумано, выдайте право на этой вкладке или включите "
                + "первичную выдачу.");
        ungrantedHint.getStyle().set("color", "var(--lumo-secondary-text-color)");
        ungrantedSection.setPadding(false);
        ungrantedSection.setSpacing(false);
        ungrantedSection.add(ungrantedTitle, ungrantedHint, ungrantedGrid);

        // Для CHECK_ONLY-измерений ("доступ к виду документа целиком", без построчного
        // списка записей) — три обычных чекбокса вместо грида, см. loadAccessMatrix/
        // saveAccessMatrix и AccessGrantAdminService.kindOf.
        singleGrantUpdate.setEnabled(false);
        singleGrantDelete.setEnabled(false);
        singleGrantRead.addValueChangeListener(e -> {
            applySingleGrantEnabled(expressibleFlagsOfDimension, e.getValue());
            if (expressibleFlagsOfDimension.read() && !e.getValue()) {
                singleGrantUpdate.setValue(false);
                singleGrantDelete.setValue(false);
            }
        });
        HorizontalLayout singleGrantRow = new HorizontalLayout(
                new Span("Доступ:"), singleGrantRead,
                singleGrantUpdateLabel, singleGrantUpdate,
                singleGrantDeleteLabel, singleGrantDelete);
        singleGrantRow.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        singleGrantRow.setSpacing(true);

        Span hint = new Span("Запись без отмеченного \"Чтение\" недоступна там, где измерение вообще " +
                "выражает чтение: у такого измерения \"Изменение\"/\"Удаление\" отключены, пока не " +
                "отмечено \"Чтение\". У операционного измерения флага \"Чтение\" нет — доступ " +
                "решает объявленная операция, а чтение сущности — отдельный C5 read-ключ ENTITY:<FQN>.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");

        HorizontalLayout subjectRow = new HorizontalLayout(accessDimension, accessSubjectType, accessSubjectKey,
                saveButton, effectiveRightsButton);
        subjectRow.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END);
        subjectRow.setSpacing(true);
        subjectRow.setWidthFull();
        subjectRow.getStyle().set("flex-wrap", "wrap");

        accessTab.setSpacing(true);
        accessTab.setSizeFull();
        accessTab.add(subjectRow, hint, singleGrantRow, accessGrid, ungrantedSection);
        accessTab.setFlexGrow(1, accessGrid);
        this.singleGrantRowLayout = singleGrantRow;

        refreshUngrantedReport();
        loadAccessMatrix();
    }

    /**
     * Показывает определения, у которых нет ни одной решающей строки ни у одного субъекта:
     * зарегистрировано — и молча закрыто. Отчёт пуст — секция скрыта, чтобы не занимать место
     * сообщением «всё в порядке».
     */
    private void refreshUngrantedReport() {
        var rows = accessGrantAdminService.ungrantedDefinitions();
        ungrantedGrid.setItems(rows);
        ungrantedSection.setVisible(!rows.isEmpty());
    }

    /** Что решает определение — для столбца отчёта; пустое множество невозможно у определения. */
    private static String formatUngrantedOperations(java.util.Set<C5Operation> operations) {
        if (operations.contains(C5Operation.READ)) {
            return "чтение";
        }
        java.util.List<String> labels = new java.util.ArrayList<>();
        if (operations.contains(C5Operation.CREATE)) {
            labels.add("создание");
        }
        if (operations.contains(C5Operation.UPDATE)) {
            labels.add("изменение");
        }
        if (operations.contains(C5Operation.EXECUTE)) {
            labels.add("выполнение");
        }
        if (operations.contains(C5Operation.DELETE)) {
            labels.add("удаление");
        }
        return labels.isEmpty() ? "—" : String.join(", ", labels);
    }

    private String accessDimensionLabel(String key) {
        if (key.startsWith("ENTITY:")) {
            String entityKey = key.substring("ENTITY:".length());
            return entityLabels.getOrDefault(entityKey, entityKey) + " — права на объект";
        }
        if (key.startsWith("ACTION:")) {
            String permissionId = key.substring("ACTION:".length());
            String title = operationTitles.get(permissionId);
            // Сырой ключ остаётся как есть, если владелец не объявил название: без выдуманной
            // расшифровки администратор видит хотя бы точный ключ, который выдаёт.
            return title == null ? key : title + " — операция (" + key + ")";
        }
        return key;
    }

    /**
     * «Изменение»/«Удаление» без «Чтения» требуются только там, где измерение вообще выражает
     * «Чтение»: для именованной операции требовать чтение значило бы требовать право, которым
     * операция не решается (ADR-0017 §3 — EXECUTE решает «изменение»).
     */
    private void applySingleGrantEnabled(GrantFlags expressible, boolean read) {
        boolean readGate = expressible.read() && !read;
        singleGrantUpdate.setEnabled(expressible.update() && !readGate);
        singleGrantDelete.setEnabled(expressible.delete() && !readGate);
    }

    private void refreshSubjectKeyItems() {
        accessSubjectKey.setItems(accessSubjectType.getValue() == AccessGrant.SubjectType.USER
                ? accessGrantAdminService.allUsernames()
                : accessGrantAdminService.allRoleNames());
    }

    private Checkbox buildReadCheckbox(DimensionValueAccessRow row) {
        Checkbox checkbox = new Checkbox(row.read);
        checkbox.addValueChangeListener(e -> {
            row.read = e.getValue();
            if (!row.read) {
                row.update = false;
                row.delete = false;
            }
            accessGrid.getDataProvider().refreshItem(row);
        });
        return checkbox;
    }

    private Checkbox buildUpdateCheckbox(DimensionValueAccessRow row) {
        Checkbox checkbox = new Checkbox(row.update);
        checkbox.setEnabled(row.read);
        checkbox.addValueChangeListener(e -> row.update = e.getValue());
        return checkbox;
    }

    private Checkbox buildDeleteCheckbox(DimensionValueAccessRow row) {
        Checkbox checkbox = new Checkbox(row.delete);
        checkbox.setEnabled(row.read);
        checkbox.addValueChangeListener(e -> row.delete = e.getValue());
        return checkbox;
    }

    private void loadAccessMatrix() {
        String dimension = accessDimension.getValue();
        String subjectKey = accessSubjectKey.getValue();

        boolean checkOnly = dimension != null
                && accessGrantAdminService.kindOf(dimension) == RlsDimensionKind.CHECK_ONLY;
        accessGrid.setVisible(!checkOnly);
        singleGrantRowLayout.setVisible(checkOnly);

        if (dimension == null || subjectKey == null) {
            accessRows.clear();
            accessGrid.setItems(accessRows);
            return;
        }

        if (checkOnly) {
            // Показываются ровно те флаги, которые измерение выражает: C5 read — только
            // чтение (F-REST-READ-3, З2), операция — объявленные ею операции, где EXECUTE
            // решает флаг «изменение» и требоваться «чтение» не может.
            GrantFlags expressible = accessGrantAdminService.expressibleFlags(dimension);
            this.expressibleFlagsOfDimension = expressible;
            boolean namedOperation = accessGrantAdminService.isNamedOperationDimension(dimension);
            singleGrantUpdateLabel.setText(namedOperation ? "Выполнение:" : "Изменение:");
            singleGrantRead.setVisible(expressible.read());
            singleGrantUpdateLabel.setVisible(expressible.update());
            singleGrantUpdate.setVisible(expressible.update());
            singleGrantDeleteLabel.setVisible(expressible.delete());
            singleGrantDelete.setVisible(expressible.delete());
            GrantFlags flags = accessGrantAdminService.currentSingleGrant(dimension, accessSubjectType.getValue(), subjectKey);
            singleGrantRead.setValue(flags.read());
            singleGrantUpdate.setValue(flags.update());
            singleGrantDelete.setValue(flags.delete());
            applySingleGrantEnabled(expressible, flags.read());
            return;
        }
        this.expressibleFlagsOfDimension = new GrantFlags(true, true, true);
        singleGrantUpdateLabel.setText("Изменение:");
        singleGrantRead.setVisible(true);
        singleGrantUpdateLabel.setVisible(true);
        singleGrantUpdate.setVisible(true);
        singleGrantDeleteLabel.setVisible(true);
        singleGrantDelete.setVisible(true);

        accessRows.clear();
        Map<Long, GrantFlags> current = accessGrantAdminService.currentGrantsByDimensionValue(
                dimension, accessSubjectType.getValue(), subjectKey);
        for (AccessGrantAdminService.ValueRow value : accessGrantAdminService.allValues(dimension)) {
            accessRows.add(new DimensionValueAccessRow(value, current.getOrDefault(value.id(), GrantFlags.NONE)));
        }
        accessGrid.setItems(accessRows);
    }

    private void saveAccessMatrix() {
        String dimension = accessDimension.getValue();
        String subjectKey = accessSubjectKey.getValue();
        if (dimension == null || subjectKey == null) {
            Notification.show("Выберите измерение и пользователя или роль", 3000, Notification.Position.MIDDLE);
            return;
        }

        // Межлок: повторный клик по «Сохранить», пока первый запрос в полёте, игнорируется.
        if (accessSaveInProgress) {
            return;
        }
        accessSaveInProgress = true;
        saveAccessButton.setEnabled(false);
        try {
            if (accessGrantAdminService.kindOf(dimension) == RlsDimensionKind.CHECK_ONLY) {
                // Сохраняется только выражаемое измерением: скрытый чекбокс не должен
                // записать флаг, которого определение не решает.
                GrantFlags expressible = accessGrantAdminService.expressibleFlags(dimension);
                GrantFlags flags = new GrantFlags(
                        expressible.read() && singleGrantRead.getValue(),
                        expressible.update() && singleGrantUpdate.getValue(),
                        expressible.delete() && singleGrantDelete.getValue());
                accessGrantAdminService.saveSingleGrant(dimension, accessSubjectType.getValue(), subjectKey, flags);
            } else {
                Map<Long, GrantFlags> desired = new java.util.HashMap<>();
                for (DimensionValueAccessRow row : accessRows) {
                    desired.put(row.value.id(), new GrantFlags(row.read, row.update, row.delete));
                }
                accessGrantAdminService.saveGrants(dimension, accessSubjectType.getValue(), subjectKey, desired);
            }
            refreshUngrantedReport();

            Notification.show("Права сохранены для " + subjectKey, 2500, Notification.Position.BOTTOM_START)
                    .addThemeVariants(com.vaadin.flow.component.notification.NotificationVariant.LUMO_SUCCESS);
        } finally {
            accessSaveInProgress = false;
            saveAccessButton.setEnabled(true);
        }
    }

    /** Строка диалога «Эффективные права»: измерение + свёрнутое состояние и источник. */
    private static final class EffectiveRightsRow {
        final String dimension;
        final String values;
        final String read;
        final String update;
        final String delete;
        final String sources;

        EffectiveRightsRow(String dimension, String values, String read, String update,
                           String delete, String sources) {
            this.dimension = dimension;
            this.values = values;
            this.read = read;
            this.update = update;
            this.delete = delete;
            this.sources = sources;
        }
    }

    /**
     * Диалог «Эффективные права» (Фаза 7 RLS-плана): по выбранному субъекту — свёртка
     * прямых грантов и всех его ролей (с учётом wildcard "*") по каждому измерению.
     * Показывает ИТОГОВЫЙ доступ, которого на самом деле придерживается система, —
     * в отличие от матрицы редактирования, где видны только прямые строки субъекта.
     */
    private void openEffectiveRightsDialog() {
        String subjectKey = accessSubjectKey.getValue();
        if (subjectKey == null) {
            Notification.show("Выберите пользователя или роль", 3000, Notification.Position.MIDDLE);
            return;
        }
        AccessGrant.SubjectType subjectType = accessSubjectType.getValue();
        Map<String, EffectiveGrant> effective =
                accessGrantAdminService.collectEffective(subjectType, subjectKey);

        java.util.List<EffectiveRightsRow> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, EffectiveGrant> entry : effective.entrySet()) {
            EffectiveGrant grant = entry.getValue();
            rows.add(new EffectiveRightsRow(
                    entry.getKey(),
                    formatEffectiveValues(entry.getKey(), grant),
                    yesNo(grant.canRead()),
                    yesNo(grant.canUpdate()),
                    yesNo(grant.canDelete()),
                    grant.sources().isEmpty() ? "—" : String.join(", ", grant.sources())));
        }

        Grid<EffectiveRightsRow> grid = new Grid<>(EffectiveRightsRow.class, false);
        grid.addColumn(r -> r.dimension).setHeader("Измерение").setAutoWidth(true);
        grid.addColumn(r -> r.values).setHeader("Записи").setFlexGrow(1);
        grid.addColumn(r -> r.read).setHeader("Чтение").setWidth("90px").setFlexGrow(0);
        grid.addColumn(r -> r.update).setHeader("Изменение").setWidth("90px").setFlexGrow(0);
        grid.addColumn(r -> r.delete).setHeader("Удаление").setWidth("90px").setFlexGrow(0);
        grid.addColumn(r -> r.sources).setHeader("Источник").setWidth("260px").setFlexGrow(0);
        grid.setItems(rows);

        com.vaadin.flow.component.dialog.Dialog dialog = new com.vaadin.flow.component.dialog.Dialog(grid);
        dialog.setHeaderTitle("Эффективные права: " + subjectKey
                + (subjectType == AccessGrant.SubjectType.USER ? " (пользователь)" : " (роль)"));
        dialog.setWidth("820px");
        dialog.setHeight("480px");
        dialog.open();
    }

    /** Колонка «Записи»: "все" — wildcard-грант; "—" — CHECK_ONLY или нет прав на чтение; иначе коды записей. */
    private String formatEffectiveValues(String dimension, EffectiveGrant grant) {
        if (accessGrantAdminService.kindOf(dimension) == RlsDimensionKind.CHECK_ONLY
                || !grant.canRead()) {
            return "—";
        }
        if (grant.unlimited()) {
            return "все";
        }
        Map<Long, String> codesById = new java.util.HashMap<>();
        for (AccessGrantAdminService.ValueRow value : accessGrantAdminService.allValues(dimension)) {
            codesById.put(value.id(), value.code());
        }
        return grant.readableValueIds().stream()
                .map(id -> codesById.getOrDefault(id, String.valueOf(id)))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String yesNo(boolean value) {
        return value ? "Да" : "—";
    }
}
