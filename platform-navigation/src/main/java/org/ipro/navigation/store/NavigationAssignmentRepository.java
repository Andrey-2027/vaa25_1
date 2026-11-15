package org.ipro.navigation.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NavigationAssignmentRepository extends JpaRepository<NavigationAssignmentEntity, Long> {
    Optional<NavigationAssignmentEntity> findByUserKey(String userKey);
    List<NavigationAssignmentEntity> findByMenu_Id(long menuId);
    List<NavigationAssignmentEntity> findByUserKeyIn(java.util.Set<String> keys);
    long countByMenu_Id(long menuId);
}
