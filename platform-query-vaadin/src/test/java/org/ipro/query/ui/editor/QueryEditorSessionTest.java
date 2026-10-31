package org.ipro.query.ui.editor;

import org.ipro.query.*;
import org.ipro.query.param.*;
import org.ipro.query.register.*;
import org.ipro.query.sqm.*;

import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QuerySource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEditorSessionTest {

    private static QueryDraft draft(String jpql, String parameter) {
        return new QueryDraft(jpql, QuerySource.MANUAL, null, null, List.of(new QueryParameter(parameter)));
    }

    @Test
    void cancelRestoresLastAppliedDraftAndApplyCommitsIt() {
        var first = draft("select a.code from Nomenclature a", "first");
        var session = new QueryEditorSession(first);
        assertThat(session.dirty()).isFalse();

        var second = draft("select a.code from Nomenclature a where a.code = :second", "second");
        session.update(second);
        assertThat(session.dirty()).isTrue();
        assertThat(session.unapplied()).contains(second);

        session.cancel();
        assertThat(session.draft()).isEqualTo(first);
        assertThat(session.dirty()).isFalse();

        session.update(second);
        session.apply();
        assertThat(session.dirty()).isFalse();
        assertThat(session.baseline()).isEqualTo(second);

        session.cancel();
        assertThat(session.draft()).isEqualTo(second);
    }

    @Test
    void listenerSeesDraftAndRollback() {
        var first = draft("select 1", "first");
        var session = new QueryEditorSession(first);
        var seen = new ArrayList<QueryDraft>();
        session.onDraftChanged(seen::add);

        var second = draft("select 2", "second");
        session.update(second);
        session.cancel();

        assertThat(seen).containsExactly(second, first);
    }

    @Test
    void visualDraftStillRequiresVisualJson() {
        assertThatThrownBy(() -> new QueryDraft("select 1", QuerySource.VISUAL, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visualQueryJson");
    }
}
