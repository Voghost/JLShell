package com.jlshell.data.dao;

import java.util.List;
import java.util.Optional;

import com.jlshell.data.entity.VaultEntryEntity;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * 凭据库 JDBI DAO。
 */
@RegisterBeanMapper(VaultEntryEntity.class)
public interface VaultEntryDao {

    @SqlQuery("SELECT * FROM credential_vault ORDER BY name ASC")
    List<VaultEntryEntity> findAllOrderByName();

    @SqlQuery("SELECT * FROM credential_vault WHERE project_id = :projectId ORDER BY name ASC")
    List<VaultEntryEntity> findAllByProjectId(@Bind("projectId") String projectId);

    @SqlQuery("SELECT * FROM credential_vault WHERE project_id IS NULL ORDER BY name ASC")
    List<VaultEntryEntity> findAllWithNoProject();

    @SqlQuery("SELECT * FROM credential_vault WHERE id = :id")
    Optional<VaultEntryEntity> findById(@Bind("id") String id);

    @SqlUpdate("INSERT INTO credential_vault (id, name, authentication_type, encryption_mode, " +
            "encrypted_password, encrypted_passphrase, encrypted_key_content, private_key_path, " +
            "project_id, created_at, updated_at) " +
            "VALUES (:id, :name, :authenticationType, :encryptionMode, " +
            ":encryptedPassword, :encryptedPassphrase, :encryptedKeyContent, :privateKeyPath, " +
            ":projectId, :createdAt, :updatedAt)")
    void insert(@BindBean VaultEntryEntity entity);

    @SqlUpdate("UPDATE credential_vault SET name=:name, authentication_type=:authenticationType, " +
            "encryption_mode=:encryptionMode, encrypted_password=:encryptedPassword, " +
            "encrypted_passphrase=:encryptedPassphrase, encrypted_key_content=:encryptedKeyContent, " +
            "private_key_path=:privateKeyPath, project_id=:projectId, " +
            "updated_at=:updatedAt WHERE id=:id")
    void update(@BindBean VaultEntryEntity entity);

    @SqlUpdate("DELETE FROM credential_vault WHERE id = :id")
    void deleteById(@Bind("id") String id);

    @SqlUpdate("UPDATE credential_vault SET project_id=NULL WHERE project_id=:projectId")
    void clearProjectIdForProject(@Bind("projectId") String projectId);
}
