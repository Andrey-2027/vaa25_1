package org.ipro.lifecycle;

import org.ipro.events.AggregateSection;
import org.ipro.events.EntityChangedEvent;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.identity.IdentifiableEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D2 (runtime slice): поведение fail-fast реестра проверяется в модуле, который им владеет.
 *
 * <p>Тест переехал из дерева приложения и потерял зависимость от прикладных сущностей: раньше
 * он использовал {@code org.ip.model.Nomenclature} и {@code PrdSpec}, то есть проверка
 * платформенного реестра не могла существовать без приложения. Теперь типы-фикстуры объявлены
 * рядом: модуль доказывает своё поведение сам, а полный контекст приложения проверяет
 * {@code EventContourWiringIT} — уже как потребитель.</p>
 */
class EntityLifecycleRegistryTest {

    @Test
    void indexesOneHandlerByEntityTypeAndDoesNotInvokeItForAnotherType() {
        RecordingLifecycle handler = new RecordingLifecycle();
        EntityLifecycleRegistry registry = new EntityLifecycleRegistry(List.of(handler));
        FixtureDocument document = new FixtureDocument();
        EventContext context = EventContext.forEntity(
            FixtureDocument.class, null, EventSource.SYSTEM, "save:FixtureDocument");

        registry.beforeSave(FixtureDocument.class, document, context);
        registry.onSave(FixtureDocument.class, document, context);

        assertThat(registry.find(FixtureDocument.class)).contains(handler);
        assertThat(handler.beforeSaveCount).isEqualTo(1);
        assertThat(handler.onSaveCount).isEqualTo(1);
        assertThat(registry.find(FixtureRow.class)).isEmpty();
    }

    @Test
    void rejectsDuplicateAuthoritativeHandlersAtConstruction() {
        assertThatThrownBy(() -> new EntityLifecycleRegistry(List.of(
                new RecordingLifecycle(), new RecordingLifecycle())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(FixtureDocument.class.getName())
            .hasMessageContaining("More than one EntityLifecycle");
    }

    @Test
    void dispatchesBeforeUpdateAndExposesFieldDifference() {
        RecordingLifecycle handler = new RecordingLifecycle();
        EntityLifecycleRegistry registry = new EntityLifecycleRegistry(List.of(handler));
        FixtureDocument original = new FixtureDocument();
        original.setId(42L);
        original.setCode("OLD");
        FixtureDocument updated = new FixtureDocument();
        updated.setId(42L);
        updated.setCode("NEW");
        EventContext context = EventContext.forEntity(
            FixtureDocument.class, 42L, EventSource.SYSTEM, "update:FixtureDocument");

        registry.beforeUpdate(FixtureDocument.class, original, updated, context);

        assertThat(handler.beforeUpdateCount).isEqualTo(1);
        assertThat(handler.codeChanged).isTrue();
    }

    @Test
    void aggregateContextKeepsAttachedEmptySectionDistinctFromAbsentSection() {
        EventContext context = EventContext.builder(FixtureDocument.class)
            .attachSection(FixtureRow.class)
            .operationName("save:FixtureDocument")
            .build();
        AggregateSaveContext<FixtureDocument> aggregateContext = new AggregateSaveContext<>(
            new FixtureDocument(),
            List.of(AggregateSection.attached(FixtureRow.class, List.of())),
            context);

        assertThat(aggregateContext.isSectionAttached(FixtureRow.class)).isTrue();
        assertThat(aggregateContext.section(FixtureRow.class)).isEmpty();
        assertThat(aggregateContext.section(FixtureDocument.class)).isEmpty();
    }

    @Test
    void deliversAfterCommitCallbackFromChangedEvent() {
        RecordingLifecycle handler = new RecordingLifecycle();
        EntityLifecycleRegistry registry = new EntityLifecycleRegistry(List.of(handler));
        FixtureDocument document = new FixtureDocument();
        EventContext context = EventContext.forEntity(
            FixtureDocument.class, 42L, EventSource.SYSTEM, "save:FixtureDocument");

        registry.onEntityChanged(new EntityChangedEvent<>(document, context));

        assertThat(handler.afterCommitCount).isEqualTo(1);
    }

    private static final class RecordingLifecycle implements EntityLifecycle<FixtureDocument> {
        private int beforeSaveCount;
        private int onSaveCount;
        private int beforeUpdateCount;
        private int afterCommitCount;
        private boolean codeChanged;

        @Override
        public Class<FixtureDocument> entityType() {
            return FixtureDocument.class;
        }

        @Override
        public void beforeSave(EntitySaveContext<FixtureDocument> context) {
            beforeSaveCount++;
        }

        @Override
        public void onSave(EntitySaveContext<FixtureDocument> context) {
            onSaveCount++;
        }

        @Override
        public void beforeUpdate(EntityUpdateContext<FixtureDocument> context) {
            beforeUpdateCount++;
            codeChanged = context.changed(FixtureDocument::getCode);
        }

        @Override
        public void afterCommit(EntityChangedContext<FixtureDocument> context) {
            afterCommitCount++;
        }
    }

    /** Сущность-фикстура модуля: контракт — только {@link IdentifiableEntity}. */
    static class FixtureDocument implements IdentifiableEntity {
        private Long id;
        private String code;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }

    /** Строка агрегата — отдельный тип, чтобы «вложенный» и «отсутствующий» не смешивались. */
    static class FixtureRow implements IdentifiableEntity {
        private Long id;

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }
    }
}
