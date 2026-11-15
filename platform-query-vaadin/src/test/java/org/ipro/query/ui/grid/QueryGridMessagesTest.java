package org.ipro.query.ui.grid;

import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.query.QueryReadRefusedException;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryLimits;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Тексты строки состояния и состояний области данных (UX-5, UX-11). */
class QueryGridMessagesTest {
    private static QueryGridSpec spec() {
        return new QueryGridSpec("Список", "key#1", new QueryDefinition("select 1", null, null, null, List.of(),
                new QueryLimits(5000, 30_000)), ProjectionExecutionMode.LAZY, 0, null, null, null);
    }

    @Test
    void statusLinesFollowTheMode() {
        var at = LocalTime.of(14, 2);
        var took = Duration.ofMillis(8300);

        assertThat(QueryGridStatus.text(ProjectionExecutionMode.LAZY, 1240, -1, false, 0, null, null))
                .isEqualTo("Строк: 1 240");
        assertThat(QueryGridStatus.text(ProjectionExecutionMode.IN_MEMORY, 212, 212, false, 0, at, took))
                .isEqualTo("Целиком в памяти · 212 строк · данные на 14:02 (выполнено за 8,3 с)");
        assertThat(QueryGridStatus.text(ProjectionExecutionMode.IN_MEMORY, 134, 212, false, 0, at, took))
                .isEqualTo("Целиком в памяти · 134 из 212 строк · данные на 14:02 (выполнено за 8,3 с)");
        assertThat(QueryGridStatus.text(ProjectionExecutionMode.HYBRID, 5000, 5000, true, 5000, at, took))
                .isEqualTo("Показаны первые 5 000 строк; итоги и группы — по всей выборке");
        assertThat(QueryGridStatus.text(ProjectionExecutionMode.HYBRID, 40, 40, false, 5000, at, took))
                .startsWith("Строк: 40 · данные на 14:02");
    }

    @Test
    void notRunHintMentionsThePreviousDurationWhenKnown() {
        assertThat(QueryGridStatus.notRunHint(null)).isEqualTo("Заполните параметры и нажмите «Выполнить».");
        assertThat(QueryGridStatus.notRunHint(Duration.ofMillis(8300))).endsWith("Последнее выполнение — 8,3 с.");
        assertThat(QueryGridStatus.elapsed(Duration.ofSeconds(72))).isEqualTo("01:12");
    }

    @Test
    void runHistoryRemembersTheLastDurationPerKeyAndIsBounded() {
        var history = new QueryRunHistory();
        history.record("a", Duration.ofSeconds(1));
        history.record("a", Duration.ofSeconds(2));

        assertThat(history.lastDuration("a")).contains(Duration.ofSeconds(2));
        assertThat(history.lastDuration("b")).isEmpty();
        for (int index = 0; index < 600; index++) history.record("k" + index, Duration.ofSeconds(index));
        assertThat(history.lastDuration("a")).as("старые ключи вытесняются").isEmpty();
        assertThat(history.lastDuration("k599")).isPresent();
    }

    @Test
    void platformRefusalsKeepTheirMeaning() {
        var limit = QueryGridFailure.of(new QueryReadRefusedException(QueryReadRefusedException.Reason.ROW_LIMIT, "x"),
                spec(), 30_000);
        assertThat(limit.kind()).isEqualTo(QueryGridFailure.Kind.ROW_LIMIT);
        assertThat(limit.title()).isEqualTo("Результат больше 30 000 строк — целиком в память не помещается");
        assertThat(limit.message()).contains("Сузьте параметры").contains("«Постранично»");

        assertThat(QueryGridFailure.of(new QueryReadRefusedException(QueryReadRefusedException.Reason.QUOTA_BUSY, "x"),
                spec(), 0).title()).isEqualTo("Сейчас выполняется много отчётов");
        assertThat(QueryGridFailure.of(new QueryReadRefusedException(QueryReadRefusedException.Reason.ACCESS_CHANGED, "x"),
                spec(), 0).kind()).isEqualTo(QueryGridFailure.Kind.ACCESS_CHANGED);
        var denied = QueryGridFailure.of(new QueryReadRefusedException(QueryReadRefusedException.Reason.ACCESS_DENIED,
                "Отказ: нет ENTITY-read для Накладная"), spec(), 0);
        assertThat(denied.kind()).isEqualTo(QueryGridFailure.Kind.NO_ACCESS);
        assertThat(denied.message()).doesNotContain("Накладная");
        assertThat(denied.details()).contains("Накладная");
    }

    @Test
    void timeoutIsFoundInsideTheCauseChain() {
        var timeout = new RuntimeException("wrapped", new RuntimeException(new SQLException("canceled", "57014")));

        var failure = QueryGridFailure.of(timeout, spec(), 0);

        assertThat(failure.kind()).isEqualTo(QueryGridFailure.Kind.TIMEOUT);
        assertThat(failure.message()).contains("30 с");
    }

    @Test
    void compileTimeRefusalsAndBrokenQueriesAreToldApart() {
        assertThat(QueryGridFailure.of(new IllegalArgumentException("Отказ: нет права"), spec(), 0).kind())
                .isEqualTo(QueryGridFailure.Kind.NO_ACCESS);
        assertThat(QueryGridFailure.of(new IllegalArgumentException("Не удалось заполнить параметры: a"), spec(), 0).kind())
                .isEqualTo(QueryGridFailure.Kind.PARAMETERS);
        var broken = QueryGridFailure.of(new IllegalArgumentException("Поле удалено"), spec(), 0);
        assertThat(broken.kind()).isEqualTo(QueryGridFailure.Kind.NEEDS_FIX);
        assertThat(broken.message()).doesNotContain("Поле удалено");
        assertThat(QueryGridFailure.missingParameters(List.of("Цех", "Период")).message())
                .isEqualTo("Не заполнено: Цех, Период.");
    }
}
