package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 工序
 */
@TableName(value = "prod_process")
@Data
public class ProdProcess implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("line_id")
    private Long lineId;

    @TableField("name")
    private String name;

    @TableField("seq")
    private Integer seq;

    @TableField("param_name")
    private String paramName;

    @TableField("param_target")
    private Double paramTarget;

    @TableField("param_usl")
    private Double paramUsl;

    @TableField("param_lsl")
    private Double paramLsl;

    @TableField("create_time")
    private Date createTime;

    @TableField("update_time")
    private Date updateTime;

    @TableLogic
    @TableField("is_delete")
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
