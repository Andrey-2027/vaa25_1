package org.ipro.data;

import org.ipro.crud.IdentifiableEntity;
import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D2-hardening: потеря контура lifecycle обязана быть громкой.
 *
 * <p>Write path получает контур через {@code getIfAvailable()}, поэтому «контур исчез» и
 * «правило не сработало» были неразличимы. Страж должен ловить два разных сценария потери:</p>
 * <ol>
 * <li>handlers объявлены, registry нет — исполнять правила некому;</li>
 * <li>registry есть, publisher нет — половина контура (события и {@code afterCommit}) исчезла.</li>
 * </ol>
 *
 * <p>Частичный контекст без handlers при этом обязан проходить молча, иначе проверка сломала бы
 * slice/unit-контексты и её бы отключили.</p>
 */
class EventContourStartupCheckTest {

    @Test
    void failsLoudlyWhenHandlersExistButTheContourIsMissing() {
        assertThatThrownBy(() -> new EventContourStartupCheck(
            List.of(handler(Nomenclature.class)), provider(null), provider(null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Контур lifecycle не подключён")
            .hasMessageContaining("Nomenclature -> NomenclatureRules")
            .hasMessageContaining("platform-events");
    }

    @Test
    void failsLoudlyWhenTheRegistryExistsWithoutThePublisher() {
        assertThatThrownBy(() -> new EventContourStartupCheck(
            List.of(handler(Nomenclature.class)),
            provider(new EntityLifecycleRegistry(List.of())), provider(null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EntityEventPublisher отсутствует")
            .hasMessageContaining("afterCommit");
    }

    @Test
    void staysQuietInAPartialContextWithoutHandlers() {
        assertThatCode(() -> new EventContourStartupCheck(
            List.of(), provider(null), provider(null)))
            .doesNotThrowAnyException();
    }

    @Test
    void staysQuietWhenTheWholeContourIsPresent() {
        assertThatCode(() -> new EventContourStartupCheck(
            List.of(handler(Nomenclature.class)),
            provider(new EntityLifecycleRegistry(List.of())),
            provider(new EntityEventPublisher(event -> { }))))
            .doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static EntityLifecycle<Nomenclature> handler(Class<Nomenclature> type) {
        return new NomenclatureRules();
    }

    /** Тип-заглушка: тесту важен не домен, а форма контура. */

    private static final class NomenclatureRules implements EntityLifecycle<Nomenclature> {
        @Override
        public Class<Nomenclature> entityType() {
            return Nomenclature.class;
        }
    }

    private static final class Nomenclature implements IdentifiableEntity {
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
