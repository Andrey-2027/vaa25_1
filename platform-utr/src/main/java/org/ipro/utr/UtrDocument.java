package org.ipro.utr;

import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.metadata.GridViewState;
import org.ipro.query.model.QueryDefinition;

import java.util.List;
import java.util.Objects;

/** Переносимые настройки отчёта. Значения конкретного запуска принадлежат сеансу. */
public record UtrDocument(String title, QueryDefinition query, GridViewState layout,
                          ProjectionExecutionMode mode) {

    public UtrDocument {
        if (title == null || title.isBlank() || title.strip().length() > 200) {
            throw new IllegalArgumentException("Название отчёта должно содержать от 1 до 200 символов");
        }
        title = title.strip();
        Objects.requireNonNull(query, "query");
        layout = layout == null ? new GridViewState(List.of(), List.of()) : layout;
        if (layout.fixedFilter() != null || !layout.filters().isEmpty()) {
            throw new IllegalArgumentException("Макет UTR сохраняет пользовательский отбор в userFilter");
        }
        mode = mode == null ? ProjectionExecutionMode.LAZY : mode;
    }

    UtrDocument withTitle(String value) {
        return new UtrDocument(value, query, layout, mode);
    }

    UtrDocument withLayout(GridViewState value) {
        return new UtrDocument(title, query, value, mode);
    }
}
