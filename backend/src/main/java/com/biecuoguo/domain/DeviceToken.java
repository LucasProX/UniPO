package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("device_tokens")
public class DeviceToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 登记时的设备会话（V40）；会话结束时这一行随之删除。 */
    private String deviceSessionId;
    private String token;
    private String platform;
    private String environment;
    private LocalDateTime createdAt;
    private LocalDateTime lastSeenAt;
}
