package org.ip.spec;

import org.ip.model.Workshop;
import org.springframework.data.jpa.domain.Specification;

public final class WorkshopSpecs {

    private WorkshopSpecs() {}

    public static Specification<Workshop> byCode(String code) {
        return BaseSpecs.likeIgnoreCase("code", code);
    }

    public static Specification<Workshop> byName(String name) {
        return BaseSpecs.likeIgnoreCase("name", name);
    }

    public static Specification<Workshop> byCodeOrName(String term) {
        return (root, query, cb) -> {
            if (term == null || term.isBlank()) {
                return cb.conjunction();
            }
            String pattern = "%" + term.toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("code")), pattern),
                    cb.like(cb.lower(root.get("name")), pattern)
            );
        };
    }
}