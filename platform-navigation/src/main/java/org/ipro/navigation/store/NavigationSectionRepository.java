package org.ipro.navigation.store;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NavigationSectionRepository extends JpaRepository<NavigationSectionEntity, Long> {
    java.util.List<NavigationSectionEntity> findByMenu_Id(long menuId);
}
