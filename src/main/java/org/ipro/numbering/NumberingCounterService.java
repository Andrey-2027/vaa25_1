package org.ipro.numbering;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Низкоуровневая аллокация последовательности. Вся работа идёт в СВОЕЙ короткой транзакции
 * ({@code REQUIRES_NEW}) с пессимистичной блокировкой строки счётчика — гонки нет, время
 * удержания лока минимально (не блокируется весь save() документа).
 *
 * <p>Дыра «первого счётчика»: {@code PESSIMISTIC_WRITE} по НЕсуществующей строке не создаёт
 * gap-lock, поэтому два параллельных first-insert'а оба видят null. Строка счётчика — единственная
 * точка, где могут возникнуть дубли, и она закрыта здесь: первым делом {@code flush()} вносит
 * INSERT в этой же короткой транзакции — проигравший получает unique-violation, его транзакция
 * на Postgres помечается aborted, и retry выполняется СНАРУЖИ ({@link NumberingService#next}),
 * целиком новой транзакцией. Пропуски при откатах — допустимы, дубли — нет.</p>
 */
public class NumberingCounterService {

    @PersistenceContext
    private EntityManager entityManager;

    public NumberingCounterService() {
    }

    public NumberingCounterService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long allocate(String key, long initialValue) {
        NumberingCounter counter = entityManager.find(NumberingCounter.class, key, LockModeType.PESSIMISTIC_WRITE);
        if (counter == null) {
            counter = new NumberingCounter(key, initialValue);
            entityManager.persist(counter);
            entityManager.flush();
        }
        counter.setLastValue(counter.getLastValue() + 1);
        return counter.getLastValue();
    }

    /** Правка «текущего значения» администратором — через ТОТ ЖЕ locking-путь, не UPDATE в обход. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setCurrentValue(String key, long value) {
        NumberingCounter counter = entityManager.find(NumberingCounter.class, key, LockModeType.PESSIMISTIC_WRITE);
        if (counter == null) {
            entityManager.persist(new NumberingCounter(key, value));
            entityManager.flush();
        } else {
            counter.setLastValue(value);
        }
    }

    /** Текущее значение счётчика без побочных эффектов (0 — счётчик ещё не создан). Для админ-экрана. */
    @Transactional(readOnly = true)
    public long lastValue(String key) {
        NumberingCounter counter = entityManager.find(NumberingCounter.class, key);
        return counter == null ? 0L : counter.getLastValue();
    }
}
