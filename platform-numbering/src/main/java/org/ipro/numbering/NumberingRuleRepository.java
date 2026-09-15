package org.ipro.numbering;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NumberingRuleRepository extends JpaRepository<NumberingRule, Long> {

    Optional<NumberingRule> findByEntityClassAndFieldName(String entityClass, String fieldName);
}
