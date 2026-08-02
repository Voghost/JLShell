package com.jlshell.plugin.api.security;

public record PluginAccessDecision(Effect effect, String reason) {

    public enum Effect {
        ALLOW,
        DENY,
        ABSTAIN
    }

    public static PluginAccessDecision allow() {
        return new PluginAccessDecision(Effect.ALLOW, "");
    }

    public static PluginAccessDecision deny(String reason) {
        return new PluginAccessDecision(Effect.DENY, reason == null ? "access denied" : reason);
    }

    public static PluginAccessDecision abstain() {
        return new PluginAccessDecision(Effect.ABSTAIN, "");
    }
}
