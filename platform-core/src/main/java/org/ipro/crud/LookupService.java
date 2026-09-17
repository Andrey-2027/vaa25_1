package org.ipro.crud;

import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.DetailRead;
import org.ipro.data.ListRead;
import org.ipro.data.LookupRead;
import org.ipro.fetch.plan.FetchScenario;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Сервис динамического поиска сущностей. Используется EntityField для автокомплита
 * и SelectionForm для поиска по подстроке.
 *
 * <p>Поиск работает для ЛЮБОГО {@code @Entity} класса — даже если у него нет Spring Data
 * Repository. Для операций записи используйте canonical handle
 * ({@link org.ipro.data.EntityDataAccess}) либо предметный use case типа —
 * LookupService только для ЧТЕНИЯ.</p>
 *
 * <p>C4.1 (ADR-0007 §4): собственная Criteria/fetch orchestration удалена. Lookup идёт
 * через {@link CanonicalReadExecutor} — ту же границу, что и standard reads, с единым
 * правилом {@code scenario plan ∪ extras -> deepen once -> graph}, обязательным RLS gate
 * и проверкой экспозиции типа до SQL.</p>
 */
@Service
public class LookupService {

    private final CanonicalReadExecutor readExecutor;

    public LookupService(CanonicalReadExecutor readExecutor) {
        this.readExecutor = readExecutor;
    }

    /**
     * Поиск сущностей по подстроке (case-insensitive) в указанных полях.
     *
     * @param entityClass  класс сущности
     * @param searchFields имена Java-полей для поиска (например, {"code", "name"})
     * @param term         искомая подстрока (пустая или null → все записи)
     * @param limit        максимум записей
     * @return список найденных сущностей
     */
    public <T> List<T> search(Class<T> entityClass, String[] searchFields, String term, int limit) {
        return search(entityClass, searchFields, term, limit, null);
    }

    /**
     * То же с дополнительными fetch-путями. Базовый план {@code LOOKUP} добавляется в
     * любом случае; дополнительные пути только расширяют его.
     */
    public <T> List<T> search(Class<T> entityClass, String[] searchFields, String term, int limit,
                              Collection<String> fetchPaths) {
        return readExecutor.readLookup(new LookupRead<>(entityClass,
            searchFields == null ? List.of() : List.of(searchFields), term, limit, fetchPaths));
    }

    /** Получить все записи сущности. */
    public <T> List<T> findAll(Class<T> entityClass) {
        return readExecutor.readAll(new ListRead<>(entityClass, FetchScenario.LOOKUP,
            null, Pageable.unpaged(), List.of()));
    }

    /** Получить запись по ID. */
    public <T> Optional<T> findById(Class<T> entityClass, Object id) {
        return findById(entityClass, id, null);
    }

    /**
     * Получить запись по ID с eager-загрузкой указанных связей через fetch-граф
     * (в т.ч. вложенных через точку: "nomenclature.unitOfMeasurement" → subgraph).
     * Нужно, когда сущность после выбора в UI-компоненте читается вне сессии.
     *
     * <p>Сценарий — {@code LOOKUP}, а не {@code DETAIL}: только план выбора несёт
     * объявленные зависимости {@code @Lookup(fetch = ...)} (например, единицу измерения
     * выбранной номенклатуры). Extra-пути расширяют его, но не заменяют.</p>
     */
    public <T> Optional<T> findById(Class<T> entityClass, Object id, Collection<String> fetchPaths) {
        if (id == null) {
            return Optional.empty();
        }
        return readExecutor.readDetail(DetailRead.lookup(entityClass, id, fetchPaths));
    }
}
