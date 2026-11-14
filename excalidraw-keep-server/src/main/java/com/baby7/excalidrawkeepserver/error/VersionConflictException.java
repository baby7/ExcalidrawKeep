package com.baby7.excalidrawkeepserver.error;

/**
 * 客户端拿的 baseVersion 与库里不一致。后端不替客户端合并，
 * 把 currentVersion 带回去让前端取最新场景做 reconcile 后重试。
 */
public class VersionConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int currentVersion;

    public VersionConflictException(int currentVersion) {
        super("场景已被其他窗口或标签页修改");
        this.currentVersion = currentVersion;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }
}
