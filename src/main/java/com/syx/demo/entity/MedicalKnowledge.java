package com.syx.demo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("medical_knowledge")
public class MedicalKnowledge {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String title;
    private String content;      // 核心：知识内容
    private String category;     // 分类：急救指南/药品说明/操作规范
    private String tags;         // 标签
    private String source;       // 来源
    private Integer status;      // 1启用 0停用
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}