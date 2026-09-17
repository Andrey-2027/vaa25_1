package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import org.ipro.data.fixture.C4FixtureEntity;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C4.8: сортировка списка по to-many-пути отклоняется до SQL.
 *
 * <p>Ранее такой путь «поддерживался» добавлением {@code distinct}: порядок корня по элементу
 * коллекции не определён, а в PostgreSQL {@code SELECT DISTINCT} с {@code ORDER BY} по
 * join'нутой коллекции невалиден. H2, на котором идут остальные тесты, этой ошибки не
 * воспроизводит — поэтому проверка идёт на уровне решения executor'а по JPA metamodel, а не
 * наблюдаемого результата запроса.</p>
 */
class CanonicalReadSortGuardTest {

    private EntityManager entityManager;
    private Metamodel metamodel;
    private CanonicalReadExecutor executor;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        metamodel = mock(Metamodel.class);
        when(entityManager.getMetamodel()).thenReturn(metamodel);
        // Маркер «дошли до построения Criteria»: так проверяется, что guard сортировки
        // действительно пропустил запрос, а не отклонил его по другой причине.
        when(entityManager.getCriteriaBuilder()).thenThrow(
            new IllegalStateException("дошли до построения Criteria"));

        EntityDescriptorCatalog catalog = mock(EntityDescriptorCatalog.class);
        when(catalog.descriptorOf(C4FixtureEntity.class)).thenReturn(new EntityDescriptor(
            C4FixtureEntity.class, EntityExposure.STANDARD_ROOT, true, true,
            new EntityCapabilities(Set.of(FetchScenario.LIST), Set.of(DataOperation.CREATE),
                "тест"), "тест"));

        RlsReadGate readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), any())).thenReturn(true);
        executor = new CanonicalReadExecutor(catalog, mock(ScenarioFetchGraphResolver.class),
            mock(MetadataResolver.class), mock(RlsFilterActivator.class), readGate, null,
            ReadTelemetry.noop(), () -> "test-user");
        ReflectionTestUtils.setField(executor, "entityManager", entityManager);
    }

    @Test
    void pagedSortByToManyPathIsRejectedBeforeAnyQuery() {
        metamodelReportsAttribute("children", true);

        assertThatThrownBy(() -> executor.readPage(PageRead.of(C4FixtureEntity.class,
            FetchScenario.LIST, null, PageRequest.of(0, 10, Sort.by("children")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("to-many-пути");

        verify(entityManager, never()).getCriteriaBuilder();
    }

    @Test
    void unpagedSortByToManyPathIsRejectedBeforeAnyQuery() {
        metamodelReportsAttribute("children", true);

        assertThatThrownBy(() -> executor.readAll(new ListRead<>(C4FixtureEntity.class,
            FetchScenario.LIST, null, PageRequest.of(0, 10, Sort.by("children")), List.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("to-many-пути");

        verify(entityManager, never()).getCriteriaBuilder();
    }

    @Test
    void sortByRootFieldIsNotRejected() {
        metamodelReportsAttribute("code", false);

        assertThatThrownBy(() -> executor.readAll(new ListRead<>(C4FixtureEntity.class,
            FetchScenario.LIST, null, PageRequest.of(0, 10, Sort.by("code")), List.of())))
            .hasMessageContaining("дошли до построения Criteria");
    }

    /**
     * Объявляет единственный атрибут типа: {@code PluralAttribute} для to-many-пути и
     * {@code SingularAttribute}-подобный атрибут для поля корня.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void metamodelReportsAttribute(String name, boolean plural) {
        ManagedType managed = mock(ManagedType.class);
        Attribute attribute = plural ? mock(PluralAttribute.class) : mock(Attribute.class);
        when(attribute.getName()).thenReturn(name);
        when(managed.getAttributes()).thenReturn(Set.of(attribute));
        when(metamodel.managedType(C4FixtureEntity.class)).thenReturn(managed);
    }
}
