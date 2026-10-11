package org.ipro.lifecycle;

import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.events.config.EventsAutoConfiguration;
import org.ipro.identity.IdentifiableEntity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Spring transaction synchronization and event wiring, without a database. */
class LifecycleTransactionTest {
    @Test
    void stringIdentityPassesEveryHookAndAfterCommitRunsAfterTransactionCommit() {
        RecordingLifecycle handler = new RecordingLifecycle();
        runner(handler).run(context -> {
            var registry = context.getBean(EntityLifecycleRegistry.class);
            var publisher = context.getBean(EntityEventPublisher.class);
            var transaction = new TransactionTemplate(new RecordingTransactionManager(handler.calls));
            TextEntity entity = new TextEntity("text-key");
            EventContext event = event(entity);
            transaction.executeWithoutResult(status -> {
                registry.beforeSave(TextEntity.class, entity, event);
                registry.beforeUpdate(TextEntity.class, new TextEntity(entity.getId()), entity, event);
                registry.beforeAggregateSave(TextEntity.class, entity, List.of(), event);
                registry.beforeDelete(TextEntity.class, entity, event);
                registry.onSave(TextEntity.class, entity, event);
                publisher.publishChanged(entity, event);
                assertThat(handler.afterCommit).isNull();
            });
            assertThat(handler.calls).containsExactly("beforeSave", "beforeUpdate", "beforeAggregateSave",
                    "beforeDelete", "onSave", "commit", "afterCommit");
            assertThat(handler.afterCommit.entity()).isSameAs(entity);
            assertThat(handler.afterCommit.entityId()).isSameAs(entity.getId());
            assertThat(handler.afterCommit.eventContext()).isSameAs(event);
            assertThat(registry.find(TextEntity.class)).contains(handler);
        });
    }

    @Test
    void rollbackDiscardsScheduledAfterCommitCallback() {
        RecordingLifecycle handler = new RecordingLifecycle();
        runner(handler).run(context -> {
            var registry = context.getBean(EntityLifecycleRegistry.class);
            var publisher = context.getBean(EntityEventPublisher.class);
            var transaction = new TransactionTemplate(new RecordingTransactionManager(handler.calls));
            TextEntity entity = new TextEntity("rollback-key");
            transaction.executeWithoutResult(status -> {
                registry.onSave(TextEntity.class, entity, event(entity));
                publisher.publishChanged(entity, event(entity));
                status.setRollbackOnly();
            });
            assertThat(handler.calls).containsExactly("onSave", "rollback");
            assertThat(handler.afterCommit).isNull();
        });
    }

    @Test
    void synchronousVetoRollsBackAndDiscardsAlreadyScheduledChangedEvent() {
        RecordingLifecycle handler = new RecordingLifecycle();
        handler.veto = new IllegalStateException("blocked by lifecycle");
        runner(handler).run(context -> {
            var registry = context.getBean(EntityLifecycleRegistry.class);
            var publisher = context.getBean(EntityEventPublisher.class);
            var transaction = new TransactionTemplate(new RecordingTransactionManager(handler.calls));
            TextEntity entity = new TextEntity("veto-key");
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                publisher.publishChanged(entity, event(entity));
                registry.beforeSave(TextEntity.class, entity, event(entity));
                registry.onSave(TextEntity.class, entity, event(entity));
            })).isSameAs(handler.veto);
            assertThat(handler.calls).containsExactly("beforeSave", "rollback");
            assertThat(handler.afterCommit).isNull();
        });
    }

    private static ApplicationContextRunner runner(RecordingLifecycle handler) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EventsAutoConfiguration.class))
                .withBean(RecordingLifecycle.class, () -> handler);
    }

    private static EventContext event(TextEntity entity) {
        return EventContext.builder(TextEntity.class).aggregateId(entity.getId())
                .source(EventSource.UI).formVariant("full").operationName("save:TextEntity").build();
    }

    static final class RecordingLifecycle implements EntityLifecycle<TextEntity> {
        final List<String> calls = new ArrayList<>();
        IllegalStateException veto;
        EntityChangedContext<TextEntity> afterCommit;

        @Override public Class<TextEntity> entityType() { return TextEntity.class; }
        @Override public void beforeSave(EntitySaveContext<TextEntity> context) {
            inTransaction("beforeSave");
            if (veto != null) throw veto;
        }
        @Override public void beforeUpdate(EntityUpdateContext<TextEntity> context) {
            inTransaction("beforeUpdate");
        }
        @Override public void beforeAggregateSave(AggregateSaveContext<TextEntity> context) {
            inTransaction("beforeAggregateSave");
        }
        @Override public void beforeDelete(EntityDeleteContext<TextEntity> context) {
            inTransaction("beforeDelete");
        }
        @Override public void onSave(EntitySaveContext<TextEntity> context) { inTransaction("onSave"); }
        @Override public void afterCommit(EntityChangedContext<TextEntity> context) {
            calls.add("afterCommit");
            afterCommit = context;
        }
        private void inTransaction(String name) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            calls.add(name);
        }
    }

    static final class TextEntity implements IdentifiableEntity<String> {
        private String id;
        TextEntity(String id) { this.id = id; }
        @Override public String getId() { return id; }
        @Override public void setId(String id) { this.id = id; }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final List<String> calls;
        private RecordingTransactionManager(List<String> calls) { this.calls = calls; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { calls.add("commit"); }
        @Override protected void doRollback(DefaultTransactionStatus status) { calls.add("rollback"); }
    }
}
