package org.ipro.rls;

import java.util.Optional;

/**
 * Нейтральный контракт owned-секций для repository-границы RLS.
 *
 * <p>Существует ради направления зависимостей (шаг 8б):
 * {@code RlsRepositoryEnforcementAspect} раньше звал {@code SectionMetadataRegistry},
 * то есть подсистема принуждения зависела от метаданных. Теперь аспект знает только
 * этот интерфейс, а реализацию поверх реестра секций поставляет приложение.</p>
 *
 * <p>Отсутствие реализации — не silent fail-open: бин аспекта требует этот контракт
 * обязательным параметром, и без адаптера контекст не стартует.</p>
 */
@FunctionalInterface
public interface RlsOwnedSectionLookup {

    /**
     * Ключ owned-секции для класса строки или {@code Optional.empty()}, если класс —
     * не строка табличной части. Найденная секция означает отказ прямого доступа
     * в репозиторий (только aggregate-сервис секции).
     */
    Optional<String> sectionKey(Class<?> rowType);
}
