package org.ip.config;

import org.ipro.query.QueryReadRefusedException;
import org.ipro.report.task.ReportTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationQueryTaskRunnerTest {
    @Test
    void anonymousAndRejectedSubmissionsFailThroughTheFuture() {
        try (var tasks = new ReportTaskExecutor(work -> { throw new RejectedExecutionException(); })) {
            var runner = new ApplicationQueryListConfiguration().queryTaskRunner(tasks);
            SecurityContextHolder.clearContext();
            assertThatThrownBy(() -> runner.submit(() -> "unexpected").join())
                    .hasCauseInstanceOf(QueryReadRefusedException.class)
                    .hasRootCauseMessage("Требуется вход для выполнения запроса");
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("first", null, List.of()));
            assertThatThrownBy(() -> runner.submit(() -> "unexpected").join())
                    .hasCauseInstanceOf(QueryReadRefusedException.class)
                    .hasRootCauseMessage("Очередь выполнения запросов заполнена");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void usesSubmittingUserOnWorkerAndClearsWorkerAfterwards() throws Exception {
        try (var thread = Executors.newSingleThreadExecutor(); var tasks = new ReportTaskExecutor(thread)) {
            var runner = new ApplicationQueryListConfiguration().queryTaskRunner(tasks);
            for (String user : List.of("first", "second")) {
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
                var result = runner.submit(() -> SecurityContextHolder.getContext().getAuthentication().getName());
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(user);
                assertThat(thread.submit(() -> SecurityContextHolder.getContext().getAuthentication())
                        .get(10, TimeUnit.SECONDS)).isNull();
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
