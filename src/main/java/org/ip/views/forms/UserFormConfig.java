package org.ip.views.forms;

import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ip.model.Role;
import org.ip.model.User;
import org.ipro.crud.LookupService;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link UserItemForm} как default-вариант для {@link User}. UI получает
 * роли через canonical lookup (C4.6 волна A): собственный application-service роли удалён,
 * поэтому форма не открывает ни параллельный repository entry point, ни типизированный
 * сервис ради одной списочной выдачи.
 */
@Component
public class UserFormConfig implements ItemFormCustomization {

    private final LookupService lookupService;

    public UserFormConfig(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    @Override
    public Class<?> entityClass() {
        return User.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            var meta = ctx.metadataResolver().resolve(User.class);
            return new UserItemForm(meta, ctx.fieldFactory(), lookupService.findAll(Role.class));
        });
    }
}
