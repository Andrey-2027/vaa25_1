package org.ipro.navigation.store;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface NavigationOverrideRepository extends Repository<NavigationOverrideEntity, Long> {
    List<NavigationOverrideEntity> findByProfile_Id(long profileId);
    <S extends NavigationOverrideEntity> List<S> saveAll(Iterable<S> overrides);
    @Modifying
    @Query("delete from NavigationOverrideStore o where o.profile.id = :id")
    void removeForProfile(@Param("id") long id);
}
