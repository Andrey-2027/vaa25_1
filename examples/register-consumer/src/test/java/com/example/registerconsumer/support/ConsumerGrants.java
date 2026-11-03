package com.example.registerconsumer.support;

import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Explicit disposable test grants. This is application test data, not register infrastructure:
 * the production application only adapts principal/roles/auditor.
 */
public final class ConsumerGrants {

    private ConsumerGrants() {
    }

    /** Sets the authenticated principal used by both C5 decisions and RLS. */
    public static void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    public static void logout() {
        SecurityContextHolder.clearContext();
    }

    /** Entity permission: read + create/update/delete. */
    public static void entityWrite(AccessGrantRepository grants, String user, Class<?> type) {
        grant(grants, user, C5PermissionKeys.entity(type), null, true, true);
    }

    /** Entity permission: read only. */
    public static void entityRead(AccessGrantRepository grants, String user, Class<?> type) {
        grant(grants, user, C5PermissionKeys.entity(type), null, true, false);
    }

    /** Named operation permission: EXECUTE is expressed by canUpdate on the action key. */
    public static void action(AccessGrantRepository grants, String user, String permissionId) {
        grant(grants, user, C5PermissionKeys.action(permissionId), null, false, true);
    }

    /** Row-level permission for a register reference dimension; value is the target id. */
    public static void rowScope(AccessGrantRepository grants, String user, String dimension, Long valueId) {
        grant(grants, user, dimension, valueId, true, true);
    }

    /** Removes every grant of a test principal so each test starts from an explicit state. */
    public static void revokeAll(AccessGrantRepository grants, String user) {
        grants.deleteAll(grants.findAll().stream()
            .filter(grant -> grant.getSubjectType() == AccessGrant.SubjectType.USER)
            .filter(grant -> user.equals(grant.getSubjectKey()))
            .toList());
        grants.flush();
    }

    private static void grant(AccessGrantRepository grants, String user, String dimension, Long valueId,
                              boolean read, boolean write) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(user);
        grant.setDimension(dimension);
        grant.setDimensionValueId(valueId);
        grant.setCanRead(read);
        grant.setCanUpdate(write);
        grant.setCanDelete(write);
        grants.save(grant);
        grants.flush();
    }
}
