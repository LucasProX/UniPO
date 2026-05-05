package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String publicUid;
    private String email;
    private String phone;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private Long schoolId;
    private Long collegeId;
    private Long majorId;
    private String college;
    private String major;
    private String grade;
    private String bio;
    private String verifiedStatus;
    private String role;
    private String operatorScope;
    private Integer level;
    private Integer xp;
    private String levelTitle;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastLoginAt;
}
