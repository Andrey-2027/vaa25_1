package org.ipro.crud;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Стабильный read-only контракт lookup-чтения для приложения и Report (D3.5.1, APP_API).
 *
 * <p>Реализация — {@link LookupService} (MODULE_API). Класс напрямую не повышать до APP_API:
 * его конструктор принимает module-level {@code CanonicalReadExecutor}, overload с произвольными
 * fetch-путями раскрывает механику загрузки, а {@code findAll()} выполняет потенциально
 * неограниченное чтение — все это признаки внутреннего адаптера, а не спроектированного
 * контракта.</p>
 *
 * <p>Границы контракта:</p>
 * <ul>
 *   <li>только чтение; записи — через {@code EntityDataAccess} либо предметный use case;</li>
 *   <li>без безусловного {@code findAll} (для больших справочников это скрытая выгрузка таблицы);</li>
 *   <li>{@code findSelectedById} использует сценарий {@code LOOKUP}, а не {@code DETAIL},
 *       чтобы сохранить объявленные зависимости {@code @Lookup(fetch = ...)};</li>
 *   <li>поиск ограничен {@code limit}; пустой/null term — первые записи в детерминированном порядке.</li>
 *   <li>только чтение: если потребителю нужен handle с записью, это
 *       {@link EntityServiceResolver}, а не расширение этого интерфейса.</li>
 * </ul>
 *
 * <p>D3.5.3: граница лимита объявлена и проверяется ({@link #MAX_LIMIT}). Иначе обещание
 * «без безусловного find» держал один параметр, а {@code Integer.MAX_VALUE} в вызове — та же
 * выгрузка таблицы, только написанная руками и не видная ни в одном гейте.</p>
 */
public interface EntityLookup {

    /**
     * Верхняя граница {@code limit}: больше — это уже не lookup, а полная выборка.
     *
     * <p>Проверяется в {@code LookupRead} — единственной точке построения lookup-запроса, поэтому
     * граница действует и для будущих потребителей, а не только для этой реализации. Нужна полная
     * выборка — это list-read ({@code LIST}/Pageable) или отдельный use case с явным намерением, а
     * не lookup с подкрученным лимитом.</p>
     */
    int MAX_LIMIT = 1000;

    /**
     * Поиск сущностей по подстроке (case-insensitive) в указанных полях.
     *
     * @param entityClass класс сущности
     * @param searchFields имена Java-полей для поиска (например, {@code ["code", "name"]})
     * @param term искомая подстрока (пустая или null — все записи в пределах лимита)
     * @param limit максимум записей
     * @return список найденных сущностей
     */
    <T> List<T> search(Class<T> entityClass, Collection<String> searchFields, String term, int limit);

    /**
     * Загрузить выбранную запись по id сценарием {@code LOOKUP}.
     *
     * @param entityClass класс сущности
     * @param id идентификатор (null — пусто)
     * @return запись или пусто
     */
    <T> Optional<T> findSelectedById(Class<T> entityClass, Object id);
}
