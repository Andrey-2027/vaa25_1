package org.ipro.crud;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.ipro.data.CatalogOwnerContext;
import org.ipro.data.CatalogOwnerViolationException;
import org.ipro.data.ownerfixture.OwnerContract;
import org.ipro.data.ownerfixture.OwnerContractor;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.SectionMetadataRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3.R: owner-контракт в aggregate-пути.
 *
 * <p>Aggregate save публикует события и вызывает before-хуки раньше сохранения корня, поэтому
 * preflight владельца обязан происходить до них, а финальная проверка — после callbacks, но до
 * записи. Иначе запрещённая операция успела бы вызвать пользовательский код.</p>
 */
class CatalogOwnerAggregateTest {

    @Test
    void missingOwnerIsRejectedBeforeAggregateEventsAndHooks() {
        EntityEventPublisher events = mock(EntityEventPublisher.class);
        EntityLifecycleRegistry lifecycle = mock(EntityLifecycleRegistry.class);
        MetadataDrivenAggregateSaveService service = service(mock(EntityManager.class), events,
            lifecycle, List.of());

        assertThatThrownBy(() -> service.save(new OwnerContract("AGG-1", null), List.of(),
                EventSource.UI))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .satisfies(error -> assertThat(((CatalogOwnerViolationException) error).reason())
                .isEqualTo(CatalogOwnerViolationException.Reason.OWNER_REQUIRED));

        verify(events, never()).openAggregateOperation(any());
        verify(events, never()).publishAggregateSaving(any(), any(), any());
        verify(lifecycle, never()).beforeAggregateSave(any(), any(), any(), any());
    }

    @Test
    void ownerTransferIsRejectedBeforeAggregateEvents() {
        EntityManager entityManager = entityManagerWithPersistedOwner(7L);
        EntityEventPublisher events = mock(EntityEventPublisher.class);
        EntityLifecycleRegistry lifecycle = mock(EntityLifecycleRegistry.class);
        MetadataDrivenAggregateSaveService service = service(entityManager, events, lifecycle,
            List.of());

        OwnerContractor other = owner(8L, "B");
        OwnerContract existing = new OwnerContract("AGG-2", other);
        existing.setId(42L);

        assertThatThrownBy(() -> service.save(existing, List.of(), EventSource.UI))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .satisfies(error -> assertThat(((CatalogOwnerViolationException) error).reason())
                .isEqualTo(CatalogOwnerViolationException.Reason.OWNER_IMMUTABLE));

        verify(events, never()).openAggregateOperation(any());
        verify(events, never()).publishAggregateSaving(any(), any(), any());
        verify(lifecycle, never()).beforeAggregateSave(any(), any(), any(), any());
    }

    @Test
    void contextOwnerMustMatchTheAggregateAssociation() {
        MetadataDrivenAggregateSaveService service = service(mock(EntityManager.class),
            mock(EntityEventPublisher.class), mock(EntityLifecycleRegistry.class), List.of());

        OwnerContractor declared = owner(7L, "A");
        OwnerContractor other = owner(8L, "B");

        assertThatThrownBy(() -> CatalogOwnerContext.call(OwnerContractor.class.getName(), 8L,
                () -> service.save(new OwnerContract("AGG-4", declared), List.of(),
                    EventSource.UI)))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .satisfies(error -> assertThat(((CatalogOwnerViolationException) error).reason())
                .isEqualTo(CatalogOwnerViolationException.Reason.CONTEXT_MISMATCH));

        assertThat(other.getId()).isEqualTo(8L);
    }

    private static MetadataDrivenAggregateSaveService service(EntityManager entityManager,
                                                              EntityEventPublisher events,
                                                              EntityLifecycleRegistry lifecycle,
                                                              List<AggregateSaveContributionParticipant> participants) {
        return new MetadataDrivenAggregateSaveService(mock(ServiceLocator.class),
            sectionRegistry(), mock(GenericOwnedSectionService.class), events, lifecycle,
            participants, null, entityManager);
    }

    private static SectionMetadataRegistry sectionRegistry() {
        SectionMetadataRegistry registry = mock(SectionMetadataRegistry.class);
        when(registry.forOwner(any())).thenReturn(List.of());
        return registry;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EntityManager entityManagerWithPersistedOwner(Long ownerId) {
        EntityManager entityManager = mock(EntityManager.class);
        Metamodel metamodel = mock(Metamodel.class);
        EntityType entityType = mock(EntityType.class);
        when(entityManager.getMetamodel()).thenReturn(metamodel);
        when(metamodel.entity(OwnerContract.class)).thenReturn(entityType);
        when(entityType.getName()).thenReturn("OwnerContract");
        TypedQuery<Object> query = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Object.class))).thenReturn(query);
        when(query.setFlushMode(any())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(ownerId));
        return entityManager;
    }

    private static OwnerContractor owner(Long id, String name) {
        OwnerContractor owner = new OwnerContractor(name);
        owner.setId(id);
        return owner;
    }
}
