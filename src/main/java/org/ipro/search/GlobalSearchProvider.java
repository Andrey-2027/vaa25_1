package org.ipro.search;

import jakarta.persistence.EntityManager;

import java.util.List;

/**
 * Типизированный провайдер одного источника глобального поиска.
 *
 * <p>Провайдер владеет способом выполнения bounded-запроса и преобразованием сущности
 * в безопасные значения результата. Общий сервис владеет каталогом, лимитами и RLS,
 * поэтому провайдер не может случайно обойти общие гейты.</p>
 */
public interface GlobalSearchProvider<T> {

    /** Сущность, которую обслуживает провайдер. */
    Class<T> entityClass();

    /** Выполнить ограниченный запрос по уже разрешённой декларации источника. */
    List<T> search(EntityManager entityManager,
                   GlobalSearchSource source,
                   String term,
                   int limit,
                   int timeoutMs);

    /** Извлечь технический идентификатор для навигации, не отдавая entity в UI. */
    Object idOf(T entity);

    /** Сформировать готовую подпись результата. */
    String displayValue(T entity, GlobalSearchSource source);

    /** Определить вид и поле совпадения для уже найденной записи. */
    GlobalSearchMatch classify(T entity, GlobalSearchSource source, String term);
}
