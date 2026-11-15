package org.ipro.query.ui.grid;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Время прошлого выполнения для подсказки «Не выполнялся» (UX-3). Хранится в памяти процесса по
 * ключу запуска: после перезапуска или на другом узле подсказки просто нет. Таблицы в БД нет.
 */
public final class QueryRunHistory {
    private static final int CAPACITY = 500;

    private final Map<String, Duration> durations = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Duration> eldest) {
            return size() > CAPACITY;
        }
    };

    public synchronized void record(String launchKey, Duration duration) {
        durations.put(launchKey, duration);
    }

    public synchronized Optional<Duration> lastDuration(String launchKey) {
        return Optional.ofNullable(durations.get(launchKey));
    }
}
