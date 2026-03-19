package com.jlshell.data.repository;

import com.jlshell.data.entity.CredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 凭证仓储。
 */
public interface CredentialRepository extends JpaRepository<CredentialEntity, String> {
}
