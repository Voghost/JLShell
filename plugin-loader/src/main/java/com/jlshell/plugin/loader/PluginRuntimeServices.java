package com.jlshell.plugin.loader;

import com.jlshell.plugin.api.event.HostEvent;
import com.jlshell.plugin.api.event.HostEvents;

/** 跨 UI/加载器边界的单进程宿主事件入口。 */
public final class PluginRuntimeServices {

    private static final HostEventBusImpl HOST_EVENTS = new HostEventBusImpl();

    private PluginRuntimeServices() {
    }

    public static HostEvents hostEvents(String ownerId) {
        return HOST_EVENTS.scoped(ownerId);
    }

    public static void publish(HostEvent event) {
        HOST_EVENTS.publish(event);
    }
}
