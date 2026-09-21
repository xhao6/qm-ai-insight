package com.yupi.springbootinit.controller;

import com.yupi.springbootinit.common.BaseResponse;
import com.yupi.springbootinit.common.ResultUtils;
import com.yupi.springbootinit.model.vo.QualityVO;
import com.yupi.springbootinit.service.QualityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/quality")
public class QualityController {

    @Resource
    private QualityService qualityService;

    @GetMapping("/summary")
    public BaseResponse<QualityVO.Summary> summary(@RequestParam(required = false, defaultValue = "7") int days,
                                                   @RequestParam(required = false, defaultValue = "1") Long lineId) {
        return ResultUtils.success(qualityService.summary(lineId, days));
    }

    @GetMapping("/yield/trend")
    public BaseResponse<List<QualityVO.YieldPoint>> yieldTrend(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "1") Long lineId) {
        return ResultUtils.success(qualityService.yieldTrend(lineId, days));
    }

    @GetMapping("/oee/trend")
    public BaseResponse<List<QualityVO.OeePoint>> oeeTrend(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "1") Long lineId) {
        return ResultUtils.success(qualityService.oeeTrend(lineId, days));
    }

    @GetMapping("/defect/distribution")
    public BaseResponse<List<QualityVO.DefectItem>> defectDistribution(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "1") Long lineId) {
        return ResultUtils.success(qualityService.defectDistribution(lineId, days));
    }

    @GetMapping("/spc")
    public BaseResponse<QualityVO.SpcResult> spc(
            @RequestParam Long processId,
            @RequestParam(defaultValue = "30") int days) {
        return ResultUtils.success(qualityService.spc(processId, days));
    }

    @GetMapping("/defect/process")
    public BaseResponse<QualityVO.ProcessDefectMatrix> defectProcess(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "1") Long lineId) {
        return ResultUtils.success(qualityService.defectProcessMatrix(lineId, days));
    }
}
