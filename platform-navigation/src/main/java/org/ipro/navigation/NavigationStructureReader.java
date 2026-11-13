package org.ipro.navigation;

import org.ipro.navigation.store.NavigationEntryRepository;
import org.ipro.navigation.store.NavigationSectionRepository;
import org.ipro.navigation.store.NavigationStructureRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class NavigationStructureReader {
    private final NavigationStructureRepository structure;
    private final NavigationSectionRepository sections;
    private final NavigationEntryRepository entries;
    private final TransactionTemplate transaction;

    public NavigationStructureReader(NavigationStructureRepository structure, NavigationSectionRepository sections,
                                     NavigationEntryRepository entries, PlatformTransactionManager manager) {
        this.structure = structure;
        this.sections = sections;
        this.entries = entries;
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public NavigationStructure current() {
        return transaction.execute(status -> {
            var state = structure.findBySingletonKey(1)
                .orElseThrow(() -> new IllegalStateException("Структура меню ещё не собрана"));
            return new NavigationStructure(state.getVersion(), state.getSeededAt(),
                sections.findAll().stream().map(row -> new NavigationStructure.Section(row.getKey(),
                    row.getParent() == null ? null : row.getParent().getKey(), row.getTitle(), row.getIcon(),
                    row.getPosition(), row.getAreaKey())).toList(),
                entries.findAll().stream().map(row -> new NavigationStructure.Entry(row.getKey(),
                    row.getSection().getKey(), row.getFunctionKey(), row.getTitle(), row.getPosition())).toList());
        });
    }
}
