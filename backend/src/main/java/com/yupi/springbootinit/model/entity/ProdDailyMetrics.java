package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * 产线日指标
 */
@TableName(value = "prod_daily_metrics")
@Data
public class ProdDailyMetrics implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("stat_date")
    private Date statDate;

    @TableField("line_id")
    private Long lineId;

    @TableField("plan_qty")
    private Integer planQty;

    @TableField("output_qty")
    private Integer outputQty;

    @TableField("good_qty")
    private Integer goodQty;

    @TableField("defect_qty")
    private Integer defectQty;

    @TableField("available_time_min")
    private Integer availableTimeMin;

    @TableField("run_time_min")
    private Integer runTimeMin;

    @TableField("ideal_cycle_min")
    private BigDecimal idealCycleMin;

    @TableField("actual_cycle_min")
    private BigDecimal actualCycleMin;

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
