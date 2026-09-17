package org.ipro.crud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C4.6: интернирование экземпляров (см. {@link InternedEntity}) — поведение вынесено из двух
 * прикладных сервисов в один компонент, поэтому проверяется напрямую, а не только через свои
 * вызовы: ретрай на unique-конфликте, отсутствие ретрая на других ошибках и диагностика при
 * исчерпании попыток.
 */
class NaturalKeyCreateSupportTest {

    private NaturalKeyCreateSupport support;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
            .thenAnswer(invocation -> new SimpleTransactionStatus());
        support = new NaturalKeyCreateSupport(transactionManager);
    }

    @Test
    void returnsFoundInstanceWithoutCallingCreator() {
        AtomicInteger created = new AtomicInteger();

        String result = support.getOrCreate("nom-1|a:1",
            () -> Optional.of("победитель"),
            () -> {
                created.incrementAndGet();
                return "новый";
            });

        assertThat(result).isEqualTo("победитель");
        assertThat(created).hasValue(0);
    }

    @Test
    void retriesAfterUniqueConflictAndReturnsTheWinnersRow() {
        AtomicInteger finderCalls = new AtomicInteger();

        String result = support.getOrCreate("nom-1|a:1",
            () -> finderCalls.getAndIncrement() == 0
                ? Optional.empty()
                : Optional.of("победитель"),
            () -> {
                throw new DataIntegrityViolationException("uk_skl_nom_opa_nom_canonical");
            });

        assertThat(result)
            .as("проигравший гонку обязан вернуть строку победителя, а не свой INSERT")
            .isEqualTo("победитель");
        assertThat(finderCalls).hasValue(2);
    }

    @Test
    void doesNotRetryOnNonUniqueFailure() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> support.getOrCreate("nom-1|a:1",
            () -> Optional.empty(),
            () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("нарушение внешнего ключа");
            }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("внешнего ключа");

        assertThat(attempts)
            .as("неизвестная ошибка не должна считаться гонкой и повторяться")
            .hasValue(1);
    }

    @Test
    void reportsTheContendedKeyWhenAttemptsAreExhausted() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> support.getOrCreate("nom-7|a:3;b:9",
            () -> Optional.empty(),
            () -> {
                attempts.incrementAndGet();
                throw new DataIntegrityViolationException("duplicate key value");
            }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nom-7|a:3;b:9")
            .hasMessageContaining("3 попытки");

        assertThat(attempts).hasValue(3);
    }

    @Test
    void createsInsideOwnAttemptWhenNothingIsFound() {
        AtomicInteger created = new AtomicInteger();

        String result = support.getOrCreate("nom-2|a:1",
            () -> Optional.empty(),
            () -> {
                created.incrementAndGet();
                return "новый";
            });

        assertThat(result).isEqualTo("новый");
        assertThat(created).hasValue(1);
    }

    @Test
    void rejectsMissingCollaborators() {
        assertThatThrownBy(() -> new NaturalKeyCreateSupport(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> support.getOrCreate("k", null, () -> "x"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> support.getOrCreate("k", () -> Optional.empty(), null))
            .isInstanceOf(NullPointerException.class);
    }

    /**
     * Важно, что требование отдельной транзакции на попытку сохраняется: на PostgreSQL
     * продолжать работу в отменённой транзакции после unique-конфликта нельзя.
     */
    @Test
    void runsEveryAttemptInItsOwnTransaction() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        AtomicInteger transactions = new AtomicInteger();
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> {
            transactions.incrementAndGet();
            return new SimpleTransactionStatus();
        });
        NaturalKeyCreateSupport counted = new NaturalKeyCreateSupport(transactionManager);

        assertThatThrownBy(() -> counted.getOrCreate("nom-1|a:1", () -> Optional.empty(), () -> {
            throw new DataIntegrityViolationException("duplicate");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasCauseInstanceOf(DataIntegrityViolationException.class);

        assertThat(transactions)
            .as("каждая попытка обязана идти в своей транзакции")
            .hasValue(3);
    }
}
