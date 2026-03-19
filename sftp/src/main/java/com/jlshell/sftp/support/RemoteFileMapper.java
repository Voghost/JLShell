package com.jlshell.sftp.support;

import java.time.Instant;
import java.util.Set;

import com.jlshell.sftp.model.RemoteFileEntry;
import com.jlshell.sftp.model.RemoteFileType;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.xfer.FilePermission;

/**
 * SSHJ 远程文件信息到模块模型的映射器。
 */
public final class RemoteFileMapper {

    private RemoteFileMapper() {
    }

    public static RemoteFileEntry fromRemoteResourceInfo(RemoteResourceInfo resourceInfo) {
        return fromAttributes(
                resourceInfo.getPath(),
                resourceInfo.getName(),
                resourceInfo.getAttributes()
        );
    }

    public static RemoteFileEntry fromAttributes(String path, String name, FileAttributes attributes) {
        Integer uid = attributes.has(FileAttributes.Flag.UIDGID) ? attributes.getUID() : null;
        Integer gid = attributes.has(FileAttributes.Flag.UIDGID) ? attributes.getGID() : null;
        Instant modifiedAt = attributes.has(FileAttributes.Flag.ACMODTIME)
                ? Instant.ofEpochSecond(attributes.getMtime())
                : null;
        long size = attributes.has(FileAttributes.Flag.SIZE) ? attributes.getSize() : 0L;

        return new RemoteFileEntry(
                path,
                name,
                toType(attributes.getType()),
                size,
                toPermissionString(attributes.getPermissions()),
                modifiedAt,
                uid,
                gid
        );
    }

    private static RemoteFileType toType(FileMode.Type type) {
        if (type == null) {
            return RemoteFileType.OTHER;
        }
        return switch (type) {
            case DIRECTORY -> RemoteFileType.DIRECTORY;
            case REGULAR -> RemoteFileType.FILE;
            case SYMLINK -> RemoteFileType.SYMLINK;
            default -> RemoteFileType.OTHER;
        };
    }

    private static String toPermissionString(Set<FilePermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "---------";
        }

        return new StringBuilder(9)
                .append(has(permissions, FilePermission.USR_R) ? 'r' : '-')
                .append(has(permissions, FilePermission.USR_W) ? 'w' : '-')
                .append(has(permissions, FilePermission.USR_X) ? 'x' : '-')
                .append(has(permissions, FilePermission.GRP_R) ? 'r' : '-')
                .append(has(permissions, FilePermission.GRP_W) ? 'w' : '-')
                .append(has(permissions, FilePermission.GRP_X) ? 'x' : '-')
                .append(has(permissions, FilePermission.OTH_R) ? 'r' : '-')
                .append(has(permissions, FilePermission.OTH_W) ? 'w' : '-')
                .append(has(permissions, FilePermission.OTH_X) ? 'x' : '-')
                .toString();
    }

    private static boolean has(Set<FilePermission> permissions, FilePermission permission) {
        return permissions.contains(permission);
    }
}
