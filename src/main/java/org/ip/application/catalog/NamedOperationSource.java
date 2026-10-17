package org.ip.application.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Источник названий именованных операций, объявляющий сразу несколько (ADR-0019 §5, ADR-0024 §2.4).
 *
 * <p>Зачем рядом с {@link NamedOperationOwner}. Одиночный владелец отвечает на вопрос «как
 * называется эта операция» и хорош там, где операция одна и её название прикладное
 * ({@code AttributeValuePermissions}, {@code UserPasswordChangePermission}). Платформенный регистр
 * отвечает иначе: его именованные операции объявляет декларация, а подписи собирает платформа
 * ({@code org.ipro.register.RegisterNamedOperations}). Реализовать {@link NamedOperationOwner}
 * платформенный модуль не может — интерфейс прикладной, — поэтому приложению нужен ровно
 * <b>один</b> адаптер, который переводит платформенный список в этот прикладной вид. Без
 * источника пришлось бы держать по бину подписи на каждый регистр — то, что убрал S5(а).</p>
 *
 * <p>Строк текста у реализации этого интерфейса нет: она называет не себя, а данные платформы.</p>
 */
public interface NamedOperationSource {

    /** Операции с их названиями; порядок не важен — потребители строят проекцию по id. */
    List<NamedOperationOwner> namedOperations();

    /**
     * Сливает одиночных владельцев и источники в один список: потребители каталога прав видят обе
     * формы, не зная, откуда пришло название.
     */
    static List<NamedOperationOwner> merge(List<NamedOperationOwner> owners,
                                           List<NamedOperationSource> sources) {
        List<NamedOperationOwner> all = new ArrayList<>(owners);
        for (NamedOperationSource source : sources) {
            all.addAll(source.namedOperations());
        }
        return List.copyOf(all);
    }
}
