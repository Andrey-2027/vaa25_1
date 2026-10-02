package org.ipro.rest.security;

import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyDescriptor;
import org.ipro.rls.c5.C5PermissionEvaluator;

import java.util.Objects;
import java.util.Set;

/**
 * Validates reference authorization according to pilots 3.4a and 3.4b (F-REST-READ-3 §7.2).
 */
public class RestReferenceAuthorizationValidator {

    private final C5PermissionEvaluator c5Evaluator;
    private final RlsDimensionRegistry dimensionRegistry;
    private final org.ipro.rls.RlsReadGate readGate;

    public RestReferenceAuthorizationValidator(C5PermissionEvaluator c5Evaluator,
                                               RlsDimensionRegistry dimensionRegistry) {
        this(c5Evaluator, dimensionRegistry, null);
    }

    public RestReferenceAuthorizationValidator(C5PermissionEvaluator c5Evaluator,
                                               RlsDimensionRegistry dimensionRegistry,
                                               org.ipro.rls.RlsReadGate readGate) {
        this.c5Evaluator = Objects.requireNonNull(c5Evaluator, "c5Evaluator must not be null");
        this.dimensionRegistry = Objects.requireNonNull(dimensionRegistry, "dimensionRegistry must not be null");
        this.readGate = readGate;
    }

    /**
     * Authorizes a to-one association path under 3.4a (un-RLS target) or 3.4b (row RLS target).
     *
     * @return true if authorized, false otherwise
     */
    public boolean authorizeReferencePath(String username, Class<?> rootType, ResolvedRestPath path) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(rootType, "rootType must not be null");
        Objects.requireNonNull(path, "path must not be null");

        if (path.segments().isEmpty()) {
            return false;
        }

        // 1. Проверяем Attribute Read на root
        String firstSegment = path.segments().getFirst().name();
        if (!c5Evaluator.isAttributeReadPermitted(username, rootType, firstSegment)) {
            return false;
        }

        // Если это скалярный атрибут root (без ассоциаций), достаточно
        if (path.segments().size() == 1 && !path.segments().getFirst().association()) {
            return true;
        }

        // Для to-one ассоциации:
        ResolvedRestPath.Segment associationSegment = path.segments().getFirst();
        Class<?> targetEntityType = associationSegment.javaType();
        String terminalName = path.segments().getLast().name();

        // 2. C5 Entity Read на целевую сущность
        if (!c5Evaluator.isEntityReadPermitted(username, targetEntityType)) {
            return false;
        }

        // 3. C5 Attribute Read на терминал целевой сущности
        if (!c5Evaluator.isAttributeReadPermitted(username, targetEntityType, terminalName)) {
            return false;
        }

        // 4. Проверка защищённости цели (Пилот 3.4a vs 3.4b)
        RlsPolicyDescriptor targetPolicy = dimensionRegistry.policyOf(targetEntityType);
        Set<String> targetCheckOnly = targetPolicy.checkOnlyDimensions();
        Set<String> targetFilterable = targetPolicy.filterableDimensions();

        // 4a. Если у цели есть CHECK_ONLY измерения, её class gate ОБЯЗАН быть проверен (3.4b)
        if (!targetCheckOnly.isEmpty()) {
            if (readGate == null || !readGate.canRead(targetEntityType, username)) {
                return false;
            }
        }

        // 4b. Если у цели нет row RLS (FILTERABLE измерений)
        if (targetFilterable.isEmpty()) {
            // Пилот 3.4a: цель без row RLS (напр. Nomenclature) — C5 проверок на тип, терминал и CHECK_ONLY достаточно!
            return true;
        }

        // 4c. Пилот 3.4b: цель с row RLS (напр. PrdSpec -> Journal)
        // Требуется подтверждённая совместимость защиты с root:
        // Root обязан полностью удовлетворять ВСЕМ filterable-измерениям цели.
        RlsPolicyDescriptor rootPolicy = dimensionRegistry.policyOf(rootType);
        Set<String> rootFilterable = rootPolicy.filterableDimensions();

        if (!rootFilterable.containsAll(targetFilterable)) {
            return false;
        }

        return true;
    }
}
