package org.ipro.reportstudio.query;

import org.ipro.reportstudio.data.QueryField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconcile QueryField-set ↔ layout (Фаза 2): added/removed/changedTypes/unknown.
 * Чистая функция — без Spring/БД.
 */
class QueryFieldReconcilerTest {

    private static final QueryField CODE = field("code", String.class);
    private static final QueryField NAME = field("name", String.class);
    private static final QueryField CNT = field("cnt", Long.class);
    private static final QueryField QTY = field("qty", java.math.BigDecimal.class);

    @Test
    void identicalSetsHaveNoChanges() {
        ReconcileResult result = QueryFieldReconciler.reconcile(
            List.of(CODE, NAME), List.of(CODE, NAME), List.of("code", "name"));
        assertThat(result.hasChanges()).isFalse();
        assertThat(result.added()).isEmpty();
        assertThat(result.removed()).isEmpty();
        assertThat(result.changedTypes()).isEmpty();
        assertThat(result.unknown()).isEmpty();
    }

    @Test
    void newFieldsAreAdded() {
        ReconcileResult result = QueryFieldReconciler.reconcile(
            List.of(CODE), List.of(CODE, CNT), List.of("code"));
        assertThat(result.added()).extracting(QueryField::name).containsExactly("cnt");
        assertThat(result.hasChanges()).isTrue();
    }

    @Test
    void vanishedFieldsAreRemoved() {
        ReconcileResult result = QueryFieldReconciler.reconcile(
            List.of(CODE, NAME), List.of(CODE), List.of("code", "name"));
        assertThat(result.removed()).extracting(QueryField::name).containsExactly("name");
    }

    @Test
    void typeChangeIsReported() {
        ReconcileResult result = QueryFieldReconciler.reconcile(
            List.of(CNT), List.of(field("cnt", Integer.class)), List.of("cnt"));
        assertThat(result.changedTypes()).hasSize(1);
        ReconcileResult.TypeChange change = result.changedTypes().get(0);
        assertThat(change.name()).isEqualTo("cnt");
        assertThat(change.oldType()).isEqualTo(Long.class);
        assertThat(change.newType()).isEqualTo(Integer.class);
    }

    @Test
    void layoutFieldMissingFromBothSetsIsUnknown() {
        ReconcileResult result = QueryFieldReconciler.reconcile(
            List.of(CODE), List.of(CODE), List.of("code", "ghost"));
        assertThat(result.unknown()).containsExactly("ghost");
        // "ghost" был битым ДО текущего reconcile — не added/removed
        assertThat(result.added()).isEmpty();
        assertThat(result.removed()).isEmpty();
    }

    @Test
    void renameLooksLikeRemoveAndAdd() {
        ReconcileResult result = QueryFieldReconciler.reconcile(
            List.of(field("old", String.class)), List.of(field("new", String.class)), List.of("old"));
        assertThat(result.removed()).extracting(QueryField::name).containsExactly("old");
        assertThat(result.added()).extracting(QueryField::name).containsExactly("new");
        assertThat(result.changedTypes()).isEmpty();
    }

    @Test
    void nullLayoutIsTolerated() {
        ReconcileResult result = QueryFieldReconciler.reconcile(List.of(CODE), List.of(CODE), null);
        assertThat(result.hasChanges()).isFalse();
    }

    private static QueryField field(String name, Class<?> javaType) {
        return new QueryField(name, name, javaType, name, true, true, QueryField.isNumber(javaType));
    }
}