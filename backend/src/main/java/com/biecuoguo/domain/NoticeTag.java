package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("notice_tags")
public class NoticeTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long noticeId;
    private Long tagId;
}
