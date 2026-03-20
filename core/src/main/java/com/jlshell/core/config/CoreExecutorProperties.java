package com.jlshell.core.config;

import java.time.Duration;

/**
 * 核心线程池配置（纯 POJO，由 AppContext 直接构造）。
 */
public class CoreExecutorProperties {

    private int corePoolSize = 4;
    private int maxPoolSize = 16;
    private int queueCapacity = 256;
    private Duration keepAlive = Duration.ofSeconds(60);

    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }

    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public Duration getKeepAlive() { return keepAlive; }
    public void setKeepAlive(Duration keepAlive) { this.keepAlive = keepAlive; }
}
