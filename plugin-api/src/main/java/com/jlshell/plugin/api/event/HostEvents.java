package com.jlshell.plugin.api.event;

import java.util.function.Consumer;

import com.jlshell.plugin.api.lifecycle.Registration;

/**
 * 插件只读的宿主事件订阅入口。事件只能由宿主发布，监听器在发布事件的线程同步执行；
 * 需要更新 JavaFX UI 的插件应自行切换到 FX Application Thread。
 */
public interface HostEvents {

    boolean available();

    <E extends HostEvent> Registration subscribe(Class<E> eventType, Consumer<? super E> listener);

    static HostEvents unavailable() {
        return UnavailableHostEvents.INSTANCE;
    }
}

final class UnavailableHostEvents implements HostEvents {
    static final UnavailableHostEvents INSTANCE = new UnavailableHostEvents();

    private UnavailableHostEvents() {
    }

    @Override public boolean available() { return false; }

    @Override
    public <E extends HostEvent> Registration subscribe(Class<E> eventType, Consumer<? super E> listener) {
        return Registration.noop();
    }
}
