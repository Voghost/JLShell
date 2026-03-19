package com.jlshell.sftp.service;

import java.util.concurrent.CompletableFuture;

import com.jlshell.core.session.SshSession;
import com.jlshell.sftp.model.RemoteDirectoryListing;
import com.jlshell.sftp.model.RemoteFileEntry;
import com.jlshell.sftp.model.TransferRequest;

/**
 * SFTP 服务抽象。
 */
public interface SftpService {

    CompletableFuture<RemoteDirectoryListing> listDirectory(SshSession sshSession, String directoryPath);

    CompletableFuture<RemoteFileEntry> stat(SshSession sshSession, String path);

    CompletableFuture<Void> upload(SshSession sshSession, TransferRequest request, TransferProgressListener listener);

    CompletableFuture<Void> download(SshSession sshSession, TransferRequest request, TransferProgressListener listener);

    CompletableFuture<Void> rename(SshSession sshSession, String sourcePath, String targetPath);

    CompletableFuture<Void> delete(SshSession sshSession, String remotePath, boolean recursive);

    CompletableFuture<Void> createDirectory(SshSession sshSession, String remotePath, boolean recursive);
}
