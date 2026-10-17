package org.ipro.register;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Определения именованных операций регистра вместе с их подписями для человека (ADR-0024 §2.4).
 *
 * <p><b>Почему подпись собирает платформа.</b> Идентификатор операции уже стабилен и принадлежит
 * платформе ({@link RegisterKeyChangePermissions#permissionId(Class)},
 * {@link RegisterKeyWritePermissions#setPermissionId(Class)}), а название писал приложение —
 * четырьмя бинами {@code NamedOperationOwner} на регистр. Этот класс возвращает подписи как
 * <b>данные</b>: текст выводится из {@link RegisterMetadata#displayName()} объявленного регистра,
 * поэтому «объявил регистр и забыл» здесь работает, а форма подписи одна на всех и не может
 * разойтись между регистрами.</p>
 *
 * <p>Пустое имя сюда не доходит: декларацию с включённой операцией и пустым {@code displayName}
 * отвергает {@link RegisterMetadataValidator}, и {@link RegisterRegistry} не публикует снимок с
 * такой декларацией. Подпись не может исчезнуть молча — ошибка видна на старте приложения.</p>
 *
 * <p>Прикладной интерфейс остаётся прикладным: платформа не реализует {@code NamedOperationOwner}
 * и не знает дерева приложения. Приложение читает этот список один раз своим адаптером к каталогу
 * прав и не держит ни одной строки текста.</p>
 */
public final class RegisterNamedOperations {

    /** Одна именованная операция: стабильный идентификатор и её подпись для человека. */
    public record Operation(String permissionId, String title) {
        public Operation {
            Objects.requireNonNull(permissionId, "permissionId");
            Objects.requireNonNull(title, "title");
        }
    }

    private final RegisterRegistry registers;

    public RegisterNamedOperations(RegisterRegistry registers) {
        this.registers = Objects.requireNonNull(registers, "registers");
    }

    /**
     * Операции всех объявленных регистров, в порядке деклараций. Регистр без включённых
     * именованных операций не даёт ни одной записи — возможность остаётся opt-in.
     */
    public List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        for (RegisterDescriptor descriptor : registers.descriptors()) {
            String name = descriptor.displayName();
            Class<?> type = descriptor.entityType();
            if (descriptor.keyChangeAllowed()) {
                operations.add(new Operation(RegisterKeyChangePermissions.permissionId(type),
                    "Изменить ключ записи: " + name));
            }
            if (descriptor.keyWriteAllowed()) {
                operations.add(new Operation(RegisterKeyWritePermissions.setPermissionId(type),
                    "Записать по ключу: " + name));
                operations.add(new Operation(RegisterKeyWritePermissions.deletePermissionId(type),
                    "Удалить по ключу: " + name));
            }
        }
        return List.copyOf(operations);
    }
}
