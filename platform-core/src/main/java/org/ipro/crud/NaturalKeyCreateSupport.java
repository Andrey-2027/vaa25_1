package org.ipro.crud;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Дедуплицирующее «найти или создать» по натуральному ключу — операция интернирования
 * экземпляра (см. {@link InternedEntity}).
 *
 * <p>Основная сложность такой операции — не конфликт правок, а гонка на создании: два
 * потока одновременно не находят комбинацию и оба пытаются её вставить. Порядок попытки:</p>
 * <ol>
 * <li>всё тело выполняется в <b>своей</b> транзакции ({@code REQUIRES_NEW}): на PostgreSQL
 * отменённая транзакция помечает соединение как aborted, и продолжать в вызывающей
 * транзакции после unique-конфликта нельзя;</li>
 * <li>повторный SELECT внутри той же транзакции: если строку успел создать другой поток,
 * возвращается его строка — проигравший не вставляет ничего;</li>
 * <li>unique-нарушение классифицируется по цепочке причин (Hibernate может пробросить
 * JPA-обёртку, своё исключение напрямую или Spring {@code DataIntegrityViolationException})
 * и приводит к повтору попытки с новым SELECT;</li>
 * <li>исчерпание попыток — отказ с названным ключом (исходное нарушение остаётся в
 * {@code cause}), а не повтор последнего конфликта без контекста.</li>
 * </ol>
 *
 * <p>Компонент не знает ни о канонизации ключа, ни о составе создаваемого графа (шапка со
 * строками секции — обычный случай): и то, и другое остаётся за вызывающим, а общим является
 * ровно поведение интернирования.</p>
 */
public class NaturalKeyCreateSupport {

    private static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate createTx;

    public NaturalKeyCreateSupport(PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.createTx = new TransactionTemplate(transactionManager);
        this.createTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Complete-profile path: checks the selected structural key against both the winner
     * and the created row inside the attempt transaction, before it can commit.
     * The diagnostic string is never parsed to recover key properties.
     */
    public <T extends InternedEntity> T getOrCreate(Class<T> type, T key,
            Supplier<Optional<T>> finder, Supplier<T> creator) {
        Objects.requireNonNull(finder, "finder");
        Objects.requireNonNull(creator, "creator");
        var identity = InterningContracts.identity(type, key);
        String diagnostic = key.interningKey();
        java.util.function.UnaryOperator<T> checked = row -> {
            if (!identity.equals(InterningContracts.identity(type, Objects.requireNonNull(row)))
                    || !diagnostic.equals(row.interningKey())) {
                throw new IllegalStateException("INTERNED_KEY_MISMATCH " + type.getName()
                    + ": finder/creator returned a different natural key: " + identity);
            }
            return row;
        };
        return getOrCreate(diagnostic, () -> finder.get().map(checked), () -> checked.apply(creator.get()));
    }

    /**
     * Найти существующий экземпляр или создать новый, разрешая гонку создателей.
     *
     * @param interningKey ключ идентичности — попадает в диагностику отказа, когда попытки
     *                     исчерпаны и гонку разрешить не удалось
     * @param finder       повторный поиск уже существующего экземпляра; вызывается
     *                     <b>внутри</b> транзакции попытки
     * @param creator      создание экземпляра, если поиск пуст; вызывается там же, поэтому
     *                     создаваемый граф (шапка + строки) попадает в одну транзакцию
     */
    public <T> T getOrCreate(String interningKey, Supplier<Optional<T>> finder,
                             Supplier<T> creator) {
        Objects.requireNonNull(finder, "finder must not be null");
        Objects.requireNonNull(creator, "creator must not be null");
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return createTx.execute(status -> finder.get().orElseGet(creator));
            } catch (RuntimeException conflict) {
                if (!isUniqueViolation(conflict)) {
                    throw conflict;
                }
                if (attempt == MAX_ATTEMPTS) {
                    // Исходное нарушение сохраняется в cause: диагностируется именно оно, а
                    // ключ добавляется только чтобы было понятно, какой экземпляр не сошёлся.
                    throw new IllegalStateException("Интернирование по ключу '" + interningKey
                        + "' не разрешилось за " + MAX_ATTEMPTS + " попытки: уникальный конфликт"
                        + " повторяется. Проверьте, что ключ каноничен и что повторный SELECT ищет"
                        + " по тому же представлению, которое защищает уникальный индекс.",
                        conflict);
                }
                // гонка: другой поток уже создал строку — повторяем SELECT в новой транзакции
            }
        }
        throw new IllegalStateException("Недостижимо: цикл попыток всегда завершается возвратом"
            + " или отказом.");
    }

    /**
     * Классификация уникального нарушения по цепочке причин (Hibernate может пробросить
     * как JPA-обёртку, так и своё исключение напрямую, Spring — как DataIntegrityViolation).
     */
    private static boolean isUniqueViolation(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())) return true;
        }
        return false;
    }
}
