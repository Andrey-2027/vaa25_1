package org.ip.views.home;

import org.ipro.form.coordinator.FormOpenEvent;
import org.ipro.form.coordinator.FormOpenObserver;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * Ведёт «Недавнее»: каждое успешное открытие списка или существующей записи становится закрепляемым
 * пунктом в начале {@code home.recent.v1}. Список пишется только если это функция каталога навигации (с
 * вариантом) и открытие было без бизнес-параметров; запись — только публикуемого типа.
 */
@Component
public class HomeRecentRecorder implements FormOpenObserver {
    private static final Logger log = LoggerFactory.getLogger(HomeRecentRecorder.class);

    private final FormRouteCatalog routes;
    private final NavigationFunctionsProvider functions;
    private final HomeService home;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public HomeRecentRecorder(FormRouteCatalog routes, NavigationFunctionsProvider functions, HomeService home) {
        this(routes, functions, home, Clock.systemUTC());
    }

    HomeRecentRecorder(FormRouteCatalog routes, NavigationFunctionsProvider functions, HomeService home, Clock clock) {
        this.routes = routes;
        this.functions = functions;
        this.home = home;
        this.clock = clock;
    }

    @Override
    public void opened(FormOpenEvent event) {
        pinOf(event).ifPresent(pin -> {
            try {
                home.record(pin, clock.instant());
            } catch (RuntimeException failure) {
                // Недавнее — удобство: сбой записи не должен мешать работе.
                log.warn("Не удалось записать открытие в «Недавнее»", failure);
            }
        });
    }

    Optional<HomePin> pinOf(FormOpenEvent event) {
        Optional<String> entityKey = routes.find(event.type()).map(PublishedFormRoute::entityKey);
        if (entityKey.isEmpty()) return Optional.empty();
        if (event.kind() == FormOpenEvent.Kind.LIST) {
            if (!event.plain()) return Optional.empty();
            String key = "list:" + entityKey.get() + (event.variant() == null ? "" : ":" + event.variant());
            return functions.find(key).map(function -> HomePin.function(function.key()));
        }
        if (event.id() instanceof Number id) return Optional.of(HomePin.record(entityKey.get(), id.longValue()));
        return Optional.empty();
    }
}
