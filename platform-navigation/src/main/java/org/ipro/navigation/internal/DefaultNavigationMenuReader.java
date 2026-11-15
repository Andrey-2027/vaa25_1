package org.ipro.navigation.internal;

import org.ipro.navigation.NavigationMenuReader;
import org.ipro.navigation.NavigationStructure;
import org.ipro.navigation.NavigationUserDirectory;
import org.ipro.navigation.store.NavigationAssignmentRepository;
import org.ipro.navigation.store.NavigationMenuRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads the assignment and structure within a single database snapshot. */
public final class DefaultNavigationMenuReader implements NavigationMenuReader {
    private final NavigationUserDirectory users;
    private final NavigationAssignmentRepository assignments;
    private final NavigationMenuRepository menus;
    private final NavigationStructureReader structures;
    private final TransactionTemplate read;

    public DefaultNavigationMenuReader(NavigationUserDirectory users, NavigationAssignmentRepository assignments,
            NavigationMenuRepository menus, NavigationStructureReader structures, PlatformTransactionManager manager) {
        this.users = users;
        this.assignments = assignments;
        this.menus = menus;
        this.structures = structures;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public NavigationStructure currentMenu() {
        return read.execute(status -> {
            var key = DefaultNavigationMenuService.userKey(users.currentUserKey());
            var menu = assignments.findByUserKey(key).map(row -> row.getMenu())
                .orElseGet(() -> menus.findByMainKey(1)
                    .orElseThrow(() -> new IllegalStateException("Основное меню ещё не собрано")));
            return structures.read(menu);
        });
    }
}
