package com.jlshell.data.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 会话历史实体。
 */
@Entity
@Table(name = "session_history")
public class SessionHistoryEntity extends AbstractAuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false)
    private ConnectionEntity connection;

    @Column(name = "session_identifier", nullable = false, length = 64)
    private String sessionIdentifier;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "remote_address", length = 255)
    private String remoteAddress;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "failure_reason", length = 2048)
    private String failureReason;

    public ConnectionEntity getConnection() {
        return connection;
    }

    public void setConnection(ConnectionEntity connection) {
        this.connection = connection;
    }

    public String getSessionIdentifier() {
        return sessionIdentifier;
    }

    public void setSessionIdentifier(String sessionIdentifier) {
        this.sessionIdentifier = sessionIdentifier;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
