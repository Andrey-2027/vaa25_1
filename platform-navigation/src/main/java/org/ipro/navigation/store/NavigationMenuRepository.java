package org.ipro.navigation.store;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface NavigationMenuRepository extends JpaRepository<NavigationMenuEntity, Long> {
    List<NavigationMenuEntity> findAllByOrderByPositionAscIdAsc();
    Optional<NavigationMenuEntity> findByMainKey(int mainKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select menu from NavigationMenuEntity menu where menu.mainKey = 1")
    Optional<NavigationMenuEntity> lockMain();
}
