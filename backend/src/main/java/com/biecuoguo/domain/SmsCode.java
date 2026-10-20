package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sms_codes")
public class SmsCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String purpose;
    private String codeHash;
    private LocalDateTime expiresAt;
    private Boolean consumed;
    private LocalDateTime createdAt;
}
