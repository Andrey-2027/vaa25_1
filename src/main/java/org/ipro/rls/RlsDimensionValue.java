package org.ipro.rls;

import java.util.List;
import java.util.Map;

/**
 * Реализуется КАЖДОЙ RLS-защищённой на запись сущностью (заменяет прежний однозначный
 * {@code getRlsDimensionValueId(): Long} — тот не мог выразить сущность, зависящую от
 * нескольких измерений сразу, например документ с проверкой и по Журналу, и по Филиалу
 * через два разных Цеха).
 *
 * checkRlsWrite/checkRlsDelete в AbstractBaseService требуют ВСЕ проверки из ВСЕХ
 * измерений разом (AND) — как между разными измерениями, так и между несколькими
 * проверками одного измерения (например, у ReceivingDocument по измерению "BRANCH" — две
 * проверки: через Цех-приёмщик и через Цех-сдатчик, обе должны пройти).
 *
 * Сущность, НЕ реализующая этот интерфейс, write-guard'ом не проверяется вовсе (не
 * защищена RLS на запись) — это и есть признак "участвует/не участвует", вместо
 * отдельной аннотации-маркера.
 *
 * Пример (сущность сама является измерением — Journal/Branch):
 * <pre>{@code
 * public Map<String, List<RlsCheckValue>> getRlsChecks() {
 *     return Map.of("JOURNAL", List.of(RlsCheckValue.of(getId())));
 * }
 * }</pre>
 *
 * Пример (зависимая сущность с опциональным участием — Workshop через Branch):
 * <pre>{@code
 * public Map<String, List<RlsCheckValue>> getRlsChecks() {
 *     return Map.of("BRANCH", List.of(
 *         branch != null ? RlsCheckValue.of(branch.getId()) : RlsCheckValue.notApplicable()));
 * }
 * }</pre>
 */
public interface RlsDimensionValue {

    Map<String, List<RlsCheckValue>> getRlsChecks();
}