package org.ip.repository;

import org.ip.model.UserFormSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserFormSettingsRepository extends JpaRepository<UserFormSettings, Long> {

    Optional<UserFormSettings> findByUsernameAndSettingKey(String username, String settingKey);

    void deleteByUsernameAndSettingKey(String username, String settingKey);
}
