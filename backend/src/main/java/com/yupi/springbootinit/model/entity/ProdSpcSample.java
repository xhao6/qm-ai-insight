package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * SPC采样数据
 */
@TableName(value = "prod_spc_sample")
@Data
public class ProdSpcSample implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("stat_date")
    private Date statDate;

    @TableField("process_id")
    private Long processId;

    @TableField("sample_no")
    private Integer sampleNo;

    @TableField("value")
    private Double value;

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
