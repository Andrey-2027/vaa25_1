package org.ipro.data.grouping;

import jakarta.persistence.EntityManager;
import org.ipro.filtergrid.grouping.CriteriaGroupValuesService;
import org.ipro.filtergrid.grouping.GroupValuesService;

/**
 * C4.8: JPA-реализация {@link GroupingValuesProviderFactory}.
 *
 * <p>Единственное место, где построение группировки списка знает о persistence context, и
 * находится оно вне {@code org.ipro.form}/{@code org.ip.views}, поэтому UI-слой больше не
 * владеет {@code EntityManager}. Бин объявляется в {@code DataAccessAutoConfiguration}: платформа
 * исключена из component-scan приложения и регистрируется явно.</p>
 *
 * <p>D1: порт {@link GroupingValuesProviderFactory} объявлен в этом же пакете, поэтому
 * {@code org.ipro.data} не зависит от {@code org.ipro.form} (направление {@code form -> data}).</p>
 */
public class JpaGroupingValuesProviderFactory implements GroupingValuesProviderFactory {

    private final EntityManager entityManager;

    public JpaGroupingValuesProviderFactory(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public <T> GroupValuesService<T> create(Class<T> entityClass) {
        return new CriteriaGroupValuesService<>(entityManager, entityClass);
    }
}
