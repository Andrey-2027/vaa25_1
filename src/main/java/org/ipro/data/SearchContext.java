package org.ipro.data;

import org.ipro.fetch.plan.FetchScenario;

/**
 * Контекст серверного поиска C4.4 (ADR-0007 §7): один query builder, разные намерения.
 *
 * <p>До C4.4 список, автокомплит ссылки и глобальный поиск собирали запросы независимо,
 * поэтому одна и та же операция шла с разной policy (blank term, escaping, порядок,
 * limit). Контекст фиксирует это как данные, а не как копию builder'а. Standard LIST и
 * LOOKUP уже используют общий builder; подключение текущих global-search providers к
 * {@link #GLOBAL} выполняется отдельным шагом C4.5.</p>
 *
 * <ul>
 * <li>{@link #LIST} — поиск по таблице формы списка;</li>
 * <li>{@link #LOOKUP} — автокомплит выбора: явные поля, переданные UI, проверяются
 * <b>мягко</b> (неизвестное/нестроковое поле пропускается, как раньше), потому что их
 * источник — метаданные и пользовательские варианты выбора, а не конфигурация поиска;</li>
 * <li>{@link #GLOBAL} — глобальный поиск по нескольким источникам; telemetry intent
 * отличается от обычного LIST, хотя FetchPlan остаётся {@code LIST}.</li>
 * </ul>
 *
 * <p>{@code LIST}/{@code GLOBAL} сортируют по рангу совпадения, {@code LOOKUP} — только по
 * id: автокомплит показывает первые совпадения, и смена порядка здесь не является задачей.
 * Blank term у любого контекста не является фильтром: выдача bounded, а не «вся таблица»
 * (ADR-0007 §7).</p>
 */
public enum SearchContext {

    LIST(DataOperation.LIST, FetchScenario.LIST, true, true),
    LOOKUP(DataOperation.LOOKUP, FetchScenario.LOOKUP, false, false),
    GLOBAL(DataOperation.GLOBAL_SEARCH, FetchScenario.LIST, true, true);

    private final DataOperation operation;
    private final FetchScenario scenario;
    private final boolean strictExplicitFields;
    private final boolean ranked;

    SearchContext(DataOperation operation, FetchScenario scenario,
                  boolean strictExplicitFields, boolean ranked) {
        this.operation = operation;
        this.scenario = scenario;
        this.strictExplicitFields = strictExplicitFields;
        this.ranked = ranked;
    }

    /** Намерение telemetry операции. */
    public DataOperation operation() {
        return operation;
    }

    /** Fetch-сценарий, чей план и capability проверяются до RLS и SQL. */
    public FetchScenario scenario() {
        return scenario;
    }

    /** Явные поля проверяются строго (list/global) или мягко (lookup). */
    public boolean strictExplicitFields() {
        return strictExplicitFields;
    }

    /** Сортировать по рангу совпадения ({@code exact → prefix → substring}). */
    public boolean ranked() {
        return ranked;
    }
}
