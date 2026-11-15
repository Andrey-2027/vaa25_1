package org.ip.views.home;

import org.ip.navigation.ApplicationNavigationCommands;
import org.ipro.data.presentation.EntityPresentationService;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.ipro.metadata.MetadataResolver;
import org.ipro.navigation.NavigationFunction;
import org.ipro.navigation.NavigationFunctionsProvider;
import org.ipro.navigation.NavigationImportance;
import org.ipro.navigation.NavigationMenuReader;
import org.ipro.navigation.NavigationStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сохранённое → показываемое для текущего пользователя. Функции проверяются в памяти (каталог функций
 * уже построен, решение о доступе — то же, что у меню). Записи читаются одним пакетным чтением на тип
 * ({@code EntityPresentationService.resolveReadable}): подпись и доступ — одним запросом, недоступную
 * запись не показываем вовсе.
 */
@Component
public class HomeResolver {
    private static final Logger log = LoggerFactory.getLogger(HomeResolver.class);

    /** Закреплённое, которое пользователь может открыть. */
    /**
     * @param typeTitle колонка «Тип»; @param caption колонка «Представление»;
     * @param title заголовок пункта по умолчанию: название функции либо подпись записи
     */
    public record Resolved(HomePin pin, NavigationFunction function, Class<?> type, Long id,
                           String typeTitle, String caption, String title) {
        public boolean isRecord() { return pin.isRecord(); }
        public boolean isCommand() { return function != null && function.command(); }
    }

    /** Показываемый пункт: пункт раскладки и то, во что он разрешился. */
    public record ShownItem(HomeLayout.Item item, Resolved resolved) {
        public String title() { return item.title() != null ? item.title() : resolved.title(); }
    }

    public record ShownGroup(HomeLayout.Group group, List<ShownItem> items) {}

    /** Страница: показываемые группы и пункты, к которым нет доступа (они хранятся и могут вернуться). */
    public record Page(List<ShownGroup> groups, List<HomeLayout.Item> hidden) {}

    private final NavigationFunctionsProvider functions;
    private final ApplicationNavigationCommands commands;
    private final FormRouteCatalog routes;
    private final EntityPresentationService presentations;
    private final MetadataResolver metadata;
    private final NavigationMenuReader menus;

    public HomeResolver(NavigationFunctionsProvider functions, ApplicationNavigationCommands commands,
                        FormRouteCatalog routes, EntityPresentationService presentations, MetadataResolver metadata,
                        NavigationMenuReader menus) {
        this.menus = menus;
        this.functions = functions;
        this.commands = commands;
        this.routes = routes;
        this.presentations = presentations;
        this.metadata = metadata;
    }

    public Page resolve(HomeLayout layout) {
        var pins = new LinkedHashSet<HomePin>();
        layout.groups().forEach(group -> group.items().forEach(item -> pins.add(item.pin())));
        Map<HomePin, Resolved> resolved = resolve(pins);
        var groups = new ArrayList<ShownGroup>();
        var hidden = new ArrayList<HomeLayout.Item>();
        for (var group : layout.groups()) {
            var shown = new ArrayList<ShownItem>();
            for (var item : group.items()) {
                var found = resolved.get(item.pin());
                if (found == null) hidden.add(item);
                else shown.add(new ShownItem(item, found));
            }
            // Пустая группа показывается (её создал пользователь); группа, где всё скрыто, — нет.
            if (group.items().isEmpty() || !shown.isEmpty()) groups.add(new ShownGroup(group, shown));
        }
        return new Page(groups, hidden);
    }

    /** Строка «Недавнего»: что открыто и когда. */
    public record RecentRow(Resolved resolved, Instant at) {}

    /** «Недавнее» для показа: недоступные записи и функции отброшены, порядок — как в хранилище (свежие первыми). */
    public List<RecentRow> recent(List<HomeJson.Recent> recent) {
        var pins = recent.stream().map(HomeJson.Recent::pin).toList();
        var resolved = resolve(pins);
        var rows = new ArrayList<RecentRow>();
        for (var entry : recent) {
            var found = resolved.get(entry.pin());
            if (found != null) rows.add(new RecentRow(found, entry.at()));
        }
        return rows;
    }

    /**
     * «Важное из вашего меню» для пустой страницы: пункты с важностью «Важное» из меню пользователя в порядке
     * меню, доступные ему. Не сохраняется.
     */
    public List<Resolved> suggestions(int limit) {
        NavigationStructure structure = menus.currentMenu();
        var order = new java.util.HashMap<String, Integer>();
        var ordered = new ArrayList<NavigationStructure.Section>(structure.sections());
        ordered.sort(Comparator.comparingInt(NavigationStructure.Section::position).thenComparing(NavigationStructure.Section::id));
        for (int i = 0; i < ordered.size(); i++) order.put(ordered.get(i).id(), i);
        var important = structure.entries().stream()
            .filter(entry -> entry.importance() == NavigationImportance.IMPORTANT)
            .sorted(Comparator.comparingInt((NavigationStructure.Entry entry) -> order.getOrDefault(entry.sectionId(), Integer.MAX_VALUE))
                .thenComparingInt(NavigationStructure.Entry::position))
            .map(entry -> HomePin.function(entry.functionKey()))
            .distinct().toList();
        return resolve(important).values().stream().limit(limit).toList();
    }

    /** Только те закрепления, которые пользователь может открыть, в порядке входа. */
    public Map<HomePin, Resolved> resolve(Collection<HomePin> pins) {
        var result = new LinkedHashMap<HomePin, Resolved>();
        var records = new LinkedHashMap<HomePin, Class<?>>();
        for (HomePin pin : pins) {
            if (pin.isFunction()) {
                function(pin).ifPresent(resolved -> result.put(pin, resolved));
            } else {
                routes.find(pin.key()).map(PublishedFormRoute::entityClass).ifPresent(type -> records.put(pin, type));
            }
        }
        if (!records.isEmpty()) {
            var references = new LinkedHashMap<HomePin, EntityPresentationService.Reference>();
            records.forEach((pin, type) -> references.put(pin, new EntityPresentationService.Reference(type, pin.id())));
            Map<EntityPresentationService.Reference, String> labels = readable(references.values());
            references.forEach((pin, reference) -> {
                String label = labels.get(reference);
                if (label != null) {
                    result.put(pin, new Resolved(pin, null, reference.type(), pin.id(),
                        metadata.resolve(reference.type()).getItemFormTitle(), label, label));
                }
            });
        }
        // Порядок входа сохраняем и для смешанного набора.
        var ordered = new LinkedHashMap<HomePin, Resolved>();
        for (HomePin pin : pins) {
            var found = result.get(pin);
            if (found != null) ordered.put(pin, found);
        }
        return ordered;
    }

    private Map<EntityPresentationService.Reference, String> readable(
            Collection<EntityPresentationService.Reference> references) {
        try {
            return presentations.resolveReadable(references);
        } catch (RuntimeException failure) {
            // Чтение не удалось: ни подпись, ни доступ неизвестны — записи не показываются.
            log.warn("Не удалось прочитать закреплённые записи «Главного»", failure);
            return Map.of();
        }
    }

    private Optional<Resolved> function(HomePin pin) {
        var function = functions.find(pin.key());
        if (function.isEmpty() || !commands.decide(function.get()).actionable()) return Optional.empty();
        var found = function.get();
        return Optional.of(switch (found.kind()) {
            case LIST -> new Resolved(pin, found, found.entityClass(), null,
                metadata.resolve(found.entityClass()).getListFormTitle(), "Список", found.title());
            case LIST_VARIANT -> new Resolved(pin, found, found.entityClass(), null,
                metadata.resolve(found.entityClass()).getListFormTitle(),
                "Список — " + found.variant(), found.title());
            case CREATE, ACTION -> new Resolved(pin, found, found.entityClass(), null, found.title(), "Команда", found.title());
            case QUERY_LIST -> new Resolved(pin, found, null, null, found.title(), "Список по запросу", found.title());
        });
    }

}
