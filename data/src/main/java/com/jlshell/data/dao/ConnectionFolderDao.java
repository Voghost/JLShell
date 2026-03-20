package com.jlshell.data.dao;

import java.util.List;
import java.util.Optional;

import com.jlshell.data.entity.ConnectionFolderEntity;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * 连接文件夹 JDBI DAO。
 */
@RegisterBeanMapper(ConnectionFolderEntity.class)
public interface ConnectionFolderDao {

    @SqlQuery("SELECT * FROM connection_folders WHERE project_id = :projectId ORDER BY sort_order ASC, name ASC")
    List<ConnectionFolderEntity> findAllByProjectId(@Bind("projectId") String projectId);

    @SqlQuery("SELECT * FROM connection_folders WHERE project_id IS NULL ORDER BY sort_order ASC, name ASC")
    List<ConnectionFolderEntity> findAllWithNoProject();

    @SqlQuery("SELECT * FROM connection_folders WHERE id = :id")
    Optional<ConnectionFolderEntity> findById(@Bind("id") String id);

    @SqlUpdate("INSERT INTO connection_folders (id, name, parent_id, project_id, sort_order, created_at, updated_at) " +
            "VALUES (:id, :name, :parentId, :projectId, :sortOrder, :createdAt, :updatedAt)")
    void insert(@BindBean ConnectionFolderEntity entity);

    @SqlUpdate("UPDATE connection_folders SET name=:name, parent_id=:parentId, project_id=:projectId, " +
            "sort_order=:sortOrder, updated_at=:updatedAt WHERE id=:id")
    void update(@BindBean ConnectionFolderEntity entity);

    @SqlUpdate("DELETE FROM connection_folders WHERE id = :id")
    void deleteById(@Bind("id") String id);

    @SqlUpdate("UPDATE connection_folders SET parent_id=:parentId, updated_at=:updatedAt WHERE id=:folderId")
    void updateParentId(@Bind("folderId") String folderId,
                        @Bind("parentId") String parentId,
                        @Bind("updatedAt") java.time.Instant updatedAt);
}
