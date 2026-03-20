package com.jlshell.data.entity;

import java.time.Instant;

/**
 * 会话历史实体（纯 POJO，由 JDBI 映射）。
 */
public class SessionHistoryEntity extends AbstractAuditableEntity {

    private String connectionId;
    private String sessionIdentifier;
    private String state;
    private Instant openedAt;
    private Instant closedAt;
    private String remoteAddress;
    private Integer exitCode;
    private String failureReason;

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getSessionIdentifier() { return sessionIdentifier; }
    public void setSessionIdentifier(String sessionIdentifier) { this.sessionIdentifier = sessionIdentifier; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public String getRemoteAddress() { return remoteAddress; }
    public void setRemoteAddress(String remoteAddress) { this.remoteAddress = remoteAddress; }

    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
