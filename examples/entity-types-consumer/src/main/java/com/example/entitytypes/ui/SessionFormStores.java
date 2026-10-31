package com.example.entitytypes.ui;

import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.form.spi.GridView;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal in-memory stores. Personal settings are isolated by user; restart clears them. */
@Component
public class SessionFormStores implements FormSettingsStore, GridViewStore {
    private final RlsCurrentUser user;
    private final Map<String, Map<String, String>> settings = new ConcurrentHashMap<>();
    private final Map<Long, GridView> views = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    public SessionFormStores(RlsCurrentUser user) { this.user = user; }
    private Map<String, String> personal() {
        return settings.computeIfAbsent(user.requireAuthenticatedUsername(), ignored -> new ConcurrentHashMap<>());
    }
    @Override public Optional<String> get(String key) { return Optional.ofNullable(personal().get(key)); }
    @Override public void put(String key, String value) { personal().put(key, value); }
    @Override public void remove(String key) { personal().remove(key); }
    @Override public List<GridView> findVisibleViews(String formKey) {
        String owner = user.requireAuthenticatedUsername();
        return views.values().stream().filter(v -> v.formKey().equals(formKey)
                && (v.shared() || v.createdBy().equals(owner))).sorted(Comparator.comparing(GridView::id)).toList();
    }
    @Override public Optional<GridView> findById(Long id) {
        String owner = user.requireAuthenticatedUsername();
        return Optional.ofNullable(views.get(id)).filter(v -> v.shared() || v.createdBy().equals(owner));
    }
    @Override public synchronized GridView createView(String formKey, String name, String columns, boolean shared) {
        var view = new GridView(sequence.incrementAndGet(), Objects.requireNonNull(formKey),
                Objects.requireNonNull(name), Objects.requireNonNull(columns), shared, user.requireAuthenticatedUsername());
        views.put(view.id(), view); return view;
    }
    @Override public synchronized GridView updateView(GridView view) {
        GridView existing = requireOwner(view.id());
        var updated = new GridView(existing.id(), existing.formKey(), Objects.requireNonNull(view.name()),
                Objects.requireNonNull(view.columns()), view.shared(), existing.createdBy());
        views.put(updated.id(), updated); return updated;
    }
    @Override public synchronized void deleteView(Long id) { requireOwner(id); views.remove(id); }
    private GridView requireOwner(Long id) {
        String owner = user.requireAuthenticatedUsername();
        GridView existing = views.get(id);
        if (existing == null || !existing.createdBy().equals(owner)) {
            throw new IllegalArgumentException("Представление недоступно для изменения");
        }
        return existing;
    }
}
