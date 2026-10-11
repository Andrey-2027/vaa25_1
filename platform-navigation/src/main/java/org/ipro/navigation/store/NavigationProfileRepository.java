package org.ipro.navigation.store;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface NavigationProfileRepository extends Repository<NavigationProfileEntity, Long> {
    List<NavigationProfileEntity> findAllByOrderByNameAscIdAsc();
    Optional<NavigationProfileEntity> findById(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from NavigationProfileStore p where p.id = :id")
    Optional<NavigationProfileEntity> lockById(@Param("id") long id);
    NavigationProfileEntity saveAndFlush(NavigationProfileEntity profile);
    void flush();
}
