package org.ipro.rls;

/**
 * Нейтральный контракт отображаемого имени значения RLS-измерения для админки.
 *
 * <p>Существует ради направления зависимостей (шаг 8б): {@code RlsDimensionValueCatalog}
 * раньше звал {@code MetadataResolver} (select-колонки) и статику
 * {@code InstanceNameBridge.displayName}, то есть подсистема принуждения зависела от
 * подсистем метаданных и выборки. Теперь RLS знает только этот интерфейс, а реализацию
 * поверх метаданных и fetch-плана поставляет приложение. Контракт намеренно не
 * импортирует ни metadata, ни fetch, ни crud.</p>
 */
@FunctionalInterface
public interface RlsDimensionValueLabelResolver {

    /** Код и отображаемое имя значения сущности типа {@code entityType}. */
    Labels resolve(Class<?> entityType, Object value);

    /** Пара для каталога: {@code code} идёт в сортировку, {@code name} — на экран. */
    record Labels(String code, String name) {
    }
}
