package org.ipro.vaadin.explorer.ui;

import org.ipro.metadata.annotation.EntityKind;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;

/** Caller-owned UI state, independent of a component, security principal or Workspace. */
public final class EntityExplorerRegistryState {
    public enum Grouping { KIND, SUBSYSTEM }
    public static final String NO_SUBSYSTEM = "@none";
    public static final String UNKNOWN_EXPOSURE = "UNKNOWN";
    private String query = "", subsystem, exposure, selectedType;
    private EntityKind kind;
    private boolean onlyErrors, showServiceTypes, overview;
    private Grouping grouping = Grouping.KIND;
    private final Set<String> collapsed = new HashSet<>();
    public String query() { return query; }
    public void setQuery(String value) { query = value == null ? "" : value; }
    public EntityKind kind() { return kind; }
    public void setKind(EntityKind value) {
        if (value == EntityKind.AUTO) throw new IllegalArgumentException("AUTO is not a resolved kind");
        kind = value;
    }
    public String subsystem() { return subsystem; }
    public void setSubsystem(String value) { subsystem = value; }
    public String exposure() { return exposure; }
    public void setExposure(String value) { exposure = value; }
    public boolean onlyErrors() { return onlyErrors; }
    public void setOnlyErrors(boolean value) { onlyErrors = value; }
    public boolean showServiceTypes() { return showServiceTypes; }
    public void setShowServiceTypes(boolean value) { showServiceTypes = value; }
    public Grouping grouping() { return grouping; }
    public void setGrouping(Grouping value) { grouping = Objects.requireNonNull(value); }
    public String selectedType() { return selectedType; }
    public void select(String fqn) { selectedType = fqn; }
    public boolean overview() { return overview; }
    public void setOverview(boolean value) { overview = value; }
    public boolean collapsed(String groupId) { return collapsed.contains(groupId); }
    public void setCollapsed(String groupId, boolean value) { if (value) collapsed.add(groupId); else collapsed.remove(groupId); }
    public Set<String> collapsedGroups() { return Set.copyOf(collapsed); }
    public boolean hasFilters() {
        return !query.isBlank() || kind != null || subsystem != null || exposure != null || onlyErrors || showServiceTypes;
    }
    /** Grouping and stable expansion keys are user preferences, not filter conditions. */
    public void resetFilters() { query = ""; kind = null; subsystem = null; exposure = null; onlyErrors = false; showServiceTypes = false; }
}
