package com.example.generated.harness;

import java.util.List;
import java.util.Optional;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridView;
import org.ipro.form.spi.GridViewStore;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;

/** Consumer-owned persistence for the standard forms, stored in the same disposable file H2. */
final class AcceptanceStores implements FormSettingsStore, GridViewStore {
    private final JdbcTemplate jdbc;
    private final RlsCurrentUser user;
    AcceptanceStores(JdbcTemplate jdbc, RlsCurrentUser user) { this.jdbc=jdbc; this.user=user; }
    private String owner() { return java.util.Objects.requireNonNull(user.username(), "Authenticated user required"); }
    public Optional<String> get(String key) {
        return jdbc.query("select setting_value from acceptance_form_settings where owner_name=? and setting_key=?",
            (rs, row) -> rs.getString(1), owner(), key).stream().findFirst();
    }
    public void put(String key, String value) {
        jdbc.update("merge into acceptance_form_settings (owner_name,setting_key,setting_value) key(owner_name,setting_key) values (?,?,?)", owner(),key,value);
    }
    public void remove(String key) { jdbc.update("delete from acceptance_form_settings where owner_name=? and setting_key=?",owner(),key); }
    private GridView row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new GridView(rs.getLong("id"),rs.getString("form_key"),rs.getString("view_name"),rs.getString("columns_json"),rs.getBoolean("shared"),rs.getString("owner_name"));
    }
    public List<GridView> findVisibleViews(String key) {
        return jdbc.query("select * from acceptance_grid_views where form_key=? and (shared=true or owner_name=?) order by id",this::row,key,owner());
    }
    public Optional<GridView> findById(Long id) {
        return jdbc.query("select * from acceptance_grid_views where id=? and (shared=true or owner_name=?)",this::row,id,owner()).stream().findFirst();
    }
    public GridView createView(String form, String name, String columns, boolean shared) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("insert into acceptance_grid_views (form_key,view_name,columns_json,shared,owner_name) values (?,?,?,?,?)",new String[]{"id"});
            statement.setString(1,form);statement.setString(2,name);statement.setString(3,columns);statement.setBoolean(4,shared);statement.setString(5,owner());return statement;
        },keys);
        return findById(java.util.Objects.requireNonNull(keys.getKey()).longValue()).orElseThrow();
    }
    public GridView updateView(GridView view) {
        if (jdbc.update("update acceptance_grid_views set view_name=?,columns_json=?,shared=? where id=? and owner_name=?",
                view.name(),view.columns(),view.shared(),view.id(),owner()) != 1) throw new SecurityException("View is not owned by current user");
        return findById(view.id()).orElseThrow();
    }
    public void deleteView(Long id) {
        if (jdbc.update("delete from acceptance_grid_views where id=? and owner_name=?",id,owner()) != 1) throw new SecurityException("View is not owned by current user");
    }
}
