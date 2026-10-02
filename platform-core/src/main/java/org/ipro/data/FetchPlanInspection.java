package org.ipro.data;

import org.ipro.fetch.plan.FetchPlan;
import org.ipro.fetch.plan.FetchPlanRegistry;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.FactOrigin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Инспекция чтения типа (E3.2.0 шаг 2): какие read-сценарии у типа, откуда пришёл их набор и
 * какие пути несёт план каждого сценария.
 *
 * <p>Знание о плане принадлежит модулю-владельцу: {@link FetchPlanRegistry} и
 * {@link FetchPlan} живут здесь, а сам план помечен {@code INTERNAL} в реестре ролей — называть
 * его снаружи ядра нельзя. Инспекция переопубликовывает факты простыми значениями (имя сценария,
 * признак допуска, происхождение набора, пути с причинами), а тексты и строки собирает
 * потребитель — тем же способом, которым E3.1 отдал lifecycle
 * ({@code EntityLifecycleInspection} в {@code platform-events}).</p>
 *
 * <p><b>Что инспекция не делает.</b> Плана запроса она не знает: {@code pathsWith} — правило
 * конкретного вызова (динамические пути ищутся по объявлениям вызывающего), поэтому в строку
 * попадает <b>план типа</b>. Дополнительные пути запроса — не факт типа, и показывать их здесь
 * значило бы выдать один запрос за устройство типа.</p>
 *
 * <p><b>Строки не дорисовываются.</b> Сценарий попадает в выдачу, если он допущен canonical
 * path <b>или</b> у него есть непустой план: обе половины — факты, и скрывать любую из них
 * нельзя. Обратное тоже верно: комбинаций, которых нет ни в наборе, ни в плане, не существует —
 * порядок строк задан {@link FetchScenario#values()}, а не порядком вычисления.</p>
 */
public final class FetchPlanInspection {

    private final FetchPlanRegistry registry;
    private final EntityDescriptorCatalog descriptorCatalog;

    /**
     * Инспекция над уже построенными компонентами. Оба обязательны: без реестра «плана нет» не
     * отличить от «плана не спросили», а без каталога неизвестен набор сценариев и его
     * происхождение. Частичный контекст получает отсутствие бина, а не пустую выдачу.
     */
    public FetchPlanInspection(FetchPlanRegistry registry, EntityDescriptorCatalog descriptorCatalog) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.descriptorCatalog = Objects.requireNonNull(descriptorCatalog,
            "descriptorCatalog must not be null");
    }

    /**
     * Строки сценариев типа в порядке {@link FetchScenario#values()}: допущенные плюс те, у
     * которых есть непустой план.
     */
    public List<Scenario> scenariosOf(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        EntityDescriptor descriptor = descriptorCatalog.descriptorOf(type);
        Set<FetchScenario> allowed = descriptor.capabilities().readScenarios();

        List<Scenario> rows = new ArrayList<>(FetchScenario.values().length);
        for (FetchScenario scenario : FetchScenario.values()) {
            boolean allows = allowed.contains(scenario);
            List<Path> paths = pathsOf(type, scenario);
            if (!allows && paths.isEmpty()) {
                continue;
            }
            rows.add(new Scenario(scenario.name(), allows, descriptor.capabilitiesOrigin(),
                descriptor.capabilitiesSymbol(), paths));
        }
        return List.copyOf(rows);
    }

    /**
     * Пути плана сценария с причиной каждого пути; сценарий без плана даёт пустой список — это
     * факт «путей нет», а не «сценарий недоступен» (за допуск отвечает {@link Scenario#allowed()}).
     */
    public List<Path> pathsOf(Class<?> type, FetchScenario scenario) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");
        FetchPlan plan = registry.plan(type, scenario);
        List<Path> paths = new ArrayList<>(plan.paths().size());
        for (String attributePath : plan.paths()) {
            paths.add(new Path(attributePath, plan.reasonFor(attributePath)));
        }
        return List.copyOf(paths);
    }

    /**
     * Сценарий типа: допущен ли canonical path, откуда пришёл набор сценариев и какие пути в плане.
     *
     * <p>{@code origin} и {@code symbol} описывают происхождение <b>набора</b>, а не отдельного
     * сценария: объявление приложения задаёт набор целиком, поэтому расхождение происхождения у
     * строк одного типа означало бы ошибку сборки факта, а не разные решения.</p>
     */
    public record Scenario(String scenario, boolean allowed, FactOrigin origin, String symbol,
                           List<Path> paths) {

        public Scenario {
            Objects.requireNonNull(scenario, "scenario must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
            symbol = symbol == null ? "" : symbol;
            paths = List.copyOf(paths);
        }

        /** Число путей плана — то же, что вернул бы реестр для этого сценария. */
        public int pathCount() {
            return paths.size();
        }
    }

    /**
     * Путь плана и причина, по которой он в плане: {@code metadata:<SCENARIO>},
     * {@code instance-name}, {@code lookup:<Owner.field>}, {@code reference-name[:<префикс>]}.
     */
    public record Path(String attributePath, String reason) {

        public Path {
            Objects.requireNonNull(attributePath, "attributePath must not be null");
            reason = reason == null ? "" : reason;
        }
    }
}
