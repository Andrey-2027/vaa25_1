package org.ipro.navigation;

/** An explicitly declared operation exposed through a navigation profile. */
public record NavigationCommand(String key, Class<?> entityClass, String action, String variant,
                                String title, String icon, int order) {
    public boolean defaultVisible() {
        return false;
    }
}
