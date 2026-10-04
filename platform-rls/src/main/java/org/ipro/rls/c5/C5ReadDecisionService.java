package org.ipro.rls.c5;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsRoleResolver;

import java.util.List;
import java.util.Objects;

/**
 * Single implementation of the C5 exact/global/read decision (F-REST-READ-3, З2).
 *
 * <p>Rules fixed by the C5 API-review for this slice:</p>
 * <ul>
 * <li>only an exact grant of the requested dimension is considered; the RLS wildcard
 *     {@code dimension = "*"} never authorises a C5 read key;</li>
 * <li>only global rows ({@code dimensionValueId == null}) are considered; a value-scoped
 *     RLS row never becomes a global C5 permission;</li>
 * <li>only {@code canRead} counts; {@code canUpdate}/{@code canDelete} alone do not grant
 *     read, and false flags do not create an explicit deny over another subject's allow;</li>
 * <li>a user decision unions direct grants with grants of the roles assigned by
 *     {@link RlsRoleResolver}; a role decision uses exactly that role's own grants, so a
 *     role that is not assigned to the user cannot authorise the user;</li>
 * <li>a storage/provider failure propagates to the caller (deny/error), it is never
 *     converted into an allow or an empty successful result.</li>
 * </ul>
 *
 * <p>Both the server evaluator ({@link DefaultC5PermissionEvaluator}) and the existing
 * grant admin resolve read decisions through this class, so the shown and enforced
 * decisions cannot diverge.</p>
 */
public class C5ReadDecisionService {

    private final AccessGrantRepository grantRepository;
    private final RlsRoleResolver roleResolver;

    public C5ReadDecisionService(AccessGrantRepository grantRepository, RlsRoleResolver roleResolver) {
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository must not be null");
        this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver must not be null");
    }

    /**
     * Whether the user has the C5 read permission by a direct or role grant.
     * Short-circuits on the first allow; used by the server read pipeline.
     */
    public boolean isReadGranted(String dimension, String username) {
        requireDimension(dimension);
        Objects.requireNonNull(username, "username must not be null");
        if (hasExactGlobalReadGrant(AccessGrant.SubjectType.USER, username, dimension)) {
            return true;
        }
        for (String role : roleResolver.rolesOf(username)) {
            if (hasExactGlobalReadGrant(AccessGrant.SubjectType.ROLE, role, dimension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Full effective decision for a USER or ROLE with the sources of the matching allow,
     * used by the grant admin so the shown decision equals the enforced one.
     *
     * <p>For a USER {@code direct} marks the user's own grant and {@code roles} lists every
     * assigned role whose grant allowed; for a ROLE {@code direct} marks that role's own
     * grant and {@code roles} is empty.</p>
     */
    public C5ReadDecision decide(String dimension, AccessGrant.SubjectType subjectType, String subjectKey) {
        requireDimension(dimension);
        Objects.requireNonNull(subjectType, "subjectType must not be null");
        Objects.requireNonNull(subjectKey, "subjectKey must not be null");

        if (subjectType == AccessGrant.SubjectType.ROLE) {
            boolean granted = hasExactGlobalReadGrant(AccessGrant.SubjectType.ROLE, subjectKey, dimension);
            return new C5ReadDecision(granted, granted, List.of());
        }

        boolean direct = hasExactGlobalReadGrant(AccessGrant.SubjectType.USER, subjectKey, dimension);
        List<String> grantingRoles = roleResolver.rolesOf(subjectKey).stream()
            .filter(role -> hasExactGlobalReadGrant(AccessGrant.SubjectType.ROLE, role, dimension))
            .toList();
        return new C5ReadDecision(direct || !grantingRoles.isEmpty(), direct, grantingRoles);
    }

    /** Effective C5 read decision; {@code roles} contains only assigned roles with an allow. */
    public record C5ReadDecision(boolean granted, boolean direct, List<String> roles) {
        public C5ReadDecision {
            roles = List.copyOf(roles);
        }

        public static C5ReadDecision deny() {
            return new C5ReadDecision(false, false, List.of());
        }
    }

    private boolean hasExactGlobalReadGrant(AccessGrant.SubjectType subjectType, String subjectKey,
                                            String dimension) {
        return grantRepository
            .findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
                subjectType, subjectKey, dimension)
            .stream()
            .anyMatch(AccessGrant::isCanRead);
    }

    private static void requireDimension(String dimension) {
        Objects.requireNonNull(dimension, "dimension must not be null");
        if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
        }
    }
}
