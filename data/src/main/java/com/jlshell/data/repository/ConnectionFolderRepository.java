package com.jlshell.data.repository;

import java.util.List;

import com.jlshell.data.entity.ConnectionFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 连接文件夹仓储。
 */
public interface ConnectionFolderRepository extends JpaRepository<ConnectionFolderEntity, String> {

    List<ConnectionFolderEntity> findAllByProject_IdOrderBySortOrderAscNameAsc(String projectId);

    List<ConnectionFolderEntity> findAllByProjectIsNullOrderBySortOrderAscNameAsc();

    List<ConnectionFolderEntity> findAllByParent_IdOrderBySortOrderAscNameAsc(String parentId);

    List<ConnectionFolderEntity> findAllByParentIsNullAndProject_IdOrderBySortOrderAscNameAsc(String projectId);

    List<ConnectionFolderEntity> findAllByParentIsNullAndProjectIsNullOrderBySortOrderAscNameAsc();
}
