package org.ip.views.forms;

import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ip.model.User;
import org.ip.service.RoleService;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link UserItemForm} как default-вариант для {@link User}. UI получает
 * роли через application-service и не открывает параллельный repository entry point.
 */
@Component
public class UserFormConfig implements ItemFormCustomization {

    private final RoleService roleService;

    public UserFormConfig(RoleService roleService) {
        this.roleService = roleService;
    }

    @Override
    public Class<?> entityClass() {
        return User.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            var meta = ctx.metadataResolver().resolve(User.class);
            return new UserItemForm(meta, ctx.fieldFactory(), roleService.findAll());
        });
    }
}
