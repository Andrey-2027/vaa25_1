package org.ip.spec;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.time.LocalDate;
import java.util.Collection;

public final class BaseSpecs {

    private BaseSpecs() {}

    @SuppressWarnings("unchecked")
    public static <T> Specification<T> likeIgnoreCase(String fieldPath, String value) {
        return (Root<T> root, jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            if (value == null || value.isBlank()) {
                return cb.conjunction();
            }
            Expression<String> path = (Expression<String>) getPath(root, fieldPath);
            return cb.like(cb.lower(path), "%" + value.toLowerCase() + "%");
        };
    }

    @SuppressWarnings("unchecked")
    public static <T> Specification<T> equals(String fieldPath, Object value) {
        return (Root<T> root, jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            if (value == null) {
                return cb.conjunction();
            }
            Expression<Object> path = (Expression<Object>) getPath(root, fieldPath);
            return cb.equal(path, value);
        };
    }

    @SuppressWarnings("unchecked")
    public static <T> Specification<T> in(String fieldPath, Collection<?> values) {
        return (Root<T> root, jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            if (values == null || values.isEmpty()) {
                return cb.conjunction();
            }
            Expression<Object> path = (Expression<Object>) getPath(root, fieldPath);
            return path.in(values);
        };
    }

    @SuppressWarnings("unchecked")
    public static <T> Specification<T> between(String fieldPath, LocalDate from, LocalDate to) {
        return (Root<T> root, jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            if (from == null && to == null) {
                return cb.conjunction();
            }
            Expression<LocalDate> path = (Expression<LocalDate>) getPath(root, fieldPath);
            if (from != null && to != null) {
                return cb.between(path, from, to);
            } else if (from != null) {
                return cb.greaterThanOrEqualTo(path, from);
            } else {
                return cb.lessThanOrEqualTo(path, to);
            }
        };
    }

    public static <T> Specification<T> isNull(String fieldPath) {
        return (Root<T> root, jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            Expression<?> path = getPath(root, fieldPath);
            return cb.isNull(path);
        };
    }

    private static Expression<?> getPath(Root<?> root, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        Path<?> path = root.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            path = path.get(parts[i]);
        }
        return path;
    }
}