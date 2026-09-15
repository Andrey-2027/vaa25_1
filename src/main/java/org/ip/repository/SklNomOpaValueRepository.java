package org.ip.repository;

import org.ip.model.AttributeValue;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SklNomOpaValueRepository extends JpaRepository<SklNomOpaValue, Long> {

    @EntityGraph(attributePaths = {"attrType", "value"})
    List<SklNomOpaValue> findBySetOrderByAttrTypeId(SklNomOpa set);

    /**
     * Шапки, содержащие значение (для пересборки displayName при переименовании значения).
     * DISTINCT — набор может содержать значение только один раз, но JPA требует distinct
     * при join-путях в коллекции; здесь пути ссылочные, distinct оставлен для симметрии.
     */
    @Query("SELECT DISTINCT v.set FROM SklNomOpaValue v WHERE v.value = :value")
    List<SklNomOpa> findSetsContainingValue(@Param("value") AttributeValue value);
}
