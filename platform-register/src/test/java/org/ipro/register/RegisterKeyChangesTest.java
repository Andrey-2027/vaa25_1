package org.ipro.register;

import jakarta.persistence.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.ipro.crud.BaseEntity;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EntityChangedEvent;
import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.FieldChangeRecord;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated H2: flush authorization, audit and callbacks, without Vaadin or a working database. */
class RegisterKeyChangesTest {
    private StandardServiceRegistry hibernate;
    private EntityManagerFactory factory;
    private EntityManager em;
    private JpaTransactionManager transactionManager;
    private ValidatorFactory validator;
    private RegisterRegistry registers;
    private RlsPolicyEnforcer policies;
    private EventSink audit;
    private RegisterKeyChanges command;
    private final List<Object> published = new ArrayList<>();
    private Long id;

    @BeforeEach void setUp() {
        hibernate = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
            .applySetting("hibernate.connection.url", "jdbc:h2:mem:key_" + UUID.randomUUID())
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting("hibernate.show_sql", "false").build();
        factory = new MetadataSources(hibernate).addAnnotatedClass(KeyRow.class)
            .addAnnotatedClass(ClosedRow.class).buildMetadata().buildSessionFactory();
        new RegisterKeyWriteGuardInstaller(factory).afterPropertiesSet();
        transactionManager = new JpaTransactionManager(factory);
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        validator = Validation.buildDefaultValidatorFactory();
        registers = mock(RegisterRegistry.class);
        when(registers.require(KeyRow.class)).thenReturn(RegisterDescriptor.describe(KeyRow.class));
        policies = mock(RlsPolicyEnforcer.class);
        audit = mock(EventSink.class);
        command = command(new EntityLifecycleRegistry(List.of()));
        id = new TransactionTemplate(transactionManager).execute(status -> {
            KeyRow row = new KeyRow(); row.code = "before"; row.amount = 23; em.persist(row); em.flush(); return row.getId();
        });
    }

    private RegisterKeyChanges command(EntityLifecycleRegistry lifecycles) {
        EntityDataAccess reads = mock(EntityDataAccess.class);
        when(reads.detail(eq(KeyRow.class), any())).thenAnswer(invocation ->
            Optional.ofNullable(em.find(KeyRow.class, invocation.getArgument(1))));
        return new RegisterKeyChanges(em, registers, mock(RegisterKeyChangePermissions.class), reads,
            policies, validator.getValidator(), lifecycles, new EntityEventPublisher(published::add),
            () -> "key-user", Optional.of(audit), transactionManager);
    }

    @AfterEach void close() {
        validator.close(); factory.close(); StandardServiceRegistryBuilder.destroy(hibernate);
    }

    @Test void auditRecordsOldAndNewKeyAndUserAfterSuccessfulChange() {
        var changed = command.change(KeyRow.class, id, 0L, Map.of("code", "after"), null);
        assertThat(changed.getId()).isEqualTo(id);
        assertThat(changed.getVersion()).isEqualTo(1L);
        var record = ArgumentCaptor.forClass(FieldChangeRecord.class);
        verify(audit).acceptFieldChangeDurable(record.capture());
        assertThat(record.getValue().entity()).isEqualTo("KeyRow");
        assertThat(record.getValue().entityId()).isEqualTo(id.toString());
        assertThat(record.getValue().userId()).isEqualTo("key-user");
        assertThat(record.getValue().payload()).contains("\"field\":\"code\"", "\"old\":\"before\"", "\"new\":\"after\"");
        assertThat(record.getValue().payload()).contains("change-register-key:KeyCommandTestRow");
        assertThat(published).filteredOn(EntityChangedEvent.class::isInstance).hasSize(1);
        assertThat(load().amount).isEqualTo(23);
        verify(policies, times(3)).requireUpdate(any(KeyRow.class));
    }

    @Test void noOpPublishesNoEventsOrAudit() {
        var row = command.change(KeyRow.class, id, 0L, Map.of("code", "before"), null);
        assertThat(row.getVersion()).isZero();
        verifyNoInteractions(audit); assertThat(published).isEmpty();
    }

    @Test void auditFailureRollsBackTheKeyAndDoesNotPublishACommittedChange() {
        doThrow(new IllegalStateException("audit-store-failed")).when(audit).acceptFieldChangeDurable(any());
        assertThatThrownBy(() -> command.change(KeyRow.class, id, 0L, Map.of("code", "after"), null))
            .hasMessageContaining("audit-store-failed");
        assertThat(load().code).isEqualTo("before");
        assertThat(load().getVersion()).isZero();
        assertThat(published).noneMatch(EntityChangedEvent.class::isInstance);
    }

    @Test void aDeniedNewStateRollsBackAndPublishesNoAudit() {
        doAnswer(invocation -> {
            KeyRow row = invocation.getArgument(0);
            if (row.code.equals("denied")) throw new org.springframework.security.access.AccessDeniedException("new-state");
            return null;
        }).when(policies).requireUpdate(any());
        assertThatThrownBy(() -> command.change(KeyRow.class, id, 0L, Map.of("code", "denied"), null))
            .hasMessageContaining("new-state");
        assertThat(load().code).isEqualTo("before"); verifyNoInteractions(audit);
    }

    @Test void callbackCannotSmuggleAResourceChangeThroughThePermit() {
        command = command(new EntityLifecycleRegistry(List.of(new EntityLifecycle<KeyRow>() {
            @Override public Class<KeyRow> entityType() { return KeyRow.class; }
            @Override public void beforeSave(EntitySaveContext<KeyRow> context) { context.entity().amount = 999; }
        })));
        assertThatThrownBy(() -> command.change(KeyRow.class, id, 0L, Map.of("code", "after"), null))
            .hasMessageContaining("REGISTER_KEY_CHANGE_REQUIRED");
        assertThat(load().code).isEqualTo("before"); assertThat(load().amount).isEqualTo(23);
        verifyNoInteractions(audit);
    }

    @Test void onSaveCannotChangeResourcesAfterTheFirstFlushEither() {
        command = command(new EntityLifecycleRegistry(List.of(new EntityLifecycle<KeyRow>() {
            @Override public Class<KeyRow> entityType() { return KeyRow.class; }
            @Override public void onSave(EntitySaveContext<KeyRow> context) { context.entity().amount = 999; }
        })));
        assertThatThrownBy(() -> command.change(KeyRow.class, id, 0L, Map.of("code", "after"), null))
            .hasMessageContaining("REGISTER_KEY_CHANGE_REQUIRED");
        assertThat(load().code).isEqualTo("before"); assertThat(load().amount).isEqualTo(23);
        assertThat(published).noneMatch(EntityChangedEvent.class::isInstance);
    }

    @Test void permitIsLimitedToTheRequestedRow() {
        Long other = new TransactionTemplate(transactionManager).execute(status -> {
            KeyRow row = new KeyRow(); row.code = "other"; row.amount = 2; em.persist(row); em.flush(); return row.getId();
        });
        command = command(new EntityLifecycleRegistry(List.of(new EntityLifecycle<KeyRow>() {
            @Override public Class<KeyRow> entityType() { return KeyRow.class; }
            @Override public void beforeSave(EntitySaveContext<KeyRow> context) { em.find(KeyRow.class, other).code = "intruder"; }
        })));
        assertThatThrownBy(() -> command.change(KeyRow.class, id, 0L, Map.of("code", "after"), null))
            .hasMessageContaining("REGISTER_KEY_CHANGE_REQUIRED");
        assertThat(load().code).isEqualTo("before");
    }

    @Test void callbacksCannotChangeTheRequestedKey() {
        command = command(new EntityLifecycleRegistry(List.of(new EntityLifecycle<KeyRow>() {
            @Override public Class<KeyRow> entityType() { return KeyRow.class; }
            @Override public void beforeSave(EntitySaveContext<KeyRow> context) { context.entity().code = "intruder"; }
        })));
        assertThatThrownBy(() -> command.change(KeyRow.class, id, 0L, Map.of("code", "after"), null))
            .hasMessageContaining("Обработчик изменил");
        assertThat(load().code).isEqualTo("before"); verifyNoInteractions(audit);
    }

    @Test void sameKeyOfAnotherTypeDoesNotGiveItCapability() {
        var descriptor = RegisterDescriptor.describe(ClosedRow.class);
        assertThat(descriptor.keyChangeAllowed()).isFalse();
    }

    private KeyRow load() {
        return new TransactionTemplate(transactionManager).execute(status -> em.find(KeyRow.class, id));
    }

    @Entity(name = "KeyCommandTestRow") @Table(name = "key_command_test_row",
        uniqueConstraints = @UniqueConstraint(columnNames = "business_code"))
    @RegisterMetadata(dimensions = "code", resources = "amount", keyChangeAllowed = true,
        displayName = "Проверочный регистр команды ключа")
    static class KeyRow extends BaseEntity {
        @Column(name = "business_code", nullable = false) String code;
        int amount;
    }

    @Entity(name = "KeyCommandClosedRow") @Table(name = "key_command_closed_row",
        uniqueConstraints = @UniqueConstraint(columnNames = "business_code"))
    @RegisterMetadata(dimensions = "code", resources = "amount")
    static class ClosedRow extends BaseEntity {
        @Column(name = "business_code", nullable = false, updatable = false) String code;
        int amount;
    }
}
