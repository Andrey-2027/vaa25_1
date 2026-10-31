package org.ipro.query.ui.editor;

import org.ipro.query.*;
import org.ipro.query.param.*;
import org.ipro.query.register.*;
import org.ipro.query.sqm.*;

import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QueryParameterKind;
import org.ipro.query.model.QuerySource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryDraftTest {

    @Test
    void withParameterReplacesByNameAndKeepsOrder() {
        var first = new QueryParameter("first");
        var second = new QueryParameter("second");
        var draft = new QueryDraft("select 1", QuerySource.MANUAL, null, null, List.of(first, second));

        var updated = draft.withParameter(second.withRequired(true));

        assertThat(updated.parameters()).containsExactly(first, second.withRequired(true));
        assertThat(draft.parameters()).containsExactly(first, second);
        assertThat(updated.nextParameterPosition()).isEqualTo(1);
    }

    @Test
    void newParameterGoesToTheEndAndRemovalKeepsTheRest() {
        var first = new QueryParameter("first");
        var draft = new QueryDraft("select 1", QuerySource.MANUAL, null, null, List.of(first));

        var added = draft.withParameter(new QueryParameter("period").withKind(QueryParameterKind.RANGE));
        var removed = added.withoutParameter("first");

        assertThat(added.parameters()).extracting(QueryParameter::name).containsExactly("first", "period");
        assertThat(removed.parameters()).extracting(QueryParameter::name).containsExactly("period");
        assertThat(draft.parameter("first")).contains(first);
    }

    @Test
    void visualDraftRequiresVisualJson() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new QueryDraft("select 1", QuerySource.VISUAL, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visualQueryJson");
    }
}
