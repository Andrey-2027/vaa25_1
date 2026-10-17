package org.ip.application.register;

import org.ip.application.catalog.NamedOperationOwner;
import org.ip.application.catalog.NamedOperationSource;
import org.ipro.register.RegisterNamedOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Один адаптер к каталогу прав вместо четырёх бинов подписей (ADR-0024 §2.4).
 *
 * <p>Подписи именованных операций регистра собирает платформа: имя регистра объявлено его
 * декларацией ({@code @RegisterMetadata.displayName}), форма текста одна на всех. Приложению
 * остаётся только перевести платформенный список в прикладной вид {@link NamedOperationOwner},
 * чтобы его потребители ({@code PermissionCatalogService}, {@code AdminView}) видели регистровые
 * операции там же, где операции со своими названиями.</p>
 *
 * <p>Здесь нет ни одной строки текста и ни одного бина на регистр: объявил регистр в декларации —
 * получил названные операции. Тот же класс обслуживает и следующий регистр, и все последующие.</p>
 */
@Component
public class RegisterOperationTitles implements NamedOperationSource {

    private final RegisterNamedOperations operations;

    public RegisterOperationTitles(RegisterNamedOperations operations) {
        this.operations = operations;
    }

    @Override
    public List<NamedOperationOwner> namedOperations() {
        List<NamedOperationOwner> owners = new ArrayList<>();
        for (RegisterNamedOperations.Operation operation : operations.operations()) {
            owners.add(new Title(operation.permissionId(), operation.title()));
        }
        return List.copyOf(owners);
    }

    private record Title(String permissionId, String title) implements NamedOperationOwner {
    }
}
