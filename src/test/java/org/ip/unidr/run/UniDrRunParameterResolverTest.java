package org.ip.unidr.run;

import org.ip.unidr.model.*;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class UniDrRunParameterResolverTest {
    private final UniDrRunParameterResolver resolver = new UniDrRunParameterResolver(new UniDrDefinitionValidator(UniDrSourceCatalog.pilot()));
    private final UniDrDefinition definition = UniDrDefinitions.receivingItemsSummary();
    private final Instant now = Instant.parse("2026-10-31T23:30:00Z");
    private Map<String, Literal> resolve(Map<String, Literal> values, ZoneId zone) {
        return resolver.resolve(new UniDrRunRequest(definition, values, zone), now);
    }
    @Test void currentMonthUsesRunZoneAndNeverModifiesDefinition() {
        var result = resolve(Map.of("unit", new Literal(ValueType.REFERENCE, List.of("12"))), ZoneId.of("Europe/Moscow"));
        assertThat(result.get("period").values()).containsExactly("2026-11-01", "2026-11-30");
        assertThat(definition.parameters().getFirst().defaultRule()).isEqualTo(DefaultRule.CURRENT_MONTH);
        assertThat(result).doesNotContainKey("recipient");
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void explicitPeriodOverridesCurrentMonth() {
        var period = new Literal(ValueType.DATE, List.of("2024-02-01", "2024-02-29"));
        assertThat(resolve(Map.of("unit", new Literal(ValueType.REFERENCE, List.of("12")), "period", period), ZoneOffset.UTC))
            .containsEntry("period", period);
    }
    @Test void quantityRequiresUnitButCountRowsDoesNot() {
        assertThatThrownBy(() -> resolve(Map.of(), ZoneOffset.UTC)).isInstanceOf(UniDrValidationException.class).hasMessageContaining("unit");
        var count = new UniDrDefinition(1, definition.sourceId(), 1, definition.title(), List.of(),
            List.of(new Measure("rowCount", Aggregation.COUNT_ROWS, null)), List.of(), List.of(), definition.parameters(), List.of(), Mode.SUMMARY, true);
        assertThat(resolver.resolve(new UniDrRunRequest(count, Map.of(), ZoneOffset.UTC), now)).containsOnlyKeys("period");
    }
    @Test void unknownRunParameterIsRejected() {
        assertThatThrownBy(() -> resolve(Map.of("owner", new Literal(ValueType.REFERENCE, List.of("1"))), ZoneOffset.UTC))
            .isInstanceOf(UniDrValidationException.class).hasMessageContaining("values.owner");
    }
    static Stream<Literal> invalidPeriods() { return Stream.of(
        new Literal(ValueType.DATE, List.of()), new Literal(ValueType.DATE, List.of("2026-10-01")),
        new Literal(ValueType.DATE, List.of("2026-10-31", "2026-10-01")),
        new Literal(ValueType.DATE, List.of("2026-02-30", "2026-10-01")),
        new Literal(ValueType.REFERENCE, List.of("1", "2")));
    }
    @ParameterizedTest @MethodSource("invalidPeriods") void invalidPeriodFailsBeforeQuery(Literal value) {
        assertThatThrownBy(() -> resolve(Map.of("period", value, "unit", new Literal(ValueType.REFERENCE, List.of("1"))), ZoneOffset.UTC))
            .isInstanceOf(UniDrValidationException.class).hasMessageContaining("values.period");
    }
    @Test void fixedDefaultCanBeClearedForOptionalParameter() {
        var params = new ArrayList<>(definition.parameters());
        params.set(2, new Parameter("recipient", "receivingWorkshop", Operator.EQ, "Цех", DefaultRule.FIXED, new Literal(ValueType.REFERENCE, List.of("2"))));
        var modified = new UniDrDefinition(1, definition.sourceId(), 1, definition.title(), definition.columns(), definition.measures(),
            definition.groups(), definition.filters(), params, definition.sorts(), definition.mode(), true);
        assertThat(resolver.resolve(new UniDrRunRequest(modified, Map.of("unit", new Literal(ValueType.REFERENCE, List.of("1")),
            "recipient", new Literal(ValueType.REFERENCE, List.of())), ZoneOffset.UTC), now)).doesNotContainKey("recipient");
        assertThat(resolver.resolve(new UniDrRunRequest(modified, Map.of("unit", new Literal(ValueType.REFERENCE, List.of("1"))), ZoneOffset.UTC), now))
            .containsEntry("recipient", new Literal(ValueType.REFERENCE, List.of("2")));
        var cleared=new LinkedHashMap<String,Literal>();cleared.put("unit",new Literal(ValueType.REFERENCE,List.of("1")));cleared.put("recipient",null);
        var request=new UniDrRunRequest(modified,cleared,ZoneOffset.UTC);cleared.put("recipient",new Literal(ValueType.REFERENCE,List.of("3")));
        assertThat(resolver.resolve(request,now)).doesNotContainKey("recipient");
        assertThatThrownBy(()->request.values().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
