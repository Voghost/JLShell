package com.jlshell.data.repository;

import java.util.List;

import com.jlshell.data.entity.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 项目仓储。
 */
public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {

    List<ProjectEntity> findAllByOrderBySortOrderAscNameAsc();
}
