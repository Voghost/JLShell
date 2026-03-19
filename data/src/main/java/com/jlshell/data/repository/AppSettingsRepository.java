package com.jlshell.data.repository;

import java.util.Optional;

import com.jlshell.data.entity.AppSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingsRepository extends JpaRepository<AppSettingsEntity, String> {
    Optional<AppSettingsEntity> findByKey(String key);
}
