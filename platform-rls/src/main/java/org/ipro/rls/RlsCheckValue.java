package org.ipro.rls;

/**
 * Одна проверка write-guard по одному измерению (см. {@link RlsDimensionValue}).
 *
 * Два подтипа — намеренно НЕ один и тот же "null" на два разных смысла (см. обсуждение
 * плана RLS, п.0):
 *
 * <ul>
 * <li>{@link Check} с id == null — значение измерения ещё не существует, потому что
 *     сущность САМА ЯВЛЯЕТСЯ измерением и создаётся впервые (новый Journal/Branch, у
 *     которого до insert ещё нет собственного id). Проверка всё равно идёт через
 *     {@link AccessService} — что на практике означает "пройдёт только
 *     у обладателя wildcard-гранта на это измерение", так как ни один построчный грант
 *     не может совпасть с ещё не существующим id. Это осознанное правило ("создавать
 *     новые Журналы/Филиалы могут только с полным доступом"), а не побочный эффект.</li>
 * <li>{@link NotApplicable} — сущность (или конкретная связь у неё, например Цех без
 *     Филиала) СОЗНАТЕЛЬНО не участвует в этом измерении для этой проверки. Проверка
 *     считается пройденной автоматически, AccessService не вызывается вовсе.</li>
 * </ul>
 */
public sealed interface RlsCheckValue {

    record Check(Long id) implements RlsCheckValue {
    }

    record NotApplicable() implements RlsCheckValue {
        public static final NotApplicable INSTANCE = new NotApplicable();
    }

    static RlsCheckValue of(Long id) {
        return new Check(id);
    }

    static RlsCheckValue notApplicable() {
        return NotApplicable.INSTANCE;
    }
}