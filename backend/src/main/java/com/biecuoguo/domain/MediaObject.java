package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("media_objects")
public class MediaObject {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String bucket;
    private String objectKey;
    private String url;
    private String contentType;
    private Long sizeBytes;
    private String purpose;
    private LocalDateTime createdAt;
}
