package org.ipro.metadata;

import java.util.List;

/**
 * Источник состава имени сущности для metadata-кода (углубление fetch-графов).
 *
 * <p>Введён как отдельный контракт, чтобы {@code org.ipro.metadata} не зависел от
 * {@code org.ipro.fetch} (граница C3, см. ADR-0006): реализация живёт в C3
 * ({@code InstanceNameResolver}), а metadata получает только функцию «из чего складывается
 * имя сущности». Пустой список — у сущности нет объявленного единого имени, и потребитель
 * остаётся на прежнем metadata-производном составе.</p>
 */
@FunctionalInterface
public interface InstanceNameSource {

    /** Состав имени (attribute paths) объявившей InstanceName сущности; пусто — декларации нет. */
    List<String> pathsOf(Class<?> entityClass);

    /** Заглушка для вызовов без C3-резолвера (юнит-тесты, не-Spring контексты). */
    InstanceNameSource NONE = entityClass -> List.of();
}
