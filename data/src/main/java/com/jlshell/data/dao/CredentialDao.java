package com.jlshell.data.dao;

import java.util.Optional;

import com.jlshell.data.entity.CredentialEntity;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * 凭证 JDBI DAO。
 */
@RegisterBeanMapper(CredentialEntity.class)
public interface CredentialDao {

    @SqlQuery("SELECT * FROM credentials WHERE id = :id")
    Optional<CredentialEntity> findById(@Bind("id") String id);

    @SqlUpdate("INSERT INTO credentials (id, authentication_type, encrypted_password, encrypted_passphrase, " +
            "private_key_path, created_at, updated_at) " +
            "VALUES (:id, :authenticationType, :encryptedPassword, :encryptedPassphrase, " +
            ":privateKeyPath, :createdAt, :updatedAt)")
    void insert(@BindBean CredentialEntity entity);

    @SqlUpdate("UPDATE credentials SET authentication_type=:authenticationType, " +
            "encrypted_password=:encryptedPassword, encrypted_passphrase=:encryptedPassphrase, " +
            "private_key_path=:privateKeyPath, updated_at=:updatedAt WHERE id=:id")
    void update(@BindBean CredentialEntity entity);

    @SqlUpdate("DELETE FROM credentials WHERE id = :id")
    void deleteById(@Bind("id") String id);
}
