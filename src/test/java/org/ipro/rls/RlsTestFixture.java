package org.ipro.rls;

import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.ipro.rls.c5.C5PermissionDefinition;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Test-data helper that uses the real authorization path instead of opening an RLS bypass.
 * Each invocation gets an authenticated subject with an explicit wildcard grant.
 *
 * <p><b>Операции выдаются отдельно (ADR-0017).</b> Wildcard {@code "*"} остаётся построчным
 * обходом и операции не авторизует, поэтому синтетический суперпользователь получает
 * материализованные гранты на каждое зарегистрированное определение операции: то же, что
 * требуется реальному ADMIN в первичной выдаче. Определения видны фикстуре только после
 * {@link #bindRegisteredOperations}; без привязки она выдаёт только построчный доступ, и
 * запись в тесте отказывает — fail-closed, а не молчаливое ослабление.</p>
 */
public final class RlsTestFixture {

    private static volatile C5OperationPermissionRegistry registeredOperations;

    private RlsTestFixture() {
    }

    /** Bound by the test auto-configuration that sees the real C5 operation registry. */
    static void bindRegisteredOperations(C5OperationPermissionRegistry registry) {
        registeredOperations = registry;
    }

    /**
     * C5-оценщик для тестов, которым нужен только сам гейт чтения, а не решение: чтение разрешено.
     *
     * <p>До C5.7 гейт читал флаг CHECK_ONLY короткого ключа через {@code AccessService}, и тесты
     * передавали туда свою службу доступа. Теперь гейт спрашивает C5 о чтении сущности, поэтому
     * тестам, которые проверяют не гейт (каталоги метаданных, refresher параметров, CTE-спайки),
     * нужен разрешающий ответ, а не второй набор фикстур прав.</p>
     */
    public static C5PermissionEvaluator allowingC5ReadEvaluator() {
        return new C5PermissionEvaluator() {
            @Override
            public boolean isApiPermitted(String username, String resourceKey, int major, String operation) {
                return true;
            }

            @Override
            public boolean isEntityReadPermitted(String username, Class<?> entityType) {
                return true;
            }
        };
    }

    public static void runAsSuperuser(AccessGrantRepository grants, Runnable action) {
        callAsSuperuser(grants, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Суперпользователь с явными read-ключами: canonical update/delete читает исходную строку,
     * поэтому одних операционных прав мало — read-ключ и ключ операций это разные строки (ADR-0017 §3).
     */
    public static <T> T callAsSuperuserWithRead(AccessGrantRepository grants,
                                                Class<?>[] readTypes, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        String username = authenticateAsSuperuser(grants);
        try {
            grantC5EntityRead(grants, username, readTypes);
            return action.get();
        } finally {
            clearSuperuser(grants, previous, username);
        }
    }

    /** Runnable-вариант {@link #callAsSuperuserWithRead}. */
    public static void runAsSuperuserWithRead(AccessGrantRepository grants,
                                              Class<?>[] readTypes, Runnable action) {
        callAsSuperuserWithRead(grants, readTypes, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAsSuperuser(AccessGrantRepository grants, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        String username = authenticateAsSuperuser(grants);
        try {
            return action.get();
        } finally {
            clearSuperuser(grants, previous, username);
        }
    }

    private static void clearSuperuser(AccessGrantRepository grants, SecurityContext previous,
                                       String username) {
        try {
            grants.deleteAll(grants.findBySubjectTypeAndSubjectKeyAndDimension(
                AccessGrant.SubjectType.USER, username, "*"));
            grants.flush();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /** Authenticate the current test thread until its context is explicitly cleared. */
    public static String authenticateAsSuperuser(AccessGrantRepository grants) {
        String username = "rls-test-" + UUID.randomUUID();
        SecurityContext testContext = SecurityContextHolder.createEmptyContext();
        testContext.setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        SecurityContextHolder.setContext(testContext);

        AccessGrant wildcard = new AccessGrant();
        wildcard.setSubjectType(AccessGrant.SubjectType.USER);
        wildcard.setSubjectKey(username);
        wildcard.setDimension("*");
        wildcard.setCanRead(true);
        wildcard.setCanUpdate(true);
        wildcard.setCanDelete(true);
        grants.saveAndFlush(wildcard);
        grantRegisteredOperations(grants, username);
        return username;
    }

    /**
     * Материализует права на операции для синтетического суперпользователя: одна строка на
     * определение, как это делает первичная выдача в приложении. Ключи read-ключей и
     * операционные ключи не смешиваются — read по-прежнему выдаётся явно и отдельно.
     */
    private static void grantRegisteredOperations(AccessGrantRepository grants, String username) {
        C5OperationPermissionRegistry registry = registeredOperations;
        if (registry == null) {
            return;
        }
        for (C5PermissionDefinition definition : registry.definitions()) {
            Set<C5Operation> operations = definition.operations();
            boolean update = operations.stream().anyMatch(operation ->
                operation == C5Operation.CREATE || operation == C5Operation.UPDATE
                    || operation == C5Operation.EXECUTE);
            boolean delete = operations.contains(C5Operation.DELETE);
            if (!update && !delete) {
                continue;
            }
            AccessGrant grant = new AccessGrant();
            grant.setSubjectType(AccessGrant.SubjectType.USER);
            grant.setSubjectKey(username);
            grant.setDimension(definition.key());
            grant.setDimensionValueId(null);
            grant.setCanRead(false);
            grant.setCanUpdate(update);
            grant.setCanDelete(delete);
            grants.save(grant);
        }
        grants.flush();
    }

    /**
     * Выдаёт операции записи типа отдельным субъектом: одна строка на ключ операций, как это
     * делает первичная выдача. Нужно там, где субъект проверки не суперпользователь фикстуры,
     * а создаёт строку через canonical create — CREATE авторизуется этим ключом, а не построчным
     * {@code "*"} (ADR-0017 §3).
     */
    public static void grantC5EntityOperations(AccessGrantRepository grants, String username,
                                               Class<?>... entityTypes) {
        for (Class<?> entityType : entityTypes) {
            AccessGrant operations = entityGrant(grants, username, C5PermissionKeys.entity(entityType));
            operations.setSubjectType(AccessGrant.SubjectType.USER);
            operations.setSubjectKey(username);
            operations.setDimension(C5PermissionKeys.entityOperations(entityType));
            operations.setDimensionValueId(null);
            operations.setCanUpdate(true);
            grants.save(operations);
        }
        grants.flush();
    }

    /**
     * Выдаёт именованную операцию владельца: право выражается той же строкой-грантом, где
     * {@code canUpdate} разрешает {@code EXECUTE} (ADR-0017 §3, ADR-0019 §1). Ключ берётся у
     * определения, а не пишется руками — незарегистрированный ключ не выдаётся молча.
     */
    public static void grantC5Action(AccessGrantRepository grants, String username,
                                     String permissionId) {
        AccessGrant action = new AccessGrant();
        action.setSubjectType(AccessGrant.SubjectType.USER);
        action.setSubjectKey(username);
        action.setDimension(C5PermissionKeys.action(permissionId));
        action.setDimensionValueId(null);
        action.setCanRead(false);
        action.setCanUpdate(true);
        grants.save(action);
        grants.flush();
    }

    /** Add explicit read grants for canonical-read tests; an RLS wildcard is a separate permission. */
    public static void grantC5EntityRead(AccessGrantRepository grants, String username,
                                         Class<?>... entityTypes) {
        for (Class<?> entityType : entityTypes) {
            AccessGrant entityRead = entityGrant(grants, username, C5PermissionKeys.entity(entityType));
            entityRead.setSubjectType(AccessGrant.SubjectType.USER);
            entityRead.setSubjectKey(username);
            entityRead.setDimension(C5PermissionKeys.entityRead(entityType));
            entityRead.setDimensionValueId(null);
            entityRead.setCanRead(true);
            grants.save(entityRead);
        }
        grants.flush();
    }

    private static AccessGrant entityGrant(AccessGrantRepository grants, String username, String key) {
        return grants.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, username, key).stream().findFirst().orElseGet(() -> {
                AccessGrant grant = new AccessGrant(); grant.setCanRead(false); return grant;
            });
    }
}
