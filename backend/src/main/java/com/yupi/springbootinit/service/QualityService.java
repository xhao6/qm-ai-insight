package com.yupi.springbootinit.service;

import com.yupi.springbootinit.model.vo.QualityVO;

import java.util.List;

public interface QualityService {

    List<QualityVO.YieldPoint> yieldTrend(Long lineId, int days);

    List<QualityVO.OeePoint> oeeTrend(Long lineId, int days);

    List<QualityVO.DefectItem> defectDistribution(Long lineId, int days);

    QualityVO.SpcResult spc(Long processId, int days);

    QualityVO.ProcessDefectMatrix defectProcessMatrix(Long lineId, int days);

    QualityVO.Summary summary(Long lineId, int days);
}
