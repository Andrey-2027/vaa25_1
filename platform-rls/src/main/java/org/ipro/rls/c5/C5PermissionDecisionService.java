package org.ipro.rls.c5;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsRoleResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The single C5 decision for "may this subject perform this operation" (ADR-0017 §1, §3, §8, §10).
 *
 * <p>Questions are split by owner and never mixed here: whether an operation exists at all is a
 * capability of the type, which rows are visible or writable is RLS, and whether the subject may
 * perform the operation is this service. The service answers for entity
 * {@code READ}/{@code CREATE}/{@code UPDATE}/{@code DELETE} and for named {@code ACTION} operations;
 * row scope, {@code RlsContext} bypass and the flush boundary stay with RLS.</p>
 *
 * <p>Storage follows ADR-0017 §3: only an exact key with a global row ({@code dimensionValueId ==
 * null}) is considered, so an RLS {@code "*"} wildcard row and a value-scoped RLS row can never
 * authorise an operation. The {@code canUpdate} flag allows CREATE, UPDATE and EXECUTE,
 * {@code canDelete} allows DELETE, and {@code canRead} independently allows READ. A subject's
 * decision unions the subject's own grant with the grants of the roles assigned to it by
 * {@link RlsRoleResolver}; a role that is not assigned cannot authorise.</p>
 *
 * <p>Read decisions delegate to the existing {@link C5ReadDecisionService} unchanged, so read
 * behaviour, its sources and its exact/global semantics stay exactly as they were. A storage or
 * provider failure propagates; it is never converted into an allow or an empty success. Unknown
 * definitions and unsupported operations are configuration errors, not denials.</p>
 *
 * <p>{@link #decide(String, String, C5Operation)} answers enforcement and short-circuits on the
 * first allowing source. {@link #decideForSubject(AccessGrant.SubjectType, String, String,
 * C5Operation)} answers the path that <b>shows</b> rights: it spells the subject out (that path
 * offers roles as subjects just like users) and lists every granting source. Both ask the same
 * rules of the same store, so a shown decision cannot be a second, more generous reading of the
 * grants than the enforced one.</p>
 */
public class C5PermissionDecisionService {

    private final AccessGrantRepository grantRepository;
    private final RlsRoleResolver roleResolver;
    private final C5OperationPermissionRegistry operationRegistry;
    private final C5ReadDecisionService readDecisionService;

    public C5PermissionDecisionService(AccessGrantRepository grantRepository,
                                       RlsRoleResolver roleResolver,
                                       C5OperationPermissionRegistry operationRegistry,
                                       C5ReadDecisionService readDecisionService) {
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository must not be null");
        this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver must not be null");
        this.operationRegistry = Objects.requireNonNull(operationRegistry,
            "operationRegistry must not be null");
        this.readDecisionService = Objects.requireNonNull(readDecisionService,
            "readDecisionService must not be null");
    }

    /** Decides one key/operation pair for a subject. */
    public C5PermissionDecision decide(String username, String key, C5Operation operation) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        if (operation == C5Operation.READ) {
            return decideRead(username, C5PermissionKeys.readKey(key));
        }
        if (key.startsWith("ATTR:")) {
            throw new C5PermissionConfigurationException("Field permissions have been removed; use entity operations");
        }
        if (username == null || username.isBlank()) {
            return C5PermissionDecision.denied(C5DecisionReason.UNAUTHENTICATED, key, operation);
        }
        // The definition is checked before any grant is read: an unknown key is a configuration
        // error even for a subject that holds nothing.
        operationRegistry.requireSupports(key, operation);
        if (requiresRead(key, operation) && !decideRead(username, key).allowed()) {
            return C5PermissionDecision.denied(denialReason(key), key, operation);
        }
        if (grantAllows(globalGrants(AccessGrant.SubjectType.USER, username, key), operation)) {
            return C5PermissionDecision.permitted(key, operation, List.of("прямой"));
        }
        for (String role : roleResolver.rolesOf(username)) {
            if (grantAllows(roleGrants(role, key), operation)) {
                return C5PermissionDecision.permitted(key, operation, List.of("роль: " + role));
            }
        }
        return C5PermissionDecision.denied(denialReason(key), key, operation);
    }

    /**
     * Full effective decision for a USER or a ROLE — every source of the allow, not only the first
     * one sufficient for enforcement — for the path that shows and explains rights (the grant
     * admin; see the class note on sources). The subject is spelled out because that path offers
     * roles as subjects just like users, and because a ROLE decision must use exactly that role's
     * own grant: a role that is not assigned to a user can never authorise the user.
     *
     * <p>The rules are the ones enforcement asks, not a second reading of the store: an exact key
     * with a global row ({@code dimensionValueId == null}), the flag the operation maps to by
     * ADR-0017 §3, and assigned roles only. An RLS {@code "*"} row and a value-scoped row are not
     * consulted here either — showing rights must not be more permissive than deciding them.</p>
     */
    public C5PermissionDecision decideForSubject(AccessGrant.SubjectType subjectType, String subjectKey,
                                                 String key, C5Operation operation) {
        Objects.requireNonNull(subjectType, "subjectType must not be null");
        Objects.requireNonNull(subjectKey, "subjectKey must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        if (operation == C5Operation.READ) {
            return decideReadForSubject(subjectType, subjectKey, C5PermissionKeys.readKey(key));
        }
        if (key.startsWith("ATTR:")) {
            throw new C5PermissionConfigurationException("Field permissions have been removed; use entity operations");
        }
        if (subjectKey.isBlank()) {
            return C5PermissionDecision.denied(C5DecisionReason.UNAUTHENTICATED, key, operation);
        }
        // The definition is checked before any grant is read, exactly as in decide().
        operationRegistry.requireSupports(key, operation);
        if (requiresRead(key, operation) && !decideReadForSubject(subjectType, subjectKey, key).allowed()) {
            return C5PermissionDecision.denied(denialReason(key), key, operation);
        }
        List<String> sources = new ArrayList<>();
        if (grantAllows(globalGrants(subjectType, subjectKey, key), operation)) {
            sources.add(sourceOf(subjectType, subjectKey));
        }
        if (subjectType == AccessGrant.SubjectType.USER) {
            for (String role : roleResolver.rolesOf(subjectKey)) {
                if (grantAllows(roleGrants(role, key), operation)) {
                    sources.add("роль: " + role);
                }
            }
        }
        if (sources.isEmpty()) {
            return C5PermissionDecision.denied(denialReason(key), key, operation);
        }
        return C5PermissionDecision.permitted(key, operation, sources);
    }

    /** Decides an entity operation, choosing the key spelling so callers never spell it themselves. */
    public C5PermissionDecision decideEntity(String username, Class<?> entityType, C5Operation operation) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        if (operation == C5Operation.EXECUTE) {
            throw new IllegalArgumentException("EXECUTE is a named operation, not an entity operation");
        }
        return decide(username, C5PermissionKeys.entity(entityType), operation);
    }

    /** Decides execution of a named owner operation. */
    public C5PermissionDecision decideAction(String username, String permissionId) {
        return decide(username, C5PermissionKeys.action(permissionId), C5Operation.EXECUTE);
    }

    /** Requires one key/operation pair; a denial is thrown as a security exception. */
    public void require(String username, String key, C5Operation operation) {
        C5PermissionDecision decision = decide(username, key, operation);
        if (!decision.allowed()) {
            throw denied(decision);
        }
    }

    /** Requires an entity operation. */
    public void requireEntity(String username, Class<?> entityType, C5Operation operation) {
        C5PermissionDecision decision = decideEntity(username, entityType, operation);
        if (!decision.allowed()) {
            throw new C5AccessDeniedException(decision, entityType);
        }
    }

    /** Requires execution of a named owner operation. */
    public void requireAction(String username, String permissionId) {
        C5PermissionDecision decision = decideAction(username, permissionId);
        if (!decision.allowed()) {
            throw denied(decision);
        }
    }

    private C5PermissionDecision decideRead(String username, String key) {
        if (username == null || username.isBlank()) {
            return C5PermissionDecision.denied(C5DecisionReason.UNAUTHENTICATED, key, C5Operation.READ);
        }
        return decideReadForSubject(AccessGrant.SubjectType.USER, username, key);
    }

    /** Read decision for a spelled-out subject; role semantics stay in {@link C5ReadDecisionService}. */
    private C5PermissionDecision decideReadForSubject(AccessGrant.SubjectType subjectType,
                                                      String subjectKey, String key) {
        if (subjectKey.isBlank()) {
            return C5PermissionDecision.denied(C5DecisionReason.UNAUTHENTICATED, key, C5Operation.READ);
        }
        C5ReadDecisionService.C5ReadDecision decision = readDecisionService.decide(key, subjectType, subjectKey);
        if (!decision.granted()) {
            return C5PermissionDecision.denied(denialReason(key), key, C5Operation.READ);
        }
        List<String> sources = new ArrayList<>();
        if (decision.direct()) {
            sources.add(sourceOf(subjectType, subjectKey));
        }
        decision.roles().forEach(role -> sources.add("роль: " + role));
        return C5PermissionDecision.permitted(key, C5Operation.READ, sources);
    }

    /** Wording of a subject's own grant — the same one {@code AccessService} and the admin show. */
    private static String sourceOf(AccessGrant.SubjectType subjectType, String subjectKey) {
        return subjectType == AccessGrant.SubjectType.ROLE ? "роль: " + subjectKey : "прямой";
    }

    private List<AccessGrant> globalGrants(AccessGrant.SubjectType subjectType, String subjectKey,
                                           String key) {
        return grantRepository.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            subjectType, subjectKey, key);
    }

    private List<AccessGrant> roleGrants(String role, String key) {
        return globalGrants(AccessGrant.SubjectType.ROLE, role, key);
    }

    /** ADR-0017 §3: canUpdate allows CREATE/UPDATE/EXECUTE, canDelete allows DELETE. */
    public List<AccessGrant> contributions(String key, C5Operation operation, List<AccessGrant> subjectGrants) {
        if (operation == C5Operation.READ) return readDecisionService.contributions(key, subjectGrants);
        operationRegistry.requireSupports(key, operation);
        if (requiresRead(key, operation) && readDecisionService.contributions(key, subjectGrants).isEmpty()) return List.of();
        return subjectGrants.stream().filter(grant -> matchesGrant(grant, key, operation)).toList();
    }

    private static boolean requiresRead(String key, C5Operation operation) {
        return key.startsWith("ENTITY:") && (operation == C5Operation.UPDATE || operation == C5Operation.DELETE);
    }

    /** Exact/global rule shared with the read owner and the batched explanation path. */
    public static boolean matchesGrant(AccessGrant grant, String key, C5Operation operation) {
        return key.equals(grant.getDimension()) && grant.getDimensionValueId() == null
            && org.ipro.rls.RlsGrantMatching.flagAllows(grant, operation);
    }

    private static boolean grantAllows(List<AccessGrant> grants, C5Operation operation) {
        // The repository already restricts this enforcement path to exact/global rows.
        return grants.stream().anyMatch(grant -> org.ipro.rls.RlsGrantMatching.flagAllows(grant, operation));
    }

    private static C5DecisionReason denialReason(String key) {
        if (key.startsWith("ACTION:")) {
            return C5DecisionReason.ACTION_PERMISSION_DENIED;
        }
        return C5DecisionReason.ENTITY_PERMISSION_DENIED;
    }

    private static C5AccessDeniedException denied(C5PermissionDecision decision) {
        return new C5AccessDeniedException(decision);
    }
}
