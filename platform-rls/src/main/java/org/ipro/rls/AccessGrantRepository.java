package org.ipro.rls;

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

    /**
     * Прямые права пользователя по измерению (subjectType = USER, subjectKey = username).
     * dimension = "*" — зарезервированное значение "любое измерение" (полный доступ,
     * например у ADMIN) — попадает в тот же запрос, без отдельной ветки в AccessService.
     */
    @Query("select g from AccessGrant g where g.dimension in (:dimension, '*') " +
        "and g.subjectType = org.ipro.rls.AccessGrant.SubjectType.USER and g.subjectKey = :username")
    List<AccessGrant> findUserGrants(@Param("dimension") String dimension, @Param("username") String username);

    /** Права через роли по измерению (subjectType = ROLE, subjectKey = Role.getName()); см. findUserGrants про "*". */
    @Query("select g from AccessGrant g where g.dimension in (:dimension, '*') " +
        "and g.subjectType = org.ipro.rls.AccessGrant.SubjectType.ROLE and g.subjectKey in :roleNames")
    List<AccessGrant> findRoleGrants(@Param("dimension") String dimension, @Param("roleNames") Collection<String> roleNames);

    /**
     * ТОЧНОЕ совпадение по dimension (без "*") — для админ-экрана редактирования грантов
     * (см. AccessGrantAdminService). В отличие от findUserGrants/findRoleGrants (для
     * вычисления ЭФФЕКТИВНОГО доступа, где "*" — намеренно тот же результат, что и точное
     * измерение), здесь нужны СТРОКИ конкретно этого измерения — иначе строка-wildcard
     * "*" другого назначения попала бы в редактируемую матрицу по журналам.
     */
    List<AccessGrant> findBySubjectTypeAndSubjectKeyAndDimension(
        AccessGrant.SubjectType subjectType, String subjectKey, String dimension);

    /**
     * Сколько грантов вообще существует по измерению (без "*") — для bootstrap-правила
     * AccessService.isNewDimensionValueAllowed: "разметка измерения ещё не началась".
     */
    long countByDimension(String dimension);
}