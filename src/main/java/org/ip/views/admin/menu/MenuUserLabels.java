package org.ip.views.admin.menu;

import org.ip.model.User;
import org.ipro.data.EntityDataAccess;
import org.springframework.stereotype.Component;

/** Name of a user by the immutable key that the navigation module stores in an assignment. */
@Component
public class MenuUserLabels {
    private final EntityDataAccess data;

    public MenuUserLabels(EntityDataAccess data) {
        this.data = data;
    }

    public String label(String userKey) {
        try {
            return data.detail(User.class, Long.parseLong(userKey)).map(User::getUsername).orElse("#" + userKey);
        } catch (RuntimeException unreadable) {
            return "#" + userKey;
        }
    }
}
