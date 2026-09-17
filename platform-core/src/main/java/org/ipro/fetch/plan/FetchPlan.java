package org.ipro.fetch.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Разрешённый план загрузки для пары (entityClass, scenario): детерминированный список
 * attribute paths и причина включения каждого из них (C3.3, ADR-0006).
 *
 * <p>Immutable и кэшируется registry; план одного сценария не смешивается с планом другого.
 * Причины нужны для диагностики «почему этот путь здесь» и для измерений размера графа —
 * без них ограничение графа не проверить.</p>
 */
public final class FetchPlan {

    private final Class<?> entityClass;
    private final FetchScenario scenario;
    private final List<String> paths;
    private final List<String> rawPaths;
    private final Map<String, String> reasons;

    FetchPlan(Class<?> entityClass, FetchScenario scenario,
              List<String> paths, List<String> rawPaths, Map<String, String> reasons) {
        this.entityClass = entityClass;
        this.scenario = scenario;
        this.paths = List.copyOf(paths);
        this.rawPaths = List.copyOf(rawPaths);
        this.reasons = Map.copyOf(new LinkedHashMap<>(reasons));
    }

    /** Сущность плана. */
    public Class<?> entityClass() {
        return entityClass;
    }

    /** Сценарий плана. */
    public FetchScenario scenario() {
        return scenario;
    }

    /** Пути ассоциаций в стабильном лексикографическом порядке, уже углублённые. */
    public List<String> paths() {
        return paths;
    }

    /**
     * Пути плана до углубления — вход правила {@code plan ∪ extras -> deepen once}. Нужны,
     * чтобы объединение с динамическими путями углублялось ровно один раз, а не проходило
     * {@code deepen} повторно над уже углублённым планом.
     */
    public List<String> rawPaths() {
        return rawPaths;
    }

    /** Почему путь попал в план: {@code metadata:LIST}, {@code instance-name}, {@code lookup:<Owner.field>}, {@code reference-name:<prefix>}. */
    public String reasonFor(String path) {
        return reasons.get(path);
    }

    /** Размер графа — для измерений и диагностики. */
    public int size() {
        return paths.size();
    }

    public boolean isEmpty() {
        return paths.isEmpty();
    }

    @Override
    public String toString() {
        return "FetchPlan{" + entityClass.getSimpleName() + ", " + scenario
            + ", paths=" + paths + '}';
    }
}
