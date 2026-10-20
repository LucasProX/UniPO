package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_reports")
public class UserReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reporterId;
    private Long targetUserId;
    private String category;
    private String detail;
    private String status;
    private LocalDateTime createdAt;
}
