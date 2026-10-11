package org.ipro.navigation.store;

import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface NavigationAssignmentRepository extends Repository<NavigationAssignmentEntity, Long> {
    Optional<NavigationAssignmentEntity> findByUserKey(String userKey);
    NavigationAssignmentEntity saveAndFlush(NavigationAssignmentEntity assignment);
    void delete(NavigationAssignmentEntity assignment);
}
