package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import com.vaadin.flow.spring.annotation.SpringComponent;
import java.util.*;
import java.util.function.Consumer;
import org.ip.application.access.*;
import org.ip.model.User;
import org.ip.model.Role;
import org.ip.persistence.access.PermissionScopePageService;
import org.ipro.filtergrid.TextFilter;
import org.ipro.form.Dirtyable;
import org.ipro.form.Savable;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.rls.AccessGrant;
import org.springframework.context.annotation.Scope;

/** P5 shell. Matrix/scope components are installed into the right content slot in P6/P7. */
@SpringComponent
@Scope("prototype")
public class PermissionWorkspace extends VerticalLayout implements Dirtyable, Savable, BeforeLeaveObserver {
    private final PermissionStateService states;
    private final PermissionPatchService patches;
    private final PermissionDraftStore drafts = new PermissionDraftStore();
    private final ListForm<User, Long> users;
    private final ListForm<Role, Long> roles;
    private final Tab usersTab = new Tab("Пользователи");
    private final Tab rolesTab = new Tab("Роли");
    private final Tabs tabs = new Tabs(usersTab, rolesTab);
    private final H3 title = new H3();
    private final Span assigned = new Span();
    private final Span status = new Span();
    private final Span error = new Span();
    private final Button save = new Button("Сохранить", event -> doSave());
    private final Button cancel = new Button("Отменить", event -> cancelActive());
    private final VerticalLayout content = new VerticalLayout();
    private final List<Consumer<PermissionStateService.Snapshot>> listeners = new ArrayList<>();
    private PermissionStateService.Snapshot active;
    private boolean refreshing;
    private record ReturnPoint(SubjectRef.Identity subject, Runnable restore) { }
    private final java.util.Deque<ReturnPoint> returnSubjects = new java.util.ArrayDeque<>();
    private final Button returnToSubject = new Button("Вернуться к исходному субъекту", event -> returnFromRole());
    private Consumer<PermissionGrantAddress> revealPermission = address -> { };

    @org.springframework.beans.factory.annotation.Autowired
    public PermissionWorkspace(FormNavigator coordinator, PermissionStateService states, PermissionPatchService patches,
                                PermissionWorkspaceUpdates updates, PermissionCatalogService catalogs,
                                org.ip.service.AccessGrantAdminService administration, PermissionScopePageService pages) {
        this(coordinator, states, patches, updates);
        var matrix = new PermissionObjectMatrix(this, catalogs.catalog());
        matrix.setUngrantedReport(administration::ungrantedDefinitions);
        var editors = new java.util.LinkedHashMap<String, PermissionScopeEditor<?>>();
        Runnable back = () -> { content.removeAll(); content.add(matrix); matrix.render(); };
        matrix.setScopeNavigation(id -> {
            var scope = catalogs.catalog().scope(id).orElseThrow();
            var editor = editors.computeIfAbsent(id, ignored -> createScopeEditor(catalogs.catalog(), scope, pages, back));
            content.removeAll(); content.add(editor); editor.refresh();
        });
        revealPermission = address -> {
            if (address.authority() == PermissionBinding.Authority.RLS && catalogs.catalog().scope(address.key()).isPresent()) {
                var scope = catalogs.catalog().scope(address.key()).orElseThrow();
                var editor = editors.computeIfAbsent(scope.id(), ignored -> createScopeEditor(catalogs.catalog(), scope, pages, back));
                content.removeAll(); content.add(editor); editor.reveal(address.valueId());
            } else { back.run(); matrix.reveal(address.key()); }
        };
        addSubjectListener(snapshot -> editors.values().forEach(PermissionScopeEditor::refresh));
        content.add(matrix);
    }

    private <T> PermissionScopeEditor<T> createScopeEditor(PermissionCatalog catalog, PermissionCatalog.Scope scope,
            PermissionScopePageService pages, Runnable back) {
        @SuppressWarnings("unchecked") Class<T> type = (Class<T>) scope.valueType();
        return new PermissionScopeEditor<>(this, catalog, scope, type, pages, states, back);
    }

    public PermissionWorkspace(FormNavigator coordinator, PermissionStateService states, PermissionPatchService patches,
                                PermissionWorkspaceUpdates updates) {
        this.states = states; this.patches = patches;
        users = coordinator.createListForm(User.class);
        roles = coordinator.createListForm(Role.class);
        users.setSizeFull(); roles.setSizeFull();
        users.getFilterGrid().setCompact(true); roles.getFilterGrid().setCompact(true);
        users.getGrid().setSelectionMode(com.vaadin.flow.component.grid.Grid.SelectionMode.SINGLE);
        roles.getGrid().setSelectionMode(com.vaadin.flow.component.grid.Grid.SelectionMode.SINGLE);
        users.getGrid().asSingleSelect().addValueChangeListener(event -> {
            if (tabs.getSelectedTab() == usersTab && !refreshing) select(event.getValue() == null ? null : SubjectRef.of(event.getValue()).identity());
        });
        roles.getGrid().asSingleSelect().addValueChangeListener(event -> {
            if (tabs.getSelectedTab() == rolesTab && !refreshing) select(event.getValue() == null ? null : SubjectRef.of(event.getValue()).identity());
        });
        tabs.addSelectedChangeListener(event -> {
            boolean user = tabs.getSelectedTab() == usersTab;
            users.setVisible(user); roles.setVisible(!user);
            if (refreshing) return;
            select(user ? Optional.ofNullable(users.getSelectedItem()).map(SubjectRef::of).map(SubjectRef::identity).orElse(null)
                : Optional.ofNullable(roles.getSelectedItem()).map(SubjectRef::of).map(SubjectRef::identity).orElse(null));
        });
        users.getGrid().getDataProvider().addDataProviderListener(event -> dataChanged(true));
        roles.getGrid().getDataProvider().addDataProviderListener(event -> dataChanged(false));
        var left = new VerticalLayout(tabs, users, roles); left.setSizeFull(); left.setPadding(false);
        roles.setVisible(false);
        var header = new HorizontalLayout(save, cancel, new Button("Обновить права", event -> reloadActive()), returnToSubject);
        header.add(new Button("Показать все роли", event -> roles.clearContextFilter()));
        var right = new VerticalLayout(title, assigned, status, header, error, content); right.setSizeFull();
        right.getStyle().set("min-height", "0").set("overflow", "hidden");
        right.setFlexShrink(0, title, assigned, status, header, error);
        right.setFlexGrow(1, content); content.setSizeFull();
        content.setPadding(false);
        content.getStyle().set("min-height", "0").set("overflow", "auto");
        var split = new SplitLayout(left, right); split.setSizeFull(); split.setSplitterPosition(32);
        left.getStyle().set("min-width", "240px"); right.getStyle().set("min-width", "300px");
        split.getStyle().set("min-height", "0");
        add(split); setSizeFull(); setPadding(false); setFlexGrow(1, split);
        getStyle().set("min-height", "0").set("overflow", "hidden");
        addAttachListener(event -> {
            var ui = event.getUI();
            var registration = updates.subscribe(() -> ui.access(() -> {
                var identity = active == null ? null : active.subject().identity();
                refreshing = true;
                try { users.refresh(); roles.refresh(); }
                finally { refreshing = false; }
                if (identity != null) select(identity);
            }));
            addDetachListener(detach -> registration.remove());
            reloadActive();
        });
        showState();
    }

    public void select(SubjectRef.Identity identity) {
        error.setText("");
        try { active = identity == null ? null : states.objectsWithDraft(identity, drafts.patch(identity).orElse(null)); }
        catch (RuntimeException failure) { active = null; error.setText("Не удалось загрузить субъект: " + failure.getMessage()); }
        showState(); listeners.forEach(listener -> listener.accept(active));
    }
    public void reloadActive() { if (active != null) select(active.subject().identity()); }
    private void dataChanged(boolean userListChanged) {
        if (refreshing) return;
        // A refreshed/filtered list may no longer contain its selected row. Never keep a hidden target active.
        refreshing = true;
        try {
            if (userListChanged) users.getGrid().deselectAll(); else roles.getGrid().deselectAll();
            if (userListChanged == (tabs.getSelectedTab() == usersTab)) active = null;
        }
        finally { refreshing = false; }
        showState(); listeners.forEach(listener -> listener.accept(active));
    }
    private void showState() {
        returnToSubject.setVisible(!returnSubjects.isEmpty());
        title.setText(active == null ? "Выберите пользователя или роль" : active.subject().title());
        assigned.setText(active == null || active.subject().type() != AccessGrant.SubjectType.USER ? ""
            : "Роли: " + (active.roles().isEmpty() ? "не назначены" : String.join(", ", active.roles().stream().map(SubjectRef::title).toList())));
        int count = active == null ? 0 : drafts.changeCount(active.subject().identity());
        int others = drafts.dirtySubjects().size() - (active != null && drafts.dirtySubjects().contains(active.subject().identity()) ? 1 : 0);
        status.setText("Изменений: " + count + ". Других субъектов с черновиками: " + others);
        save.setEnabled(active != null && count > 0); cancel.setEnabled(active != null && count > 0);
        content.setVisible(active != null);
    }
    /** Role identity is supplied by provenance, never parsed from a label or looked up by name. */
    public void navigateRole(SubjectRef role, PermissionGrantAddress address) {
        if (role.type() != AccessGrant.SubjectType.ROLE) throw new IllegalArgumentException("Expected role");
        if (active != null && !active.subject().identity().equals(role.identity())) {
            var originalContent = content.getChildren().toList();
            var restorers = originalContent.stream().map(component -> component instanceof PermissionScopeEditor<?> editor
                ? editor.captureContext() : component instanceof PermissionObjectMatrix matrix ? matrix.captureContext() : (Runnable) () -> { }).toList();
            returnSubjects.push(new ReturnPoint(active.subject().identity(), () -> {
                content.removeAll(); originalContent.forEach(content::add); restorers.forEach(Runnable::run);
            }));
        }
        refreshing = true;
        try {
            tabs.setSelectedTab(rolesTab); users.setVisible(false); roles.setVisible(true);
            roles.clearVisualFilter(); roles.clearContextFilter();
            var nameFilter = roles.getFilterGrid().getColumnFilter("name");
            if (nameFilter instanceof TextFilter<?> text) text.getTextField().clear();
            roles.setContextFilter((root, query, cb) -> cb.equal(root.get("id"), role.id()));
            Role row = new Role(role.subjectKey()); row.setId(role.id()); roles.getGrid().select(row);
        } finally { refreshing = false; }
        select(role.identity());
        error.setText("Переход к роли по id: фильтры списка ролей сброшены, показана целевая роль.");
        roles.getGrid().scrollToStart(); roles.getGrid().getElement().callJsFunction("focus");
        revealPermission.accept(address);
    }
    public void returnFromRole() {
        if (returnSubjects.isEmpty()) return;
        var target = returnSubjects.pop();
        var identity = target.subject();
        refreshing = true;
        try {
            boolean user = identity.type() == AccessGrant.SubjectType.USER;
            tabs.setSelectedTab(user ? usersTab : rolesTab); users.setVisible(user); roles.setVisible(!user);
        } finally { refreshing = false; }
        select(identity);
        target.restore().run();
    }
    public void change(PermissionGrantAddress address, PermissionBinding.Flag flag, boolean enabled) {
        if (active == null) throw new IllegalStateException("Select a subject first");
        var subject = active.subject();
        var prior = drafts.patch(subject.identity()).stream().flatMap(patch -> patch.changes().stream())
            .filter(change -> change.address().equals(address)).findFirst();
        var baseline = prior.map(PermissionPatch.Change::expectedRows).orElseGet(() -> patches.baseline(subject, address));
        drafts.change(subject, address, flag, enabled, baseline); showState();
    }
    @Override public boolean doSave() {
        if (active == null) return !isDirty();
        var identity = active.subject().identity();
        try {
            var patch = drafts.patch(identity);
            if (patch.isPresent()) { patches.save(patch.get()); drafts.saved(identity); }
            select(identity);
            if (com.vaadin.flow.component.UI.getCurrent() != null) Notification.show("Права сохранены");
            return !isDirty();
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(PermissionWorkspace.class).warn("Permission save failed", failure);
            error.setText(failure.getMessage()); showState(); return false;
        }
    }
    public void cancelActive() {
        if (active == null) return;
        drafts.cancel(active.subject().identity()); reloadActive();
    }
    @Override public boolean isDirty() { return !drafts.dirtySubjects().isEmpty(); }
    @Override public String getCloseConfirmMessage() {
        return "Есть черновики прав у " + drafts.dirtySubjects().size()
            + " субъектов. Сохранение относится только к выбранному субъекту. Вернитесь к остальным изменениям либо явно откажитесь от них.";
    }
    @Override public void beforeLeave(BeforeLeaveEvent event) {
        if (!isDirty()) return;
        var navigation = event.postpone(); var dialog = new Dialog();
        dialog.setHeaderTitle("Несохранённые права"); dialog.add(new Span(getCloseConfirmMessage()));
        dialog.getFooter().add(new Button("Сохранить текущего", click -> { if (doSave()) { dialog.close(); navigation.proceed(); } }),
            new Button("Вернуться к изменениям", click -> dialog.close()),
            new Button("Отказаться от всех изменений", click -> {
                drafts.dirtySubjects().forEach(drafts::cancel); dialog.close(); navigation.proceed();
            }));
        dialog.open();
    }
    public Optional<PermissionStateService.Snapshot> active() { return Optional.ofNullable(active); }
    public PermissionDraftStore drafts() { return drafts; }
    public VerticalLayout content() { return content; }
    public void addSubjectListener(Consumer<PermissionStateService.Snapshot> listener) { listeners.add(listener); }
    public ListForm<User, Long> userList() { return users; }
    public ListForm<Role, Long> roleList() { return roles; }
}
