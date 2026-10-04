package org.ipro.rest.service;

import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestResourceKey;
import org.junit.jupiter.api.Test;

import jakarta.persistence.metamodel.Attribute;
import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestFilterSpecificationCompilerTest {

    private static final class FilterRoot { }

    @Test
    void rejectsMalformedBooleanRatherThanConvertingItToFalse() {
        assertRejected("enabled", boolean.class, "not-a-boolean");
    }

    @Test
    void rejectsFractionalNumberForLongRatherThanTruncating() {
        assertRejected("amount", Long.class, new BigDecimal("2.7"));
    }

    @Test
    void rejectsOverflowingLongForIntegerRatherThanWrapping() {
        assertRejected("count", Integer.class, 2_147_483_648L);
    }

    @Test
    void acceptsOnlyExactTypedScalarValue() {
        ResolvedRestResource resource = resource("amount", Long.class);
        RestFilterSpecificationCompiler.compile(resource, Map.of("amount", 7L));
    }

    private void assertRejected(String filter, Class<?> type, Object value) {
        assertThatThrownBy(() -> RestFilterSpecificationCompiler.compile(resource(filter, type), Map.of(filter, value)))
            .isInstanceOf(RestReadException.class)
            .hasFieldOrPropertyWithValue("outcome", RestReadOutcome.INVALID_REQUEST);
    }

    @SuppressWarnings("unchecked")
    private ResolvedRestResource resource(String filter, Class<?> type) {
        ResolvedRestResource resource = mock(ResolvedRestResource.class);
        ResolvedRestPath path = new ResolvedRestPath(filter, java.util.List.of(
            new ResolvedRestPath.Segment(filter, FilterRoot.class, type,
                Attribute.PersistentAttributeType.BASIC, false, false)), type, false);
        when(resource.key()).thenReturn(new RestResourceKey("probe", 1));
        when(resource.filters()).thenReturn(Map.of(filter, path));
        return resource;
    }
}
