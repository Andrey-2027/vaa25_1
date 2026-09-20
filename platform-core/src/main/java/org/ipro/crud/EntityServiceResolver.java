package org.ipro.crud;

import org.ipro.identity.IdentifiableEntity;

/**
 * Публичный type-directed резолв data handle сущности (D3.5.1, APP_API).
 *
 * <p>Реализация — {@link ServiceLocator} (MODULE_API). Сам класс публичным не делать:
 * он несет Spring {@code ApplicationContext}, metadata-резолв и детали индексирования
 * типизированных сервисов — все это внутренности wiring, а не контракт приложения.</p>
 *
 * <p>Порядок решения ровно один и не зависит от порядка регистрации бинов:</p>
 * <ol>
 *   <li>типизированный application service под своим entity type;</li>
 *   <li>иначе canonical generic handle, если descriptor типа его допускает;</li>
 *   <li>иначе отказ с реальной причиной до SQL (owned-строка секции, internal store
 *       без явного read-моста, неизвестный тип).</li>
 * </ol>
 *
 * <p>Два сервиса на один тип — ошибка конфигурации на старте, а не скрытый приоритет.</p>
 *
 * <p>Что именно экспонируется: возвращается {@link BaseService} — data handle, а не "безопасный"
 * read-only фасад. Это осознанное решение, а не утечка: потребители этого интерфейса
 * ({@code WorkshopForm}, {@code WorkshopListView}, {@code ItemFormSaveDispatcher})
 * читают <b>и</b> сохраняют запись, то есть им нужен тот же handle с его сценариями записи,
 * RLS-контуром и optimistic-проверками, что и у предметного сервиса. Потребителю, которому
 * нужно только чтение, здесь нечего делать — его контракт
 * {@link EntityLookup}: лимитированный поиск и загрузка выбранной записи по id, без доступа к
 * записи вообще. Поэтому роли разные, а не "одна на все чтения".</p>
 */
public interface EntityServiceResolver {

    /**
     * Найти data handle для сущности.
     *
     * @param entityClass класс сущности
     * @return типизированный service либо canonical handle
     * @throws IllegalStateException если handle нет (owned-строка, недопущенный тип,
     *                              отсутствие сервиса) — с причиной и вариантами исправления
     */
    <T extends IdentifiableEntity, ID> BaseService<T, ID> findService(Class<T> entityClass);
}
