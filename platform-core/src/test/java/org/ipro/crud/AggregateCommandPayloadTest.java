package org.ipro.crud;

import org.ipro.events.AggregateSection;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.EntitySaveContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * ADR-0026: объявление payload'а команды — одно свидетельство на операцию, принадлежащее потоку и
 * владельцу, с платформенным связыванием managed copy.
 */
class AggregateCommandPayloadTest {

    private static final String DOCUMENT = "Документ";
    private static final String POSITION = "Позиция";

    @Test
    void payloadIsInvisibleWithoutADeclaration() {
        Fixture fixture = new Fixture();

        assertThat(AggregateCommandPayload.of(fixture.context(fixture.document))).isEmpty();
    }

    @Test
    void declarationIsVisibleOnlyForItsOwner() {
        Fixture fixture = new Fixture();
        IdentifiableEntity<?> other = fixture.entity(DOCUMENT);

        try (var ignored = fixture.declare(fixture.document)) {
            AggregateCommandPayload payload =
                AggregateCommandPayload.of(fixture.context(fixture.document)).orElseThrow();

            assertThat(payload.sections()).singleElement()
                .satisfies(section -> assertThat(section.rowType()).isEqualTo(fixture.positionType));
            assertThat(payload.hasSection(fixture.positionType)).isTrue();
            assertThat(payload.section(fixture.positionType)).singleElement()
                .isSameAs(fixture.position);
            assertThat(payload.section(String.class)).isEmpty();
            // Тот же тип, другой экземпляр: payload принадлежит владельцу, а не операции целиком,
            // поэтому хуку строки он не виден.
            assertThat(AggregateCommandPayload.of(fixture.context(other))).isEmpty();
        }

        assertThat(AggregateCommandPayload.of(fixture.context(fixture.document))).isEmpty();
    }

    @Test
    void nestedDeclarationsDoNotCancelEachOtherAndCloseInOrder() {
        Fixture outer = new Fixture();
        Fixture inner = new Fixture();

        try (var outerDeclaration = outer.declare(outer.document)) {
            try (var innerDeclaration = inner.declare(inner.document)) {
                assertThat(AggregateCommandPayload.of(inner.context(inner.document)))
                    .get().extracting(payload -> payload.section(inner.positionType))
                    .isEqualTo(List.of(inner.position));
                // Вложенная операция не отменяет внешнюю: её собственные хуки продолжают видеть
                // свой payload. Чужая сущность не описана ни одним объявлением и недостижима.
                assertThat(AggregateCommandPayload.of(outer.context(outer.document)))
                    .get().extracting(payload -> payload.section(outer.positionType))
                    .isEqualTo(List.of(outer.position));
                assertThat(AggregateCommandPayload.of(outer.context(outer.entity(DOCUMENT)))).isEmpty();

                assertThatThrownBy(outerDeclaration::close).hasMessageContaining("close in order");
            }

            assertThat(AggregateCommandPayload.of(outer.context(outer.document))).isPresent();
            assertThat(AggregateCommandPayload.of(inner.context(inner.document))).isEmpty();
        }

        assertThat(AggregateCommandPayload.of(outer.context(outer.document))).isEmpty();
    }

    @Test
    void platformBindsTheManagedCopyAndOnlyForTheDeclaredOwner() {
        Fixture fixture = new Fixture();
        IdentifiableEntity<?> managedCopy = fixture.entity(DOCUMENT);
        IdentifiableEntity<?> copyOfAnotherOperation = fixture.entity(DOCUMENT);

        try (var ignored = fixture.declare(fixture.document)) {
            AggregateCommandPayload.bindManagedCopy(fixture.document, managedCopy);
            AggregateCommandPayload.bindManagedCopy(fixture.entity(DOCUMENT), copyOfAnotherOperation);

            // Обновление возвращает merge-копию; её видит onSave. Связал её платформенный шаг, а не
            // приложение, поэтому сравнивать id и Hibernate-класс не требуется.
            assertThat(AggregateCommandPayload.of(fixture.context(managedCopy))).isPresent();
            // Чужое объявление не принимает копию: владелец не тот экземпляр, который сохраняла
            // платформа.
            assertThat(AggregateCommandPayload.of(fixture.context(copyOfAnotherOperation))).isEmpty();
            // Экземпляр-владелец продолжает читаться: свидетельство принадлежит объявлению, а не
            // отдельному callback'у — это данные, а не право записи.
            assertThat(AggregateCommandPayload.of(fixture.context(fixture.document))).isPresent();
        }
    }

    @Test
    void payloadDoesNotSurviveItsThread() {
        Fixture fixture = new Fixture();

        try (var ignored = fixture.declare(fixture.document)) {
            boolean visibleElsewhere = CompletableFuture.supplyAsync(() ->
                AggregateCommandPayload.of(fixture.context(fixture.document)).isPresent()).join();

            assertThat(visibleElsewhere)
                .as("свидетельство принадлежит потоку, который открыл объявление")
                .isFalse();
        }
    }

    @Test
    void payloadIsReadOnlyForItsReaders() {
        Fixture fixture = new Fixture();

        try (var ignored = fixture.declare(fixture.document)) {
            AggregateCommandPayload payload =
                AggregateCommandPayload.of(fixture.context(fixture.document)).orElseThrow();

            assertThatThrownBy(() -> payload.sections().clear())
                .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> payload.section(fixture.positionType).clear())
                .isInstanceOf(UnsupportedOperationException.class);
            // Копия читателя изменяема — неизменяемость идёт от объявления, а не от типа списка.
            List<Object> rows = new ArrayList<>(payload.sections().getFirst().rows());
            assertThat(rows).containsExactly(fixture.position);
            rows.clear();
            assertThat(rows).isEmpty();
            assertThat(payload.sections().getFirst().rows()).hasSize(1);
        }
    }

    @Test
    void declaredRowTypeMismatchFailsLoudlyInsteadOfNarrowingSilently() {
        Fixture fixture = new Fixture();
        Object wrongRow = new Object();

        try (var ignored = AggregateCommandPayload.open(fixture.document,
                List.of(new AggregateSection(fixture.positionType, List.of(wrongRow))))) {
            AggregateCommandPayload payload =
                AggregateCommandPayload.of(fixture.context(fixture.document)).orElseThrow();

            assertThatThrownBy(() -> payload.section(fixture.positionType))
                .isInstanceOf(ClassCastException.class);
        }
    }

    @Test
    void failureDoesNotLeaveTheDeclarationOnTheThread() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> {
            try (var ignored = fixture.declare(fixture.document)) {
                throw new IllegalStateException("operation failed");
            }
        }).hasMessage("operation failed");

        assertThat(AggregateCommandPayload.of(fixture.context(fixture.document))).isEmpty();
    }

    /** Фикстура: владелец объявления, его позиция и контексты хуков. */
    private static final class Fixture {

        private final Class<Position> positionType = Position.class;
        private final IdentifiableEntity<?> document = entity(DOCUMENT);
        private final Position position = new Position();

        private AggregateCommandPayload.Declaration declare(IdentifiableEntity<?> owner) {
            return AggregateCommandPayload.open(owner,
                List.of(AggregateSection.attached(positionType, List.of(position))));
        }

        private EntitySaveContext<?> context(IdentifiableEntity<?> entity) {
            return new EntitySaveContext<>(entity, EventContext.forEntity(entity.getClass(), 1L,
                EventSource.SYSTEM, "save:" + entity.getClass().getSimpleName()));
        }

        @SuppressWarnings("unchecked")
        private <T extends IdentifiableEntity<?>> T entity(String name) {
            return (T) mock(IdentifiableEntity.class, invocation -> {
                throw new UnsupportedOperationException("fixture entity is a marker: " + name);
            });
        }
    }

    /** Тип строки объявленной секции: важен сам тип, поведение не нужно. */
    static final class Position {
    }
}
