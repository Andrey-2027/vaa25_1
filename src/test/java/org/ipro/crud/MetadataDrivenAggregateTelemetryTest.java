package org.ipro.crud;

import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventSource;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.api.Telemetry;
import org.ipro.telemetry.core.TelemetryBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.lang.reflect.Field;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Telemetry parity for the metadata-driven replacement of typed save use cases. */
class MetadataDrivenAggregateTelemetryTest {

    private final ServiceLocator serviceLocator = mock(ServiceLocator.class);
    private final SectionMetadataRegistry sectionRegistry = mock(SectionMetadataRegistry.class);
    private final GenericOwnedSectionService sectionService = mock(GenericOwnedSectionService.class);
    private final EntityEventPublisher eventPublisher = mock(EntityEventPublisher.class);
    private final MetadataDrivenAggregateSaveService saveService =
        new MetadataDrivenAggregateSaveService(
            serviceLocator, sectionRegistry, sectionService, eventPublisher);
    private final Telemetry telemetry = mock(Telemetry.class);

    @BeforeEach
    void setUp() {
        TelemetryBridge.set(telemetry);
        when(eventPublisher.openAggregateOperation(any()))
            .thenReturn(mock(EntityEventPublisher.EventScope.class));
    }

    @AfterEach
    void tearDown() {
        TelemetryBridge.set(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulSaveEmitsExactlyOneBusinessScopePerCall() {
        ReceivingDocument header = new ReceivingDocument();
        ReceivingDocument saved = new ReceivingDocument();
        saved.setId(10L);
        BaseService rootService = mock(BaseService.class);
        doReturn(rootService).when(serviceLocator).findService(ReceivingDocument.class);
        when(rootService.save(header)).thenReturn(saved);
        when(telemetry.beginOperation("save:ReceivingDocument"))
            .thenReturn(mock(OperationScope.class));

        inTransaction(() -> saveService.save(header, List.of(), EventSource.SYSTEM));
        inTransaction(() -> saveService.save(header, List.of(), EventSource.SYSTEM));

        verify(telemetry, times(2)).beginOperation("save:ReceivingDocument");
    }

    @Test
    void failedSectionValidationEmitsNoBusinessScope() {
        ReceivingDocument header = new ReceivingDocument();
        ReceivingDocumentItem row = new ReceivingDocumentItem();
        TableSectionMetadataInfo descriptor = mock(TableSectionMetadataInfo.class);
        doReturn(ReceivingDocumentItem.class).when(descriptor).getRowClass();
        try {
            Field parentField = ReceivingDocumentItem.class.getDeclaredField("document");
            Field lineNumberField = ReceivingDocumentItem.class.getDeclaredField("lineNumber");
            doReturn(parentField).when(descriptor).getParentField();
            when(descriptor.hasLineNumberField()).thenReturn(true);
            doReturn(lineNumberField).when(descriptor).getLineNumberField();
        } catch (NoSuchFieldException exception) {
            throw new AssertionError("ReceivingDocumentItem section metadata changed", exception);
        }
        when(sectionRegistry.forOwner(ReceivingDocument.class)).thenReturn(List.of(descriptor));
        when(sectionService.validateRows(header, List.of(row), descriptor))
            .thenReturn(List.of("invalid row"));

        assertThatThrownBy(() -> inTransaction(() -> saveService.save(header,
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    ReceivingDocumentItem.class, List.of(row))), EventSource.SYSTEM)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("invalid row");

        verify(telemetry, never()).beginOperation(anyString());
        verify(serviceLocator, never()).findService(any());
    }

    private static <T> T inTransaction(Supplier<T> action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        int completionStatus = TransactionSynchronization.STATUS_COMMITTED;
        try {
            return action.get();
        } catch (RuntimeException | Error failure) {
            completionStatus = TransactionSynchronization.STATUS_ROLLED_BACK;
            throw failure;
        } finally {
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(completionStatus);
            }
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
