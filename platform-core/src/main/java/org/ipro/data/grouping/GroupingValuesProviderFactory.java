package org.ipro.data.grouping;

import org.ipro.filtergrid.grouping.GroupValuesService;

/**
 * C4.8: узкий typed-контракт построения группировки формы списка.
 *
 * <p>До C4.8 {@code FormResolver} держал {@code EntityManager} напрямую только ради одного
 * вызова {@code new CriteriaGroupValuesService<>(entityManager, type)} — это была
 * единственная UI-утечка persistence context (её отслеживал
 * {@code CompatibilityMigrationArchitectureTest.uiDoesNotOwnPersistenceContext}).</p>
 *
 * <p>Теперь форма зависит от этого контракта, а JPA-реализация
 * ({@link JpaGroupingValuesProviderFactory}) живёт вне UI-пакетов.
 * Для ручных срезов без группировки фабрика может быть {@code null} — тогда список
 * строится без провайдера значений, как и раньше.</p>
 *
 * <p><b>D1: контракт объявлен в слое данных, а не в UI.</b> Порт был объявлен в
 * {@code org.ipro.form.grouping} (потребитель), а реализация — в
 * {@code org.ipro.data.grouping}. Это создавало направление {@code data -> form}: слой
 * канонического data access компилировался против UI-пакета, то есть нижний слой знал
 * о верхнем. Зависимость была не косметической — {@code DataAccessAutoConfiguration}
 * импортировал UI-интерфейс. Порт перенесён к реализации, и направление стало
 * {@code form -> data} (см. правило {@code PlatformDependencyDirectionTest}).</p>
 */
public interface GroupingValuesProviderFactory {

    <T> GroupValuesService<T> create(Class<T> entityClass);
}
