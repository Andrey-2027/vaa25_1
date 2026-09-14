package org.ip.views.forms;

import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ip.model.Role;
import org.ip.model.User;
import org.ipro.crud.ServiceLocator;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link UserItemForm} как default-вариант для {@link User}. UI получает
 * роли через canonical handle (C4.6 волна A): собственный application-service роли удалён,
 * поэтому форма не открывает ни параллельный repository entry point, ни типизированный
 * сервис ради одной списочной выдачи.
 *
 * <p>Волна E: резолв идёт через {@link ServiceLocator}, а не через {@code LookupService} —
 * кастомизация формы не зависит от класса, чей API начинается с «перечитать выбранное
 * значение по ID» (ADX-07, {@code ApplicationFormFetchBoundaryTest}).</p>
 */
@Component
public class UserFormConfig implements ItemFormCustomization {

    private final ServiceLocator serviceLocator;

    public UserFormConfig(ServiceLocator serviceLocator) {
        this.serviceLocator = serviceLocator;
    }

    @Override
    public Class<?> entityClass() {
        return User.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            var meta = ctx.metadataResolver().resolve(User.class);
            return new UserItemForm(meta, ctx.fieldFactory(),
                serviceLocator.<Role, Long>findService(Role.class).findAll());
        });
    }
}
