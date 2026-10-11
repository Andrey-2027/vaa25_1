package org.ipro.navigation.internal;

import org.ipro.navigation.NavigationCatalog;
import org.ipro.navigation.NavigationProfile;
import org.ipro.navigation.NavigationProfileReader;
import org.ipro.navigation.NavigationUserDirectory;

import org.ipro.navigation.store.NavigationAssignmentRepository;
import org.ipro.navigation.store.NavigationOverrideRepository;
import org.ipro.navigation.store.NavigationSnapshots;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.Optional;

/** Reads only the current user's assignment. Persistence failures propagate, never mean defaults. */
public final class DefaultNavigationProfileReader implements NavigationProfileReader {
    private final NavigationUserDirectory users;
    private final NavigationAssignmentRepository assignments;
    private final NavigationOverrideRepository overrides;
    private final NavigationCatalog catalog;
    private final PlatformTransactionManager transactions;

    public DefaultNavigationProfileReader(NavigationUserDirectory users, NavigationAssignmentRepository assignments,
            NavigationOverrideRepository overrides, NavigationCatalog catalog, PlatformTransactionManager transactions) {
        this.users = Objects.requireNonNull(users);
        this.assignments = Objects.requireNonNull(assignments);
        this.overrides = Objects.requireNonNull(overrides);
        this.catalog = Objects.requireNonNull(catalog);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public Optional<NavigationProfile> currentProfile() {
        String key = DefaultNavigationAdministrationService.userKey(users.currentUserKey());
        var transaction = new TransactionTemplate(transactions);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> assignments.findByUserKey(key)
            .map(row -> NavigationSnapshots.snapshot(row.getProfile(), overrides, catalog)));
    }
}
