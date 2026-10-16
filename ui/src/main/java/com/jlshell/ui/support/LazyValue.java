package com.jlshell.ui.support;

import java.util.Objects;
import java.util.function.Supplier;

/** 首次使用时创建共享对象，避免并发调用重复初始化后台客户端。 */
public final class LazyValue<T> implements Supplier<T> {
    private Supplier<T> factory;
    private volatile T value;

    public LazyValue(Supplier<T> factory) { this.factory = Objects.requireNonNull(factory); }

    @Override public T get() {
        T current = value;
        if (current == null) {
            synchronized (this) {
                current = value;
                if (current == null) {
                    current = Objects.requireNonNull(factory.get());
                    value = current;
                    factory = null;
                }
            }
        }
        return current;
    }
}
