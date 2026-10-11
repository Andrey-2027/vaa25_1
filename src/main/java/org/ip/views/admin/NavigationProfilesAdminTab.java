package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationConflictException;
import org.ipro.navigation.NavigationProfile;
import org.ipro.navigation.NavigationResolver;
import org.ipro.navigation.NavigationTree;
import org.ipro.navigation.NavigationUserDirectory;
import org.ipro.navigation.NavigationVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Scope;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Application-owned editor. All persistent operations use the protected navigation API. */
@SpringComponent
@Scope("prototype")
@ConditionalOnProperty(name = "platform.navigation.enabled", havingValue = "true")
public class NavigationProfilesAdminTab extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(NavigationProfilesAdminTab.class);
    private final NavigationAdministrationService administration;
    private final NavigationAdministrationAccess access;
    private final NavigationProfileDraft draft;
    private final ComboBox<NavigationProfile> profiles = new ComboBox<>("Профиль интерфейса");
    private final TreeGrid<NavigationProfileDraft.Row> tree = new TreeGrid<>();
    private final TreeGrid<PreviewEntry> preview = new TreeGrid<>();
    private final Span status = new Span();
    private final VerticalLayout stale = new VerticalLayout();
    private final ComboBox<NavigationUserDirectory.UserOption> users = new ComboBox<>("Пользователь");
    private final Span assignment = new Span("Выберите пользователя");
    private final Button save = new Button("Сохранить", event -> save());
    private final Button rename = new Button("Переименовать", event -> renameDialog());
    private final Button reset = new Button("Сбросить к настройкам разработчика", event -> confirmReset());
    private final Button assign = new Button("Назначить профиль", event -> assign());
    private final Button unassign = new Button("Снять назначение", event -> unassign());
    private List<NavigationProfile> available = List.of();
    private boolean updating;
    private boolean initialized;
    private boolean assignmentLoaded;
    private record PreviewEntry(String key, String title, List<PreviewEntry> children) {}

    public NavigationProfilesAdminTab(NavigationAdministrationService administration,
                                      NavigationAdministrationAccess access,
                                      NavigationCatalog catalog, NavigationResolver resolver) {
        this.administration = administration;
        this.access = access;
        this.draft = new NavigationProfileDraft(catalog, resolver);
        setSizeFull();
        setMinHeight("0");
        getStyle().set("overflow", "auto");
        profiles.setWidth("min(100%, 32rem)");
        profiles.setItemLabelGenerator(NavigationProfilesAdminTab::profileLabel);
        profiles.addValueChangeListener(event -> {
            if (updating) return;
            if (event.getValue() == null) { restoreSelection(); return; }
            var next = event.getValue();
            restoreSelection();
            discardThen(() -> perform(() -> loadProfile(next.id())));
        });
        var createName = new TextField("Название нового профиля");
        createName.setMaxLength(120);
        var create = new Button("Создать профиль", event -> discardThen(() -> perform(() -> {
            var created = administration.createProfile(validName(createName.getValue()));
            draft.load(created);
            updateProfileList(created);
            renderDraft();
            createName.clear();
            status.setText("Профиль создан");
        })));
        var reload = new Button("Обновить", event -> discardThen(() -> perform(this::reload)));
        add(new H3("Интерфейс"), new Span("Настройки меню применяются после обновления страницы или нового входа. "
            + "Открытые вкладки сохраняются."), new HorizontalLayout(profiles, rename, reload),
            new HorizontalLayout(createName, create), status);
        tree.addHierarchyColumn(NavigationProfileDraft.Row::title).setHeader("Подсистема / пункт").setFlexGrow(2);
        tree.addColumn(row -> row.defaultVisible() ? "Показывать" : "Скрывать").setHeader("Настройка разработчика");
        tree.addComponentColumn(this::visibilityEditor).setKey("visibility").setHeader("Профиль").setWidth("13rem").setFlexGrow(0);
        tree.addColumn(draft::outcome).setHeader("Итог до применения прав").setFlexGrow(2);
        tree.setHeight("28rem");
        tree.setWidthFull();
        stale.setPadding(false);
        stale.setVisible(false);
        add(tree, new HorizontalLayout(save, reset), stale,
            new H3("Предпросмотр состава профиля"),
            new Span("Предпросмотр не учитывает права конкретного пользователя. "
                + "Права чтения проверяются при построении его меню и открытии данных."));
        preview.addHierarchyColumn(PreviewEntry::title).setHeader("Видимые разделы и пункты");
        preview.setHeight("18rem");
        preview.setWidthFull();
        add(preview, new H3("Назначение пользователю"));
        users.setItemLabelGenerator(NavigationUserDirectory.UserOption::title);
        users.setWidth("min(100%, 32rem)");
        users.setPageSize(50);
        users.setHelperText("Первые 50 совпадений. Уточните логин, если нужного пользователя нет в списке.");
        users.setItems(query -> {
            // Bounded canonical lookup; no unbounded user-list read or manual user ID input.
            var found = new ArrayList<NavigationUserDirectory.UserOption>();
            perform(() -> found.addAll(administration.lookupUsers(query.getFilter().orElse(""), 50)));
            return found.stream().skip(query.getOffset()).limit(query.getLimit());
        });
        users.addValueChangeListener(event -> loadAssignment());
        add(new Span("Назначение сохраняется отдельно. Перед назначением сохраните изменения состава профиля."),
            users, assignment, new HorizontalLayout(assign, unassign,
                new Button("Обновить назначение", event -> loadAssignment())));
        // The editor scrolls as a whole; flex shrinking would collapse both grids to a few rows.
        getChildren().forEach(child -> setFlexShrink(0, child));
        updateControls();
    }

    public void refresh() {
        perform(() -> { if (!initialized) reload(); });
    }

    private void requireAdmin() {
        if (!access.isAdministrator()) throw new AccessDeniedException("Navigation administration required");
    }

    private void perform(Runnable action) {
        try {
            requireAdmin();
            action.run();
        } catch (NavigationConflictException conflict) {
            status.setText("Профиль изменён другим администратором. Черновик сохранён. "
                + "Скопируйте нужные изменения и нажмите «Обновить», чтобы перечитать профиль.");
        } catch (AccessDeniedException denied) {
            if (!access.isAdministrator()) {
                status.setText("Доступно только администратору");
                setEnabled(false);
            } else {
                status.setText("Недостаточно прав для действия. Для поиска и назначения проверьте права чтения пользователей.");
            }
        } catch (IllegalArgumentException invalid) {
            status.setText(invalid.getMessage());
        } catch (RuntimeException failure) {
            log.error("Navigation profile editor operation failed", failure);
            status.setText("Не удалось выполнить действие. Введённые изменения сохранены в редакторе.");
        }
    }

    private void reload() {
        var selected = draft.profile();
        available = administration.listProfiles();
        var target = selected == null ? available.stream().findFirst()
            : available.stream().filter(profile -> profile.id() == selected.id()).findFirst();
        if (target.isPresent()) draft.load(administration.profile(target.get().id()));
        updateProfileList(target.isPresent() ? draft.profile() : null);
        initialized = true;
        renderDraft();
        status.setText(target.isPresent() ? "Профиль загружен" : "Создайте первый профиль");
    }

    private void loadProfile(long id) {
        var profile = administration.profile(id);
        draft.load(profile);
        updateProfileList(profile);
        renderDraft();
        status.setText("Профиль загружен");
    }

    private void updateProfileList(NavigationProfile selected) {
        // No read after a successful write: a failed refresh must not report a committed save as failed.
        if (selected != null) {
            var list = new ArrayList<>(available.stream().filter(profile -> profile.id() != selected.id()).toList());
            list.add(selected);
            available = list.stream().sorted(java.util.Comparator.comparing(NavigationProfile::name)).toList();
        }
        updating = true;
        try {
            profiles.setItems(available);
            profiles.setValue(selected);
        } finally { updating = false; }
    }

    private void select(NavigationProfile profile) {
        updating = true;
        try { profiles.setValue(profile); }
        finally { updating = false; }
    }

    private void restoreSelection() { select(draft.profile()); }

    private ComboBox<NavigationVisibility> visibilityEditor(NavigationProfileDraft.Row row) {
        var field = new ComboBox<NavigationVisibility>();
        field.setAriaLabel("Видимость: " + row.title());
        field.setItems(NavigationVisibility.values());
        field.setItemLabelGenerator(value -> switch (value) {
            case DEFAULT -> "По умолчанию";
            case SHOW -> "Показывать";
            case HIDE -> "Скрывать";
        });
        field.setValue(draft.visibility(row));
        field.addValueChangeListener(event -> perform(() -> {
            if (event.getValue() == null) { field.setValue(NavigationVisibility.DEFAULT); return; }
            draft.change(row, event.getValue());
            tree.getDataProvider().refreshAll();
            renderPreview();
            updateControls();
            status.setText(draft.dirty() ? "Есть несохранённые изменения" : "Изменений нет");
        }));
        return field;
    }

    private void renderDraft() {
        var data = new TreeData<NavigationProfileDraft.Row>();
        if (draft.profile() != null) data.addItems(draft.roots(), NavigationProfileDraft.Row::children);
        tree.setDataProvider(new TreeDataProvider<>(data));
        tree.expandRecursively(draft.roots(), 20);
        stale.removeAll();
        var keys = draft.profile() == null ? java.util.Set.<String>of() : draft.profile().staleKeys();
        stale.setVisible(!keys.isEmpty());
        if (!keys.isEmpty()) {
            stale.add(new Span("Устаревших пунктов: " + keys.size() + ". Они отсутствуют в каталоге, "
                + "сохраняются при обычном сохранении и удаляются при сбросе профиля."));
            keys.stream().sorted().forEach(key -> stale.add(new Span(key)));
        }
        renderPreview();
        updateControls();
    }

    private void renderPreview() {
        var data = new TreeData<PreviewEntry>();
        if (draft.profile() != null) data.addItems(draft.preview().roots().stream().map(this::previewEntry).toList(), PreviewEntry::children);
        preview.setDataProvider(new TreeDataProvider<>(data));
        preview.expandRecursively(data.getRootItems(), 20);
    }

    private PreviewEntry previewEntry(NavigationTree.Node node) {
        var children = new ArrayList<PreviewEntry>();
        node.items().forEach(item -> children.add(new PreviewEntry(item.key(), item.title(), List.of())));
        node.children().forEach(child -> children.add(previewEntry(child)));
        return new PreviewEntry("subsystem:" + node.id(), node.title(), List.copyOf(children));
    }

    private void save() {
        perform(() -> {
            var profile = Objects.requireNonNull(draft.profile());
            var saved = administration.saveProfile(profile.id(), profile.version(), draft.values());
            draft.load(saved);
            updateProfileList(saved);
            renderDraft();
            status.setText("Профиль сохранён. Для применения меню обновите страницу.");
        });
    }

    private void renameDialog() {
        perform(() -> {
            var profile = Objects.requireNonNull(draft.profile());
            var dialog = new Dialog();
            dialog.setHeaderTitle("Переименовать профиль");
            var name = new TextField("Название профиля");
            name.setMaxLength(120);
            name.setValue(profile.name());
            var error = new Span();
            dialog.add(name, error);
            dialog.getFooter().add(new Button("Отмена", event -> dialog.close()), new Button("Переименовать", event -> {
                perform(() -> {
                    var renamed = administration.renameProfile(profile.id(), profile.version(), validName(name.getValue()));
                    draft.renamed(renamed);
                    updateProfileList(renamed);
                    updateControls();
                    status.setText("Название сохранено. Изменения видимости сохраняются отдельно.");
                    dialog.close();
                });
                if (dialog.isOpened()) error.setText(status.getText());
            }));
            dialog.open();
        });
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120)
            throw new IllegalArgumentException("Введите название профиля длиной от 1 до 120 символов");
        return name.strip();
    }

    private void confirmReset() {
        perform(() -> confirm("Сбросить профиль?", "Все сохранённые настройки, устаревшие пункты и локальные изменения "
            + "будут сброшены. Назначения пользователям сохранятся.", () -> perform(() -> {
                var profile = Objects.requireNonNull(draft.profile());
                var saved = administration.resetProfile(profile.id(), profile.version());
                draft.load(saved);
                updateProfileList(saved);
                renderDraft();
                status.setText("Профиль сброшен. Для применения меню обновите страницу.");
            })));
    }

    private void discardThen(Runnable action) {
        perform(() -> {
            if (draft.dirty()) confirm("Отменить несохранённые изменения?", "Локальные изменения видимости будут потеряны.", action);
            else action.run();
        });
    }

    private void confirm(String title, String text, Runnable action) {
        var dialog = new ConfirmDialog(title, text, "Продолжить", event -> action.run());
        dialog.setCancelable(true);
        dialog.setCancelText("Отмена");
        dialog.open();
    }

    private void loadAssignment() {
        assignmentLoaded = false;
        assignment.setText("Загрузка назначения…");
        perform(this::refreshAssignment);
        if (!assignmentLoaded) assignment.setText("Не удалось загрузить назначение. Нажмите «Обновить назначение».");
        updateControls();
    }

    private void refreshAssignment() {
        if (users.getValue() == null) assignment.setText("Выберите пользователя");
        else {
            var id = administration.assignmentForUser(users.getValue().key());
            assignment.setText(id.map(key -> "Текущий профиль: " + available.stream()
                .filter(profile -> profile.id() == key).map(NavigationProfile::name).findFirst().orElse("#" + key))
                .orElse("Назначения нет: используются настройки разработчика"));
        }
        assignmentLoaded = true;
        updateControls();
    }

    private void assign() {
        perform(() -> {
            var user = Objects.requireNonNull(users.getValue());
            var profile = Objects.requireNonNull(draft.profile());
            administration.assignUser(user.key(), profile.id());
            assignment.setText("Текущий профиль: " + profile.name());
            status.setText("Профиль назначен. Пользователю нужно обновить страницу.");
        });
    }

    private void unassign() {
        perform(() -> {
            administration.unassignUser(Objects.requireNonNull(users.getValue()).key());
            assignment.setText("Назначения нет: используются настройки разработчика");
            status.setText("Назначение снято. Пользователю нужно обновить страницу.");
        });
    }

    private void updateControls() {
        save.setEnabled(draft.dirty());
        rename.setEnabled(draft.profile() != null);
        reset.setEnabled(draft.profile() != null);
        assign.setEnabled(draft.profile() != null && !draft.dirty() && users.getValue() != null && assignmentLoaded);
        unassign.setEnabled(users.getValue() != null && assignmentLoaded);
    }

    private static String profileLabel(NavigationProfile profile) {
        return profile.name() + " · устаревших: " + profile.staleKeys().size();
    }
}
