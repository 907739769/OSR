package com.osr.openliststrm.backup;

/**
 * 某个分区恢复失败（校验不通过）。抛出时所有配置分区都已回滚，消息开头带着分区名，可以直接给用户看。
 *
 * @author Jack
 */
public class BackupRestoreException extends RuntimeException {

    public BackupRestoreException(String message) {
        super(message);
    }

    public BackupRestoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
