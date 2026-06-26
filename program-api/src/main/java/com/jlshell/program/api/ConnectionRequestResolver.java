package com.jlshell.program.api;

import com.jlshell.core.model.ConnectionRequest;

@FunctionalInterface
public interface ConnectionRequestResolver {
    ConnectionRequest resolve(String connectionId);
}
