package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    @JsonIgnore
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String passwordHash;
    /** Only AccountSecurityApi may advance this value; stale profile writes must never restore it. */
    @JsonIgnore
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long authVersion = 0L;
    private String nickname;
    private String avatarUrl;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String backgroundUrl;
    private Long schoolId;
    private Long collegeId;
    private Long majorId;
    private String college;
    private String major;
    private String grade;
    private String bio;
    private String verifiedStatus;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String role;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String operatorScope;
    private Integer level;
    private Integer xp;
    private String levelTitle;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastLoginAt;
    /** 上次查看粉丝列表的时间；用于个人主页的「+n 新粉丝」。 */
    private LocalDateTime followersSeenAt;
    private String gender;
    private Integer birthYear;
    private String zodiac;
    private String mbti;
    private Boolean showGender;
    private Boolean showAge;
    private Boolean showGrade;
    private Boolean onboardingCompleted;
}
