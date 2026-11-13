package org.ipro.navigation.store;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface NavigationStructureRepository extends JpaRepository<NavigationStructureEntity, Long> {
    Optional<NavigationStructureEntity> findBySingletonKey(int singletonKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select structure from NavigationStructureEntity structure where structure.singletonKey = 1")
    Optional<NavigationStructureEntity> lockSingleton();
}
