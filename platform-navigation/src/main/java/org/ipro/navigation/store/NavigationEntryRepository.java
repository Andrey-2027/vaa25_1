package org.ipro.navigation.store;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NavigationEntryRepository extends JpaRepository<NavigationEntryEntity, Long> {
    java.util.List<NavigationEntryEntity> findByMenu_Id(long menuId);

    @org.springframework.data.jpa.repository.Query("""
        select entry.functionKey as functionKey, entry.menu.id as menuId, count(entry) as total
        from NavigationEntryEntity entry group by entry.functionKey, entry.menu.id
        """)
    java.util.List<Placement> placements();

    interface Placement {
        String getFunctionKey();
        long getMenuId();
        long getTotal();
    }
}
