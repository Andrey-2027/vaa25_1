package org.ip.repository;

import org.ip.model.AccessGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AccessGrantRepository
        extends JpaRepository<AccessGrant, Long>, JpaSpecificationExecutor<AccessGrant> {

    /** Прямые права пользователя по измерению (subjectType = USER, subjectKey = username). */
    @Query("select g from AccessGrant g where g.dimension = :dimension " +
        "and g.subjectType = org.ip.model.AccessGrant.SubjectType.USER and g.subjectKey = :username")
    List<AccessGrant> findUserGrants(@Param("dimension") String dimension, @Param("username") String username);

    /** Права через роли по измерению (subjectType = ROLE, subjectKey = Role.getName()). */
    @Query("select g from AccessGrant g where g.dimension = :dimension " +
        "and g.subjectType = org.ip.model.AccessGrant.SubjectType.ROLE and g.subjectKey in :roleNames")
    List<AccessGrant> findRoleGrants(@Param("dimension") String dimension, @Param("roleNames") Collection<String> roleNames);
}
