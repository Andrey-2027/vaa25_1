package org.ipro.reportstudio.run;

import org.ipro.rls.RlsAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Единственный исполнитель фоновой работы приложения: снимок субъекта на стороне
 * платформы, отказ без аутентифицированного пользователя и гарантированная очистка
 * ThreadLocal.
 *
 * <p>Именно эти свойства делают ненужным ручной перенос контекста в прикладном коде
 * (см. `DAC-11`): без них прикладник снова начнёт сам выставлять SecurityContext и
 * забывать его снимать.</p>
 */
class ReportTaskExecutorTest {

    @AfterEach
    void clearThreadLocals() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void taskSeesSnapshotEvenWhenSourceIsMutatedAfterSubmission() throws Exception {
        List<SimpleGrantedAuthority> mutableAuthorities = new ArrayList<>(
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        Authentication source =
            new UsernamePasswordAuthenticationToken("alice", "secret", mutableAuthorities);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ReportTaskExecutor executor = new ReportTaskExecutor(pool);
            CountDownLatch submitted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<String> seenName = new AtomicReference<>();
            AtomicReference<List<String>> seenAuthorities = new AtomicReference<>();

            executor.execute(source, null, () -> {
                submitted.countDown();
                await(release);
                Authentication current = SecurityContextHolder.getContext().getAuthentication();
                seenName.set(current.getName());
                seenAuthorities.set(current.getAuthorities().stream()
                    .map(Object::toString).toList());
            });
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();

            // Субъект и его права меняются уже после постановки задачи: снимок обязан
            // остаться прежним, иначе воркер работал бы под изменённым набором прав.
            source.setAuthenticated(false);
            mutableAuthorities.clear();
            release.countDown();

            assertThat(pool.submit(() -> null).get(5, TimeUnit.SECONDS)).isNull();
            assertThat(seenName.get()).isEqualTo("alice");
            assertThat(seenAuthorities.get()).containsExactly("ROLE_USER");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void taskRunsWithTheProvidedRequestAttributes() {
        ReportTaskExecutor executor = new ReportTaskExecutor(Runnable::run);
        RequestAttributes attributes = new ProbeRequestAttributes();
        AtomicReference<RequestAttributes> seen = new AtomicReference<>();

        executor.execute(authenticated("bob"), attributes,
            () -> seen.set(RequestContextHolder.getRequestAttributes()));

        assertThat(seen.get()).isSameAs(attributes);
        // исполнитель обязан снять и request attributes, а не только SecurityContext
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Test
    void contextIsClearedAfterSuccessfulTask() {
        ReportTaskExecutor executor = new ReportTaskExecutor(Runnable::run);

        executor.execute(authenticated("carol"), null, () -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("carol");
        });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void contextIsClearedEvenWhenTaskFails() {
        ReportTaskExecutor executor = new ReportTaskExecutor(Runnable::run);

        assertThatThrownBy(() -> executor.execute(authenticated("dave"), null, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unauthenticatedSubjectIsRejectedBeforeTaskIsSubmitted() {
        ReportTaskExecutor executor = new ReportTaskExecutor(Runnable::run);
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> executor.execute(null, null, () -> ran.set(true)))
            .isInstanceOf(RlsAccessDeniedException.class);
        assertThatThrownBy(() -> executor.execute(authenticated("system"), null, () -> ran.set(true)))
            .isInstanceOf(RlsAccessDeniedException.class);
        assertThatThrownBy(() -> executor.execute(
            new UsernamePasswordAuthenticationToken("alice", "n/a"), null, () -> ran.set(true)))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("authenticated");

        assertThat(ran).isFalse();
    }

    private static Authentication authenticated(String username) {
        return new UsernamePasswordAuthenticationToken(username, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** Минимальные request attributes: проверяется только факт установки и снятия. */
    private static final class ProbeRequestAttributes implements RequestAttributes {

        @Override
        public Object getAttribute(String name, int scope) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value, int scope) {
            // значения в этом тесте не читаются
        }

        @Override
        public void removeAttribute(String name, int scope) {
            // нечего удалять
        }

        @Override
        public String[] getAttributeNames(int scope) {
            return new String[0];
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback, int scope) {
            // деструкция ничего не делает вручную
        }

        @Override
        public Object resolveReference(String key) {
            return null;
        }

        @Override
        public String getSessionId() {
            return "probe-session";
        }

        @Override
        public Object getSessionMutex() {
            return this;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
