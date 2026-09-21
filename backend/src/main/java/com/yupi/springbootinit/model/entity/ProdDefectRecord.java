package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 缺陷记录
 */
@TableName(value = "prod_defect_record")
@Data
public class ProdDefectRecord implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("stat_date")
    private Date statDate;

    @TableField("line_id")
    private Long lineId;

    @TableField("process_id")
    private Long processId;

    @TableField("defect_code")
    private String defectCode;

    @TableField("defect_name")
    private String defectName;

    @TableField("qty")
    private Integer qty;

    @TableField("severity")
    private Integer severity;

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
