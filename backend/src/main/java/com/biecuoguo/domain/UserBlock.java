package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_blocks")
public class UserBlock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long blockerId;
    private Long blockedId;
    private LocalDateTime createdAt;
}
