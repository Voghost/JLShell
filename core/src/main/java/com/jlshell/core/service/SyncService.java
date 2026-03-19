package com.jlshell.core.service;

import java.util.concurrent.CompletableFuture;

/**
 * 云端同步扩展点，仅定义抽象能力。
 * 具体同步协议和实现将在后续模块或插件中提供。
 */
public interface SyncService {

    CompletableFuture<Void> push();

    CompletableFuture<Void> pull();
}
