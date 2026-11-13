package org.ipro.navigation.internal;

import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.navigation.NavigationCatalogProvider;
import org.ipro.navigation.NavigationConflictException;
import org.ipro.navigation.NavigationProfile;
import org.ipro.navigation.NavigationUserDirectory;
import org.ipro.navigation.NavigationVisibility;

import org.ipro.navigation.store.NavigationAssignmentEntity;
import org.ipro.navigation.store.NavigationAssignmentRepository;
import org.ipro.navigation.store.NavigationOverrideEntity;
import org.ipro.navigation.store.NavigationOverrideRepository;
import org.ipro.navigation.store.NavigationProfileEntity;
import org.ipro.navigation.store.NavigationProfileRepository;
import org.ipro.navigation.store.NavigationSnapshots;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Named owner operations for INTERNAL_STORE; security checks also apply to direct Java calls. */
public final class DefaultNavigationAdministrationService implements NavigationAdministrationService {
    private final NavigationAdministrationAccess access;
    private final NavigationUserDirectory users;
    private final NavigationCatalogProvider catalog;
    private final NavigationProfileRepository profiles;
    private final NavigationOverrideRepository overrides;
    private final NavigationAssignmentRepository assignments;
    private final PlatformTransactionManager transactions;

    public DefaultNavigationAdministrationService(NavigationAdministrationAccess access, NavigationUserDirectory users,
            NavigationCatalogProvider catalog, NavigationProfileRepository profiles, NavigationOverrideRepository overrides,
            NavigationAssignmentRepository assignments, PlatformTransactionManager transactions) {
        this.access = Objects.requireNonNull(access);
        this.users = Objects.requireNonNull(users);
        this.catalog = Objects.requireNonNull(catalog);
        this.profiles = Objects.requireNonNull(profiles);
        this.overrides = Objects.requireNonNull(overrides);
        this.assignments = Objects.requireNonNull(assignments);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public List<NavigationProfile> listProfiles() {
        requireAdministrator();
        return inTransaction(true, () -> profiles.findAllByOrderByNameAscIdAsc().stream().map(this::snapshot).toList());
    }

    public NavigationProfile profile(long id) {
        requireAdministrator();
        return inTransaction(true, () -> snapshot(profiles.findById(id).orElseThrow(() -> missing(id))));
    }

    public NavigationProfile createProfile(String name) {
        requireAdministrator();
        String valid = name(name);
        return inTransaction(false, () -> snapshot(profiles.saveAndFlush(
            new NavigationProfileEntity(valid, valid.toLowerCase(Locale.ROOT)))));
    }

    public NavigationProfile renameProfile(long id, long expectedVersion, String name) {
        requireAdministrator();
        String valid = name(name);
        return inTransaction(false, () -> {
            var profile = locked(id, expectedVersion);
            profile.rename(valid, valid.toLowerCase(Locale.ROOT));
            profiles.flush();
            return snapshot(profile);
        });
    }

    /** Replaces known overrides, retains stale stored keys; DEFAULT is not persisted. */
    public NavigationProfile saveProfile(long id, long expectedVersion, Map<String, NavigationVisibility> values) {
        requireAdministrator();
        Map<String, NavigationVisibility> input = Map.copyOf(values);
        return inTransaction(false, () -> {
            var profile = locked(id, expectedVersion);
            var structureKeys = catalog.get().keys();
            var before = NavigationSnapshots.snapshot(profile, overrides, structureKeys);
            Map<String, NavigationVisibility> replacement = new TreeMap<>();
            before.staleKeys().forEach(key -> replacement.put(key, before.overrides().get(key)));
            input.forEach((key, visibility) -> {
                if (!structureKeys.contains(key)) {
                    if (!before.staleKeys().contains(key) || before.overrides().get(key) != visibility) {
                        throw new IllegalArgumentException("Unknown navigation target: " + key);
                    }
                } else if (visibility != NavigationVisibility.DEFAULT) {
                    replacement.put(key, visibility);
                }
            });
            overrides.removeForProfile(id);
            overrides.saveAll(replacement.entrySet().stream()
                .map(entry -> new NavigationOverrideEntity(profile, entry.getKey(), entry.getValue())).toList());
            profile.touch();
            profiles.flush();
            return NavigationSnapshots.snapshot(profile, overrides, structureKeys);
        });
    }

    public NavigationProfile resetProfile(long id, long expectedVersion) {
        requireAdministrator();
        return inTransaction(false, () -> {
            var profile = locked(id, expectedVersion);
            overrides.removeForProfile(id);
            profile.touch();
            profiles.flush();
            return snapshot(profile);
        });
    }

    public List<NavigationUserDirectory.UserOption> lookupUsers(String term, int limit) {
        requireAdministrator();
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("User lookup limit must be 1..50");
        return List.copyOf(users.lookup(Objects.requireNonNull(term), limit).stream().limit(limit).toList());
    }

    public void assignUser(String userKey, long profileId) {
        requireAdministrator();
        String key = userKey(userKey);
        inTransaction(false, () -> {
            if (!users.exists(key)) throw new IllegalArgumentException("Unknown user: " + key);
            var profile = profiles.findById(profileId).orElseThrow(() -> missing(profileId));
            var assignment = assignments.findByUserKey(key)
                .orElseGet(() -> new NavigationAssignmentEntity(key, profile));
            assignment.assign(profile);
            assignments.saveAndFlush(assignment);
            return null;
        });
    }

    public void unassignUser(String userKey) {
        requireAdministrator();
        String key = userKey(userKey);
        inTransaction(false, () -> { assignments.findByUserKey(key).ifPresent(assignments::delete); return null; });
    }

    public Optional<Long> assignmentForUser(String userKey) {
        requireAdministrator();
        String key = userKey(userKey);
        return inTransaction(true, () -> assignments.findByUserKey(key).map(row -> row.getProfile().getId()));
    }

    private NavigationProfileEntity locked(long id, long expectedVersion) {
        var profile = profiles.lockById(id).orElseThrow(() -> missing(id));
        if (profile.getVersion() != expectedVersion) {
            throw new NavigationConflictException("Profile has changed; reload before saving: " + id);
        }
        return profile;
    }

    private NavigationProfile snapshot(NavigationProfileEntity profile) {
        return NavigationSnapshots.snapshot(profile, overrides, catalog.get().keys());
    }

    private void requireAdministrator() {
        if (!access.isAdministrator()) throw new AccessDeniedException("Navigation administration requires ROLE_ADMIN");
    }

    private <T> T inTransaction(boolean readOnly, Supplier<T> operation) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(readOnly);
        if (readOnly) transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            return transaction.execute(status -> operation.get());
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException conflict) {
            throw new NavigationConflictException("Navigation data conflict; reload or choose a unique profile name", conflict);
        }
    }

    private static IllegalArgumentException missing(long id) {
        return new IllegalArgumentException("Navigation profile does not exist: " + id);
    }

    private static String name(String name) {
        String valid = Objects.requireNonNull(name, "profile name").strip();
        if (valid.isEmpty() || valid.length() > 120 || valid.toLowerCase(Locale.ROOT).length() > 120) {
            throw new IllegalArgumentException("Profile name must contain 1..120 characters");
        }
        return valid;
    }

    static String userKey(String key) {
        if (key == null || key.isBlank() || key.length() > 190 || !key.equals(key.strip())) {
            throw new IllegalArgumentException("A stable user key of 1..190 characters is required");
        }
        return key;
    }
}
