package com.jlshell.data.dao;

import java.util.List;
import java.util.Optional;

import com.jlshell.data.entity.CustomColorSchemeEntity;
import org.jdbi.v3.sqlobject.config.RegisterBeanMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface CustomColorSchemeDao {

    @SqlQuery("SELECT * FROM terminal_color_schemes ORDER BY name")
    @RegisterBeanMapper(CustomColorSchemeEntity.class)
    List<CustomColorSchemeEntity> findAll();

    @SqlQuery("SELECT * FROM terminal_color_schemes WHERE name = :name")
    @RegisterBeanMapper(CustomColorSchemeEntity.class)
    Optional<CustomColorSchemeEntity> findByName(@Bind("name") String name);

    @SqlUpdate("INSERT INTO terminal_color_schemes (id, name, colors_json, created_at, updated_at) " +
            "VALUES (:id, :name, :colorsJson, :createdAt, :updatedAt)")
    void insert(@BindBean CustomColorSchemeEntity entity);

    @SqlUpdate("UPDATE terminal_color_schemes SET colors_json=:colorsJson, updated_at=:updatedAt WHERE id=:id")
    void update(@BindBean CustomColorSchemeEntity entity);

    @SqlUpdate("DELETE FROM terminal_color_schemes WHERE name = :name")
    void deleteByName(@Bind("name") String name);
}
