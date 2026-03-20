package com.jlshell.data.dao;

import java.util.List;
import java.util.Optional;

import com.jlshell.data.entity.ProjectEntity;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * 项目 JDBI DAO。
 */
@RegisterBeanMapper(ProjectEntity.class)
public interface ProjectDao {

    @SqlQuery("SELECT * FROM projects ORDER BY sort_order ASC, name ASC")
    List<ProjectEntity> findAllOrderBySortOrderName();

    @SqlQuery("SELECT * FROM projects WHERE id = :id")
    Optional<ProjectEntity> findById(@Bind("id") String id);

    @SqlUpdate("INSERT INTO projects (id, name, description, sort_order, created_at, updated_at) " +
            "VALUES (:id, :name, :description, :sortOrder, :createdAt, :updatedAt)")
    void insert(@BindBean ProjectEntity entity);

    @SqlUpdate("UPDATE projects SET name=:name, description=:description, sort_order=:sortOrder, " +
            "updated_at=:updatedAt WHERE id=:id")
    void update(@BindBean ProjectEntity entity);

    @SqlUpdate("DELETE FROM projects WHERE id = :id")
    void deleteById(@Bind("id") String id);
}
