package org.ipro.data;

import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Eager-проверка контура entity lifecycle events на старте (D2, hardening extraction).
 *
 * <p>Canonical write path получает контур событий через {@code ObjectProvider.getIfAvailable()}:
 * это позволяет поднимать частичные контексты (slice/unit) без persistence, но у того же
 * решения есть обратная сторона — <b>потерю контура можно не заметить</b>. Если после выноса
 * платформенного runtime в отдельный артефакт его авто-конфигурация не зарегистрируется,
 * приложение стартует, записи проходят, а lifecycle-правила и {@code afterCommit}-callbacks
 * молча не срабатывают: предметное правило перестаёт применяться без единой ошибки в логе.</p>
 *
 * <p>Проверка различает три состояния, и это принципиально:</p>
 * <ol>
 * <li><b>частичный контекст</b> (нет ни handlers, ни контура) — норма, ничего не сообщается:
 *     так поднимаются slice-тесты и ручные executor'ы;</li>
 * <li><b>handlers есть, registry нет</b> — ошибка старта: заявленные предметные правила некому
 *     исполнять (capability/RLS/метаданные при этом работают, поэтому отказ выглядел бы как
 *     «правило просто не сработало»);</li>
 * <li><b>registry есть, publisher нет</b> — ошибка старта: {@code before*}-правила применились бы,
 *     а {@code afterCommit} и события — нет, то есть половина контура исчезла незаметно.</li>
 * </ol>
 *
 * <p>Сообщение называет и число заявленных handlers, и их типы: иначе после больших переносов
 * непонятно, что именно осталось без исполнения.</p>
 */
public final class EventContourStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(EventContourStartupCheck.class);

    public EventContourStartupCheck(
            List<EntityLifecycle<?>> lifecycleHandlers,
            ObjectProvider<EntityLifecycleRegistry> lifecycleRegistry,
            ObjectProvider<EntityEventPublisher> eventPublisher) {
        Objects.requireNonNull(lifecycleHandlers, "lifecycleHandlers must not be null");
        Objects.requireNonNull(lifecycleRegistry, "lifecycleRegistry provider must not be null");
        Objects.requireNonNull(eventPublisher, "eventPublisher provider must not be null");

        EntityLifecycleRegistry registry = lifecycleRegistry.getIfAvailable();
        EntityEventPublisher publisher = eventPublisher.getIfAvailable();
        int handlerCount = lifecycleHandlers.size();

        if (handlerCount > 0 && registry == null) {
            throw new IllegalStateException(
                "Контур lifecycle не подключён, но приложение объявляет " + handlerCount
                    + " EntityLifecycle: " + handlerNames(lifecycleHandlers)
                    + ". Предметные правила не будут применяться. Проверьте, что платформенный"
                    + " runtime-модуль (org.ipro:platform-events) есть на classpath и его"
                    + " авто-конфигурация зарегистрирована в"
                    + " META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.");
        }

        if (registry != null && publisher == null) {
            throw new IllegalStateException(
                "EntityLifecycleRegistry создан, но EntityEventPublisher отсутствует:"
                    + " before*-правила будут применяться, а afterCommit-callbacks и события —"
                    + " нет. Незаметная потеря половины контура: проверьте, что авто-конфигурация"
                    + " платформенного runtime-модуля (org.ipro:platform-events) зарегистрирована.");
        }

        if (handlerCount > 0) {
            log.info("Контур lifecycle подключён: {} handler(s) {}", handlerCount,
                handlerNames(lifecycleHandlers));
        } else {
            log.debug("Контур lifecycle подключён без прикладных handlers (частичный контекст).");
        }
    }

    private static String handlerNames(List<EntityLifecycle<?>> handlers) {
        return handlers.stream()
            .map(handler -> handler.entityType().getSimpleName() + " -> "
                + handler.getClass().getSimpleName())
            .collect(Collectors.joining(", ", "[", "]"));
    }
}
