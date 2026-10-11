package org.navigationpilot;

import jakarta.persistence.EntityManager;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ip.model.*;
import org.ip.navigationpilot.Hierarchy;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.data.EntityDataAccess;
import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationDefaultsContributor;
import org.ipro.navigation.NavigationVisibility;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsReadableIdsResolver;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/** Opt-in PostgreSQL fixture, following DataInitializer's explicit grant/canonical write path. */
@Configuration(proxyBeanMethods = false)
@Profile("navigation-pilot & !prod")
public class PilotConfiguration {
    private static final String SEED = "navigation-pilot-seed";

    @Bean NavigationDefaultsContributor pilotNavigationDefaults() {
        return defaults -> defaults
            .subsystem(Hierarchy.Root.class, "pilot-root")
            .subsystem(Hierarchy.Child.class, "pilot-child")
            .list(Hierarchy.Root.class, Hierarchy.RootItem.class, "pilot-root-list")
            .list(Hierarchy.Child.class, Hierarchy.ChildItem.class, "pilot-child-list", false);
    }

    // Supported resolver seam for startup preparation without an HTTP session. Same AccessService
    // decisions, no bypass. The UI-only launcher explicitly disables the unrelated REST web adapter.
    @Bean RlsReadableIdsResolver pilotReadableIdsResolver(AccessService access) {
        return access::getReadableIds;
    }

    @Bean
    @ConditionalOnProperty(name = "app.navigation-pilot.seed", havingValue = "true")
    CommandLineRunner pilotSeed(EntityManager em, PlatformTransactionManager transactions,
            EntityDataAccess data, AccessGrantRepository grants, UserRepository users,
            RoleRepository roles, PasswordEncoder passwords, NavigationAdministrationService navigation,
            @Value("${app.navigation-pilot.password}") String password) {
        return args -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                String database = (String) em.createNativeQuery("select current_database()", String.class).getSingleResult();
                if (!database.matches("navigation_pilot_[a-f0-9]{32}")) {
                    throw new IllegalStateException("Navigation seed requires an isolated navigation_pilot_<UUID> database");
                }
                if (users.count() != 0) {
                    for (String name : List.of("ki-admin", "ki-a", "ki-b")) {
                        if (users.findByUsername(name).isEmpty()) throw new IllegalStateException("Incomplete pilot accounts");
                    }
                    return; // Restarts preserve all administrator edits and assignments.
                }
                if (roles.count() != 0) {
                    throw new IllegalStateException("Pilot preparation requires empty account stores");
                }
                for (Class<?> type : List.of(Role.class, User.class, UnitOfMeasurement.class)) {
                    grant(grants, AccessGrant.SubjectType.USER, SEED, C5PermissionKeys.entityRead(type), true, false);
                    grant(grants, AccessGrant.SubjectType.USER, SEED, C5PermissionKeys.entityOperations(type), false, true);
                }
                for (String action : List.of(UserPasswordChangePermission.PERMISSION_ID, UserRoleAssignmentPermission.PERMISSION_ID)) {
                    grant(grants, AccessGrant.SubjectType.USER, SEED, C5PermissionKeys.action(action), false, true);
                }
                // Same non-admin role and exact same rights for A and B. ReceivingDocument is deliberately denied.
                for (Class<?> type : List.of(UnitOfMeasurement.class, PrdSpec.class)) {
                    grant(grants, AccessGrant.SubjectType.ROLE, "KI_READER", C5PermissionKeys.entityRead(type), true, false);
                }
                for (Class<?> type : List.of(User.class, Role.class, UnitOfMeasurement.class, PrdSpec.class)) {
                    grant(grants, AccessGrant.SubjectType.ROLE, "ADMIN", C5PermissionKeys.entityRead(type), true, false);
                }
                grant(grants, AccessGrant.SubjectType.ROLE, "KI_READER", "JOURNAL", true, false);
                grants.flush();
                var previous = SecurityContextHolder.getContext();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(SEED, "", List.of()));
                SecurityContextHolder.setContext(context);
                try {
                    Role admin = data.create(Role.class, new Role("ADMIN"));
                    Role reader = data.create(Role.class, new Role("KI_READER"));
                    for (String name : List.of("ki-admin", "ki-a", "ki-b")) {
                        User user = new User(name, passwords.encode(password));
                        user.addRole(name.equals("ki-admin") ? admin : reader);
                        data.create(User.class, user);
                    }
                    data.create(UnitOfMeasurement.class, new UnitOfMeasurement("шт", "Пилот — штука", "KI-PC"));
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("ki-admin", "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                    var a = navigation.createProfile("Пилот А — справочники");
                    a = navigation.saveProfile(a.id(), a.version(), Map.of(
                        "item:units-list", NavigationVisibility.SHOW,
                        "item:specifications-list", NavigationVisibility.HIDE,
                        "item:receiving-documents-list", NavigationVisibility.SHOW,
                        "item:pilot-child-list", NavigationVisibility.SHOW));
                    var b = navigation.createProfile("Пилот Б — производство");
                    b = navigation.saveProfile(b.id(), b.version(), Map.of(
                        "item:units-list", NavigationVisibility.HIDE,
                        "item:specifications-list", NavigationVisibility.SHOW,
                        "item:receiving-documents-list", NavigationVisibility.SHOW,
                        "subsystem:pilot-child", NavigationVisibility.HIDE,
                        "item:pilot-child-list", NavigationVisibility.SHOW));
                    navigation.assignUser(users.findByUsername("ki-a").orElseThrow().getId().toString(), a.id());
                    navigation.assignUser(users.findByUsername("ki-b").orElseThrow().getId().toString(), b.id());
                } finally { SecurityContextHolder.setContext(previous); }
            });
            System.out.println("NAVIGATION_PILOT_READY");
        };
    }

    private static void grant(AccessGrantRepository grants, AccessGrant.SubjectType type, String subject,
                              String key, boolean read, boolean update) {
        var grant = new AccessGrant();
        grant.setSubjectType(type); grant.setSubjectKey(subject); grant.setDimension(key);
        grant.setCanRead(read); grant.setCanUpdate(update); grant.setCanDelete(false);
        grants.save(grant);
    }
}
