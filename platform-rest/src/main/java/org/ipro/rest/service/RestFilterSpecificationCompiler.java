package org.ipro.rest.service;

import jakarta.persistence.criteria.Path;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.springframework.data.jpa.domain.Specification;

import java.util.Map;
import java.util.UUID;

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
        if (targetType.isInstance(value)) {
            return value;
        }
        if (value instanceof String str) {
            try {
                if (targetType == Long.class || targetType == long.class) {
                    return Long.valueOf(str);
                } else if (targetType == Integer.class || targetType == int.class) {
                    return Integer.valueOf(str);
                } else if (targetType == Short.class || targetType == short.class) {
                    return Short.valueOf(str);
                } else if (targetType == Boolean.class || targetType == boolean.class) {
                    return Boolean.valueOf(str);
                } else if (targetType == UUID.class) {
                    return UUID.fromString(str);
                }
            } catch (Exception ex) {
                throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                    "Invalid value format for filter '" + filterName + "': cannot convert '" + str + "' to " + targetType.getSimpleName());
            }
        }
        if (Number.class.isAssignableFrom(targetType) && value instanceof Number num) {
            if (targetType == Long.class) return num.longValue();
            if (targetType == Integer.class) return num.intValue();
            if (targetType == Short.class) return num.shortValue();
            if (targetType == Double.class) return num.doubleValue();
            if (targetType == Float.class) return num.floatValue();
        }
        throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
            "Type mismatch for filter '" + filterName + "': expected " + targetType.getSimpleName()
            + ", got " + value.getClass().getSimpleName());
    }
}