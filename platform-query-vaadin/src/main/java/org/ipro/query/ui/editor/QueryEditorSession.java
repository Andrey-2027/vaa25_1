package org.ipro.query.ui.editor;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Сессия редактирования запроса: хранит подтверждённый черновик и текущую
 * правку. «Применить» делает правку новой базой, «отменить» возвращает
 * последнюю подтверждённую версию. Никакой отчётный шаблон не участвует.
 */
public final class QueryEditorSession {

    private QueryDraft baseline;
    private QueryDraft draft;
    private Consumer<QueryDraft> listener = ignored -> { };

    public QueryEditorSession(QueryDraft baseline) {
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        this.draft = baseline;
    }

    public static QueryEditorSession of(QueryDraft baseline) {
        return new QueryEditorSession(baseline);
    }

    public QueryDraft baseline() {
        return baseline;
    }

    public QueryDraft draft() {
        return draft;
    }

    public void update(QueryDraft next) {
        QueryDraft normalized = Objects.requireNonNull(next, "next");
        if (normalized.equals(draft)) {
            return;
        }
        draft = normalized;
        listener.accept(draft);
    }

    /** Подтверждает текущую правку; отмена после этого вернёт уже её. */
    public void apply() {
        baseline = draft;
    }

    /** Возвращает последнюю подтверждённую версию. */
    public void cancel() {
        QueryDraft previous = draft;
        draft = baseline;
        if (!previous.equals(draft)) {
            listener.accept(draft);
        }
    }

    public boolean dirty() {
        return !draft.equals(baseline);
    }

    public Optional<QueryDraft> unapplied() {
        return dirty() ? Optional.of(draft) : Optional.empty();
    }

    public void onDraftChanged(Consumer<QueryDraft> listener) {
        this.listener = listener == null ? ignored -> { } : listener;
    }
}
