package org.ip.views.admin;

import java.util.Map;

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
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * «Администрирование» (после разделения): только Доступ (RLS), Настройки,
 * Нумерация. Всё, что касается телеметрии (журнал, ошибки, трассировка,
 * агрегаты, история изменений), переехало в отдельный вид
 * {@link DiagnosticsView} («Диагностика»).
 */
@SpringComponent
@Scope("prototype")
public class AdminView extends VerticalLayout {

    private final AccessGrantAdminService accessGrantAdminService;
    private final SettingsAdminTab settingsTab;
    private final NumberingAdminTab numberingTab;

    private final Grid<DimensionValueAccessRow> accessGrid = new Grid<>();
    private final VerticalLayout accessTab = new VerticalLayout();

    private final ComboBox<String> accessDimension = new ComboBox<>("Измерение");
    private final ComboBox<AccessGrant.SubjectType> accessSubjectType = new ComboBox<>("Тип субъекта");
    private final ComboBox<String> accessSubjectKey = new ComboBox<>("Пользователь/роль");
    private final java.util.List<DimensionValueAccessRow> accessRows = new java.util.ArrayList<>();
    private final Checkbox singleGrantRead = new Checkbox("");
    private final Checkbox singleGrantUpdate = new Checkbox("");
    private final Checkbox singleGrantDelete = new Checkbox("");
    private HorizontalLayout singleGrantRowLayout;
    private final Button saveAccessButton = new Button("Сохранить", new Icon(VaadinIcon.CHECK), e -> saveAccessMatrix());
    private final Button effectiveRightsButton = new Button("Эффективные права",
            new Icon(VaadinIcon.EYE), e -> openEffectiveRightsDialog());
    private boolean accessSaveInProgress;

    public AdminView(@Autowired AccessGrantAdminService accessGrantAdminService,
                     @Autowired SettingsAdminTab settingsTab,
                     @Autowired NumberingAdminTab numberingTab) {
        this.accessGrantAdminService = accessGrantAdminService;
        this.settingsTab = settingsTab;
        this.numberingTab = numberingTab;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (isAdmin()) {
            buildUi();
        } else {
            add(new H3("Доступно только администратору"));
        }
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }

    private void buildUi() {
        add(new H3("Администрирование"));

        Tab accessItem = new Tab(new Span("Доступ (RLS)"), new Icon(VaadinIcon.KEY));
        Tab settingsItem = new Tab(new Span("Настройки"), new Icon(VaadinIcon.COGS));
        Tab numberingItem = new Tab(new Span("Нумерация"), new Icon(VaadinIcon.HASH));
        Tabs tabs = new Tabs(accessItem, settingsItem, numberingItem);
        add(tabs);

        buildAccessTab();

        add(accessTab, settingsTab, numberingTab);
        show(accessTab);

        tabs.addSelectedChangeListener(e -> {
            if (e.getSelectedTab() == settingsItem) {
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
        active.setVisible(true);
    }

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
        accessDimension.setItems(accessGrantAdminService.availableDimensions());
        accessDimension.setAllowCustomValue(false);
        if (!accessGrantAdminService.availableDimensions().isEmpty()) {
            accessDimension.setValue(accessGrantAdminService.availableDimensions().get(0));
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

        // Для CHECK_ONLY-измерений ("доступ к виду документа целиком", без построчного
        // списка записей) — три обычных чекбокса вместо грида, см. loadAccessMatrix/
        // saveAccessMatrix и AccessGrantAdminService.kindOf.
        singleGrantUpdate.setEnabled(false);
        singleGrantDelete.setEnabled(false);
        singleGrantRead.addValueChangeListener(e -> {
            singleGrantUpdate.setEnabled(e.getValue());
            singleGrantDelete.setEnabled(e.getValue());
            if (!e.getValue()) {
                singleGrantUpdate.setValue(false);
                singleGrantDelete.setValue(false);
            }
        });
        HorizontalLayout singleGrantRow = new HorizontalLayout(
                new Span("Доступ:"), singleGrantRead,
                new Span("Изменение:"), singleGrantUpdate,
                new Span("Удаление:"), singleGrantDelete);
        singleGrantRow.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        singleGrantRow.setSpacing(true);

        Span hint = new Span("Запись без отмеченного \"Чтение\" для выбранного пользователя/роли — " +
                "недоступна вообще (запись гранта не создаётся). \"Изменение\"/\"Удаление\" " +
                "без \"Чтение\" не имеют смысла — отключены, пока не отмечено \"Чтение\".");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)");

        HorizontalLayout subjectRow = new HorizontalLayout(accessDimension, accessSubjectType, accessSubjectKey,
                saveButton, effectiveRightsButton);
        subjectRow.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END);
        subjectRow.setSpacing(true);

        accessTab.setSpacing(true);
        accessTab.setSizeFull();
        accessTab.add(subjectRow, hint, singleGrantRow, accessGrid);
        accessTab.setFlexGrow(1, accessGrid);
        this.singleGrantRowLayout = singleGrantRow;

        loadAccessMatrix();
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
            GrantFlags flags = accessGrantAdminService.currentSingleGrant(dimension, accessSubjectType.getValue(), subjectKey);
            singleGrantRead.setValue(flags.read());
            singleGrantUpdate.setValue(flags.update());
            singleGrantUpdate.setEnabled(flags.read());
            singleGrantDelete.setValue(flags.delete());
            singleGrantDelete.setEnabled(flags.read());
            return;
        }

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
                GrantFlags flags = new GrantFlags(
                        singleGrantRead.getValue(), singleGrantUpdate.getValue(), singleGrantDelete.getValue());
                accessGrantAdminService.saveSingleGrant(dimension, accessSubjectType.getValue(), subjectKey, flags);
            } else {
                Map<Long, GrantFlags> desired = new java.util.HashMap<>();
                for (DimensionValueAccessRow row : accessRows) {
                    desired.put(row.value.id(), new GrantFlags(row.read, row.update, row.delete));
                }
                accessGrantAdminService.saveGrants(dimension, accessSubjectType.getValue(), subjectKey, desired);
            }

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
