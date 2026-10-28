package com.biecuoguo.auth.api;

/**
 * 设备会话结束：退出这台设备（sessionId 有值），或身份版本推进后该用户全部会话失效（sessionId 为空）。
 * 随撤销事务同步发布，监听方的写入和撤销一起提交或回滚。
 */
public record DeviceSessionsEnded(long userId, String sessionId) {
    public boolean allSessions() {
        return sessionId == null;
    }
}
