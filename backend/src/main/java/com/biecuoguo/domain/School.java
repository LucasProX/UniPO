package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("schools")
public class School {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private String province;
    private String city;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
