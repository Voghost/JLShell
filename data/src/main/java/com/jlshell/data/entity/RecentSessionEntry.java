package com.jlshell.data.entity;

import java.time.Instant;

/**
 * 最近会话条目 DTO（session_history JOIN connections 投影）。
 * 不继承 AbstractAuditableEntity，仅用于只读查询。
 */
public class RecentSessionEntry {

    private String id;
    private String connectionId;
    private Instant openedAt;
    private Instant closedAt;
    private String state;
    // Joined from connections
    private String displayName;
    private String host;
    private int port;
    private String username;
    private String connectionType;
    private boolean favorite;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getConnectionType() { return connectionType; }
    public void setConnectionType(String connectionType) { this.connectionType = connectionType; }

    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
}
