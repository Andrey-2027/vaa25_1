package org.ipro.crud;

import org.ipro.data.DataOperation;
import org.ipro.data.fixture.C4FixtureEntity;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C5.6, DoD: security preflight агрегата выполняется до aggregate-событий, before-хуков и до
 * захвата rollback-состояния — запрещённое сохранение не публикует Saving и не вызывает
 * пользовательский код (ADR-0017 §5).
 */
class MetadataDrivenAggregatePreflightTest {

    @Test
    void aggregateOperationIsAuthorizedBeforeAggregateEventsAndHooks() {
        ServiceLocator serviceLocator = mock(ServiceLocator.class);
        SectionMetadataRegistry sectionRegistry = mock(SectionMetadataRegistry.class);
        when(sectionRegistry.forOwner(C4FixtureEntity.class)).thenReturn(List.of());
        GenericOwnedSectionService sectionService = mock(GenericOwnedSectionService.class);
        EntityEventPublisher events = mock(EntityEventPublisher.class);
        EntityLifecycleRegistry lifecycle = mock(EntityLifecycleRegistry.class);
        AggregateOperationAuthorizer authorizer = mock(AggregateOperationAuthorizer.class);
        doThrow(new C5AccessDeniedException("Нет прав на операцию с объектом"))
            .when(authorizer).requireOperation(C4FixtureEntity.class, DataOperation.CREATE);
        MetadataDrivenAggregateSaveService service = new MetadataDrivenAggregateSaveService(
            serviceLocator, sectionRegistry, sectionService, events, lifecycle, List.of(), authorizer);

        assertThatThrownBy(() -> service.save(new C4FixtureEntity("AGG-C5-1", "новый"),
            List.of(), EventSource.UI))
            .isInstanceOf(C5AccessDeniedException.class);

        verify(authorizer).requireOperation(C4FixtureEntity.class, DataOperation.CREATE);
        verify(events, never()).openAggregateOperation(any());
        verify(events, never()).publishAggregateSaving(any(), any(), any());
        verify(lifecycle, never()).beforeAggregateSave(any(), any(), any(), any());
        verify(sectionService, never()).replaceAll(any(), any(), any());
        verify(serviceLocator, never()).findService(any());
    }

    @Test
    void updateIntentIsAuthorizedAsUpdateNotCreate() {
        ServiceLocator serviceLocator = mock(ServiceLocator.class);
        SectionMetadataRegistry sectionRegistry = mock(SectionMetadataRegistry.class);
        when(sectionRegistry.forOwner(C4FixtureEntity.class)).thenReturn(List.of());
        AggregateOperationAuthorizer authorizer = mock(AggregateOperationAuthorizer.class);
        doThrow(new C5AccessDeniedException("Нет прав на изменение"))
            .when(authorizer).requireOperation(C4FixtureEntity.class, DataOperation.UPDATE);
        MetadataDrivenAggregateSaveService service = new MetadataDrivenAggregateSaveService(
            serviceLocator, sectionRegistry, mock(GenericOwnedSectionService.class),
            mock(EntityEventPublisher.class), mock(EntityLifecycleRegistry.class), List.of(),
            authorizer);

        C4FixtureEntity existing = new C4FixtureEntity("AGG-C5-2", "существующий");
        existing.setId(10L);

        assertThatThrownBy(() -> service.save(existing, List.of(), EventSource.UI))
            .isInstanceOf(C5AccessDeniedException.class);

        verify(authorizer).requireOperation(C4FixtureEntity.class, DataOperation.UPDATE);
    }
}
