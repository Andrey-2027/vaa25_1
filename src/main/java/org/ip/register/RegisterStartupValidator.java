package org.ip.register;

import org.ipro.metadata.ManagedEntityCatalog;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Проверяет все managed-регистры до завершения refresh, включая регистры без читателей. */
@Component
public final class RegisterStartupValidator implements SmartInitializingSingleton {

    private final ManagedEntityCatalog catalog;

    public RegisterStartupValidator(ManagedEntityCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> errors = new ArrayList<>();
        catalog.managedEntityClasses().stream()
            .filter(type -> type.isAnnotationPresent(RegisterMetadata.class))
            .sorted(Comparator.comparing(Class::getName))
            .forEach(type -> {
                for (RegisterMetadataValidator.Violation violation : RegisterMetadataValidator.validate(type)) {
                    String field = violation.field() == null ? "" : " поле '" + violation.field() + "'";
                    errors.add(type.getName() + " [" + violation.code() + "]" + field + ": "
                        + violation.message());
                }
            });
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Некорректные декларации регистров сведений:\n"
                + String.join("\n", errors));
        }
    }
}
