package com.jlshell.sftp.service;

import com.jlshell.sftp.model.TransferProgress;

/**
 * 传输进度回调。
 * UI 层可将这些事件桥接到 JavaFX 进度条，并通过 Platform.runLater 保证线程安全。
 */
public interface TransferProgressListener {

    TransferProgressListener NO_OP = new TransferProgressListener() {
    };

    default void onStarted(TransferProgress progress) {
    }

    default void onProgress(TransferProgress progress) {
    }

    default void onCompleted(TransferProgress progress) {
    }

    default void onFailed(TransferProgress progress, Throwable throwable) {
    }
}
