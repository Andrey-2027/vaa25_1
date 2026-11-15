package org.ipro.query.ui.grid;

import org.ipro.data.presentation.EntityPresentationService;

import java.util.Map;
import java.util.Objects;

/** Подписи значений колонок-ссылок: показывается представление, отбор идёт по идентификатору. */
public interface QueryReferenceLabels {
    /** Подпись объекта; недоступный пользователю объект получает «… недоступен». */
    String label(Class<?> type, Long id);

    /** Подписи без запросов: идентификатор как есть. Для тестов и потребителей без представлений. */
    static QueryReferenceLabels identifiers() {
        return (type, id) -> type.getSimpleName() + " #" + id;
    }

    /** Те же сервисы представления, что у списков и карточек; ответы запоминаются на время работы панели. */
    static QueryReferenceLabels over(EntityPresentationService presentations) {
        Objects.requireNonNull(presentations, "presentations");
        Map<EntityPresentationService.Reference, String> cache = new java.util.concurrent.ConcurrentHashMap<>();
        return (type, id) -> {
            var reference = new EntityPresentationService.Reference(type, id);
            String known = cache.get(reference);
            if (known != null) return known;
            String label = presentations.resolve(java.util.List.of(reference)).get(reference);
            String result = label == null ? presentations.unavailable(type) : label;
            cache.put(reference, result);
            return result;
        };
    }
}
