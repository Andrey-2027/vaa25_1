package org.ip.views.query;

import org.ip.service.FormSettingsService;
import org.ip.service.GridViewStoreAdapter;
import org.ipro.form.spi.GridView;
import org.ipro.metadata.GridViewState;
import org.ipro.query.ui.listform.QueryListViews;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
class ApplicationQueryListViews implements QueryListViews {
    private final GridViewStoreAdapter store;
    private final FormSettingsService settings;
    private final RlsCurrentUser users;

    ApplicationQueryListViews(GridViewStoreAdapter store, FormSettingsService settings, RlsCurrentUser users) {
        this.store = store;
        this.settings = settings;
        this.users = users;
    }

    @Override
    public List<View> views(String formKey) {
        requireUser();
        return store.findVisibleViews(formKey).stream().map(this::view).toList();
    }

    @Override
    public Optional<String> defaultViewId(String formKey) {
        requireUser();
        return settings.get(settingKey(formKey));
    }

    @Override
    public void setDefaultView(String formKey, String viewId) {
        requireUser();
        if (viewId == null) {
            settings.remove(settingKey(formKey));
        } else {
            requireVisible(formKey, viewId);
            settings.put(settingKey(formKey), viewId);
        }
    }

    @Override
    public View create(String formKey, String name, boolean shared, GridViewState state) {
        requireUser();
        return view(store.createView(formKey, name, state.toJson(), shared));
    }

    @Override
    public View update(String formKey, String viewId, String name, boolean shared, GridViewState state) {
        var existing = requireVisible(formKey, viewId);
        return view(store.updateView(new GridView(existing.id(), formKey, name, state.toJson(), shared, existing.createdBy())));
    }

    @Override
    public void delete(String formKey, String viewId) {
        var existing = requireVisible(formKey, viewId);
        store.deleteView(existing.id());
    }

    private GridView requireVisible(String formKey, String viewId) {
        requireUser();
        return store.findVisibleViews(formKey).stream().filter(item -> item.id().toString().equals(viewId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Вид списка не найден"));
    }

    private View view(GridView stored) {
        return new View(stored.id().toString(), stored.name(), stored.shared(),
                stored.shared() || requireUser().equals(stored.createdBy()), GridViewState.fromJson(stored.columns()));
    }

    private String requireUser() {
        return users.requireAuthenticatedUsername();
    }

    private static String settingKey(String formKey) {
        return "listform.defaultview." + formKey;
    }
}
