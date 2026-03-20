package com.jlshell.data.dao;

import java.util.List;
import java.util.Optional;

import com.jlshell.data.entity.ConnectionEntity;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * 连接配置 JDBI DAO。
 */
@RegisterBeanMapper(ConnectionEntity.class)
public interface ConnectionDao {

    @SqlQuery("SELECT * FROM connections ORDER BY display_name ASC")
    List<ConnectionEntity> findAllOrderByName();

    @SqlQuery("SELECT * FROM connections WHERE project_id = :projectId ORDER BY display_name ASC")
    List<ConnectionEntity> findAllByProjectId(@Bind("projectId") String projectId);

    @SqlQuery("SELECT * FROM connections WHERE project_id IS NULL ORDER BY display_name ASC")
    List<ConnectionEntity> findAllWithNoProject();

    @SqlQuery("SELECT * FROM connections WHERE folder_id = :folderId ORDER BY display_name ASC")
    List<ConnectionEntity> findAllByFolderId(@Bind("folderId") String folderId);

    @SqlQuery("SELECT * FROM connections WHERE folder_id IS NULL AND project_id = :projectId ORDER BY display_name ASC")
    List<ConnectionEntity> findAllByProjectIdNoFolder(@Bind("projectId") String projectId);

    @SqlQuery("SELECT * FROM connections WHERE folder_id IS NULL AND project_id IS NULL ORDER BY display_name ASC")
    List<ConnectionEntity> findAllWithNoFolderNoProject();

    @SqlQuery("SELECT * FROM connections WHERE id = :id")
    Optional<ConnectionEntity> findById(@Bind("id") String id);

    @SqlQuery("SELECT * FROM connections WHERE host = :host AND port = :port AND username = :username LIMIT 1")
    Optional<ConnectionEntity> findByHostPortUsername(@Bind("host") String host, @Bind("port") int port, @Bind("username") String username);

    @SqlUpdate("INSERT INTO connections (id, display_name, host, port, username, authentication_type, " +
            "host_key_verification_mode, description, default_remote_path, favorite, connection_type, " +
            "project_id, folder_id, credential_id, created_at, updated_at) " +
            "VALUES (:id, :displayName, :host, :port, :username, :authenticationType, " +
            ":hostKeyVerificationMode, :description, :defaultRemotePath, :favorite, :connectionType, " +
            ":projectId, :folderId, :credentialId, :createdAt, :updatedAt)")
    void insert(@BindBean ConnectionEntity entity);

    @SqlUpdate("UPDATE connections SET display_name=:displayName, host=:host, port=:port, " +
            "username=:username, authentication_type=:authenticationType, " +
            "host_key_verification_mode=:hostKeyVerificationMode, description=:description, " +
            "default_remote_path=:defaultRemotePath, favorite=:favorite, connection_type=:connectionType, " +
            "project_id=:projectId, folder_id=:folderId, credential_id=:credentialId, " +
            "updated_at=:updatedAt WHERE id=:id")
    void update(@BindBean ConnectionEntity entity);

    @SqlUpdate("DELETE FROM connections WHERE id = :id")
    void deleteById(@Bind("id") String id);

    @SqlUpdate("UPDATE connections SET project_id=NULL WHERE project_id=:projectId")
    void clearProjectIdForProject(@Bind("projectId") String projectId);

    @SqlUpdate("UPDATE connections SET folder_id=NULL WHERE folder_id=:folderId")
    void clearFolderIdForFolder(@Bind("folderId") String folderId);

    @SqlUpdate("UPDATE connections SET folder_id=:folderId, updated_at=:updatedAt WHERE id=:connectionId")
    void updateFolderId(@Bind("connectionId") String connectionId,
                        @Bind("folderId") String folderId,
                        @Bind("updatedAt") java.time.Instant updatedAt);
}
