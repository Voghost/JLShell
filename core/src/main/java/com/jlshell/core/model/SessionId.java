package com.jlshell.core.model;

import java.util.UUID;

/**
 * 会话唯一标识。
 * 单独建模比直接暴露 UUID 更容易在后续扩展字段或序列化规则。
 */
public record SessionId(UUID value) {

    public SessionId {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
    }

    public static SessionId randomId() {
        return new SessionId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
