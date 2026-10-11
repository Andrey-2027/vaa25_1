package org.ip.application.register;

import org.ip.application.catalog.NamedOperationOwner;
import org.ip.model.NomenclaturePrice;
import org.ip.model.NomenclatureAccountingObject;
import org.ipro.register.RegisterKeyChangePermissions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Human titles for pilot action issuance. Definitions remain owned by register infrastructure. */
@Configuration(proxyBeanMethods = false)
public class RegisterKeyPermissionTitles {
    @Bean public NamedOperationOwner priceKeyPermissionTitle() {
        return title(NomenclaturePrice.class, "Изменить ключ записи: Цены номенклатуры");
    }

    @Bean public NamedOperationOwner accountingObjectKeyPermissionTitle() {
        return title(NomenclatureAccountingObject.class, "Изменить ключ записи: Объекты учёта номенклатуры");
    }

    private static NamedOperationOwner title(Class<?> type, String text) {
        return new NamedOperationOwner() {
            @Override public String permissionId() { return RegisterKeyChangePermissions.permissionId(type); }
            @Override public String title() { return text; }
        };
    }
}
