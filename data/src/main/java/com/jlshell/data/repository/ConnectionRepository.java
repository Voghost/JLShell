package com.jlshell.data.repository;

import java.util.List;
import java.util.Optional;

import com.jlshell.data.entity.ConnectionEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 连接配置仓储。
 */
public interface ConnectionRepository extends JpaRepository<ConnectionEntity, String> {

    @EntityGraph(attributePaths = "credential")
    List<ConnectionEntity> findAllByOrderByDisplayNameAsc();

    @EntityGraph(attributePaths = "credential")
    List<ConnectionEntity> findAllByProject_IdOrderByDisplayNameAsc(String projectId);

    @EntityGraph(attributePaths = "credential")
    List<ConnectionEntity> findAllByProjectIsNullOrderByDisplayNameAsc();

    @EntityGraph(attributePaths = "credential")
    List<ConnectionEntity> findAllByFolder_IdOrderByDisplayNameAsc(String folderId);

    @EntityGraph(attributePaths = "credential")
    List<ConnectionEntity> findAllByFolderIsNullAndProject_IdOrderByDisplayNameAsc(String projectId);

    @EntityGraph(attributePaths = "credential")
    List<ConnectionEntity> findAllByFolderIsNullAndProjectIsNullOrderByDisplayNameAsc();

    @EntityGraph(attributePaths = "credential")
    Optional<ConnectionEntity> findByHostAndPortAndUsername(String host, int port, String username);
}
