package com.osr.openliststrm.rename;

/** 下载清单或 STRM 生成记录尚未就绪；只能延后处理，不能退回片名搜索。 */
public class MediaIdentityPendingException extends RuntimeException {
    public MediaIdentityPendingException(String message) { super(message); }
    public MediaIdentityPendingException(String message, Throwable cause) { super(message, cause); }
}
