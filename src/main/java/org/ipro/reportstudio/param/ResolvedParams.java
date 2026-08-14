package org.ipro.reportstudio.param;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Результат резолвинга параметров отчёта (Фаза 3): готовый к биндингу набор
 * значений + жёсткие ошибки и предупреждения.
 * <p>
 * {@code bindings} — значения по именам биндинга JPQL (для PERIOD — два имени
 * {@code nameFrom}/{@code nameTo}); сущности уже перезапрошены (свежие инстансы
 * под активным RLS). Ключи, которых нет в запросе, просто не биндятся.
 * <p>
 * {@code errors} — жёсткие прерывания: обязательный параметр не заполнен,
 * сущность не найдена/недоступна по RLS, неверная конфигурация параметра.
 * При ошибках bindings может быть неполным — выполнять отчёт нельзя.
 */
public record ResolvedParams(Map<String, Object> bindings, List<String> errors,
                             List<String> warnings) {

    public ResolvedParams {
        bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean ok() {
        return errors.isEmpty();
    }

    public static ResolvedParams ok(Map<String, Object> bindings) {
        return new ResolvedParams(bindings, List.of(), List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private final Map<String, Object> bindings = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        public Builder bind(String name, Object value) {
            bindings.put(name, value);
            return this;
        }

        public Builder error(String message) {
            errors.add(message);
            return this;
        }

        public Builder warn(String message) {
            warnings.add(message);
            return this;
        }

        public ResolvedParams build() {
            return new ResolvedParams(bindings, errors, warnings);
        }
    }
}
