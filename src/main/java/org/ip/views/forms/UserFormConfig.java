package org.ip.views.forms;

import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ip.model.Role;
import org.ip.model.User;
import org.ipro.crud.EntityLookup;
import org.ipro.form.LookupComboHelper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Регистрирует {@link UserItemForm} как default-вариант для {@link User}. UI получает
 * роли через canonical handle (C4.6 волна A): собственный application-service роли удалён,
 * поэтому форма не открывает ни параллельный repository entry point, ни типизированный
 * сервис ради одной списочной выдачи.
 *
 * <p>Волна E + D3.5.2: роли читаются bounded {@link EntityLookup#search} как маленький
 * закрытый справочник (десятки записей, явный лимит), а не выгрузкой всей таблицы.</p>
 */
@Component
public class UserFormConfig implements ItemFormCustomization {

    private final EntityLookup lookupService;

    public UserFormConfig(EntityLookup lookupService) {
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
            return new UserItemForm(meta, ctx.fieldFactory(),
                lookupService.search(Role.class, List.of("name"), "",
                    LookupComboHelper.DICTIONARY_LIMIT));
        });
    }
}
