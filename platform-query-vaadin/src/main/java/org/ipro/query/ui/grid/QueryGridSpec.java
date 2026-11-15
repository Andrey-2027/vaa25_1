package org.ipro.query.ui.grid;

import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.query.model.QueryDefinition;

import java.util.Objects;

/**
 * Нейтральное описание того, что показывает ядро просмотра. Ядро не знает, откуда оно взялось:
 * список администратора, объявление в коде и документ UTR собирают эту запись сами.
 *
 * @param launchKey       ключ запуска: идентификатор и ревизия описания; по нему запоминается
 *                        время прошлого выполнения для подсказки «не выполнялся»
 * @param hybridMaxRows   предел для {@code HYBRID}; для других режимов не используется
 * @param commandColumn   проверяемая колонка идентичности объекта команды
 * @param standardState   настройки сетки при открытии
 * @param subject         как называть объект в сообщениях об ошибке
 */
public record QueryGridSpec(String title, String launchKey, QueryDefinition query, ProjectionExecutionMode mode,
                            int hybridMaxRows, String commandColumn, QueryGridState standardState, Subject subject) {

    /** Что открыто: подпись в сообщении «… требует исправления». */
    public enum Subject {
        LIST("Список"), REPORT("Отчёт");

        private final String noun;

        Subject(String noun) {
            this.noun = noun;
        }

        public String noun() {
            return noun;
        }
    }

    public QueryGridSpec {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(launchKey, "launchKey");
        Objects.requireNonNull(query, "query");
        mode = mode == null ? ProjectionExecutionMode.LAZY : mode;
        if (mode == ProjectionExecutionMode.HYBRID && hybridMaxRows < 1) {
            hybridMaxRows = query.limits().maxRows();
        }
        standardState = standardState == null ? QueryGridState.EMPTY : standardState;
        subject = subject == null ? Subject.LIST : subject;
    }

    /** Режим «Постранично», без стандартного вида и колонки объекта. */
    public static QueryGridSpec of(String title, String launchKey, QueryDefinition query) {
        return new QueryGridSpec(title, launchKey, query, ProjectionExecutionMode.LAZY, 0, null, null, null);
    }

    public QueryGridSpec withMode(ProjectionExecutionMode value) {
        return new QueryGridSpec(title, launchKey, query, value, hybridMaxRows, commandColumn, standardState, subject);
    }

    public QueryGridSpec withStandardState(QueryGridState value) {
        return new QueryGridSpec(title, launchKey, query, mode, hybridMaxRows, commandColumn, value, subject);
    }

    public QueryGridSpec withCommandColumn(String value) {
        return new QueryGridSpec(title, launchKey, query, mode, hybridMaxRows, value, standardState, subject);
    }

    public QueryGridSpec withSubject(Subject value) {
        return new QueryGridSpec(title, launchKey, query, mode, hybridMaxRows, commandColumn, standardState, value);
    }
}
