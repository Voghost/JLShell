package com.jlshell.data.repository;

import java.time.Instant;
import java.util.List;

import com.jlshell.data.entity.SessionHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话历史仓储。
 */
public interface SessionHistoryRepository extends JpaRepository<SessionHistoryEntity, String> {

    List<SessionHistoryEntity> findTop20ByConnection_IdOrderByOpenedAtDesc(String connectionId);

    List<SessionHistoryEntity> findByOpenedAtAfterOrderByOpenedAtDesc(Instant openedAt);
}
