package org.ip.views.forms;

import org.ip.form.builder.ItemFormCustomization;
import org.ip.form.builder.ItemFormVariants;
import org.ip.model.User;
import org.ip.repository.RoleRepository;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link UserItemForm} как default-вариант для {@link User}. RoleRepository —
 * обычная Spring-зависимость этого конфига (не через FormContext.getParameter) — список
 * ролей для MultiSelectComboBox нужен только здесь, заводить для этого что-то в FormContext
 * не требуется.
 */
@Component
public class UserFormConfig implements ItemFormCustomization {

    private final RoleRepository roleRepository;

    public UserFormConfig(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    public Class<?> entityClass() {
        return User.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            var meta = ctx.metadataResolver().resolve(User.class);
            return new UserItemForm(meta, ctx.fieldFactory(), roleRepository.findAll());
        });
    }
}
