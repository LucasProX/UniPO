package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("reminders")
public class Reminder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long noticeId;
    private LocalDateTime remindAt;
    private String status;
    private String channel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
