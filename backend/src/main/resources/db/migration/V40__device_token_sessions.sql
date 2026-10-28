-- M05：推送设备跟登录会话绑定。
-- 以前 device_tokens 只记 user_id：退出登录不会删设备，旧手机会继续收到这个账号的私信推送（含预览）。
-- 现在登记时记下当前设备会话；退出这台设备、全部退出或身份版本推进时随撤销事务删除，
-- 会话自然过期的由推送模块每小时清理一次。以前登记的行没有会话，会在清理时删掉，App 下次登录状态启动会重新登记。
ALTER TABLE device_tokens
  ADD COLUMN device_session_id VARCHAR(43) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER user_id,
  ADD KEY idx_device_tokens_session (device_session_id);
