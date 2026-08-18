package org.ipro.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SettingValueRepository extends JpaRepository<SettingValue, Long> {

    Optional<SettingValue> findBySettingKeyAndScopeTypeAndScopeId(String settingKey, String scopeType, long scopeId);

    /** Все строки сферы (для админ-экрана, выгрузки, клонирования настроек филиала и т.п.). */
    List<SettingValue> findByScopeTypeAndScopeId(String scopeType, long scopeId);
}