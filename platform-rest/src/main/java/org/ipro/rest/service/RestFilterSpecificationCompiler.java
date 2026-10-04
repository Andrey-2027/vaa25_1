package org.ipro.rest.service;

import jakarta.persistence.criteria.Path;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.springframework.data.jpa.domain.Specification;

import java.util.Map;

/**
 * Compiles a safe JPA Specification from declared REST filters (F-REST-READ-3 В§6.3).
 * Supports only EQUALS operator for declared scalar and ManyToOne ID paths.
 */
public final class RestFilterSpecificationCompiler {

    private RestFilterSpecificationCompiler() {
    }

    public static <T> Specification<T> compile(ResolvedRestResource resource, Map<String, Object> filterValues) {
        if (filterValues == null || filterValues.isEmpty()) {
            return null;
        }

        Specification<T> combined = null;
        for (Map.Entry<String, Object> entry : filterValues.entrySet()) {
            String filterName = entry.getKey();
            Object rawValue = entry.getValue();

            ResolvedRestPath path = resource.filters().get(filterName);
            if (path == null) {
                throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                    "Unknown filter '" + filterName + "' on resource " + resource.key());
            }

            if (rawValue == null) {
                throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                    "Null filter value is not supported for filter '" + filterName + "'");
            }

            Object value = coerceAndValidate(rawValue, path.terminalType(), filterName);

            Specification<T> spec = (root, query, cb) -> {
                Path<?> jpaPath = root;
                for (ResolvedRestPath.Segment segment : path.segments()) {
                    jpaPath = jpaPath.get(segment.name());
                }
                return cb.equal(jpaPath, value);
            };

            combined = (combined == null) ? spec : combined.and(spec);
        }

        return combined;
    }

    private static Object coerceAndValidate(Object value, Class<?> targetType, String filterName) {
        Class<?> expectedType = boxed(targetType);
        if (expectedType.isInstance(value)) {
            return value;
        }
        throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
            "Type mismatch for filter '" + filterName + "': expected " + expectedType.getSimpleName()
            + ", got " + value.getClass().getSimpleName());
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
