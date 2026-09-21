package com.yupi.springbootinit.service.impl;

import com.yupi.springbootinit.mapper.ProdDailyMetricsMapper;
import com.yupi.springbootinit.mapper.ProdDefectRecordMapper;
import com.yupi.springbootinit.mapper.ProdProcessMapper;
import com.yupi.springbootinit.mapper.ProdSpcSampleMapper;
import com.yupi.springbootinit.model.entity.ProdDailyMetrics;
import com.yupi.springbootinit.model.entity.ProdDefectRecord;
import com.yupi.springbootinit.model.vo.QualityVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityServiceImplTest {

    private QualityServiceImpl newService(ProdDailyMetricsMapper dailyMapper,
                                          ProdDefectRecordMapper defectMapper,
                                          ProdProcessMapper processMapper,
                                          ProdSpcSampleMapper spcMapper) {
        QualityServiceImpl s = new QualityServiceImpl();
        ReflectionTestUtils.setField(s, "dailyMetricsMapper", dailyMapper);
        ReflectionTestUtils.setField(s, "defectRecordMapper", defectMapper);
        ReflectionTestUtils.setField(s, "processMapper", processMapper);
        ReflectionTestUtils.setField(s, "spcSampleMapper", spcMapper);
        return s;
    }

    private ProdDailyMetrics metrics(Date date, int output, int good,
                                     int availableMin, int runMin,
                                     double idealMin) {
        ProdDailyMetrics m = new ProdDailyMetrics();
        m.setStatDate(date);
        m.setOutputQty(output);
        m.setGoodQty(good);
        m.setAvailableTimeMin(availableMin);
        m.setRunTimeMin(runMin);
        m.setIdealCycleMin(BigDecimal.valueOf(idealMin));
        return m;
    }

    @Test
    void oeeTrendComputesAvailabilityPerformanceQuality() {
        ProdDailyMetricsMapper dailyMapper = mock(ProdDailyMetricsMapper.class);
        ProdDefectRecordMapper defectMapper = mock(ProdDefectRecordMapper.class);
        ProdProcessMapper processMapper = mock(ProdProcessMapper.class);
        ProdSpcSampleMapper spcMapper = mock(ProdSpcSampleMapper.class);
        when(dailyMapper.selectList(any())).thenReturn(Collections.singletonList(
                metrics(new Date(), 1000, 950, 600, 540, 0.5)));
        QualityServiceImpl s = newService(dailyMapper, defectMapper, processMapper, spcMapper);

        List<QualityVO.OeePoint> points = s.oeeTrend(1L, 7);

        assertEquals(1, points.size());
        QualityVO.OeePoint p = points.get(0);
        assertEquals(90.0, p.getAvailability(), 1e-6);       // 540/600
        assertEquals(92.59, p.getPerformance(), 1e-2);       // 0.5*1000/540
        assertEquals(95.0, p.getQuality(), 1e-6);            // 950/1000
        assertEquals(79.17, p.getOee(), 1e-2);               // 0.90*0.9259*0.95
    }

    @Test
    void defectDistributionAggregatesAndCumulativePct() {
        ProdDailyMetricsMapper dailyMapper = mock(ProdDailyMetricsMapper.class);
        ProdDefectRecordMapper defectMapper = mock(ProdDefectRecordMapper.class);
        ProdProcessMapper processMapper = mock(ProdProcessMapper.class);
        ProdSpcSampleMapper spcMapper = mock(ProdSpcSampleMapper.class);
        ProdDefectRecord r1 = new ProdDefectRecord();
        r1.setDefectCode("weld_defect"); r1.setDefectName("焊接缺陷"); r1.setQty(60);
        ProdDefectRecord r2 = new ProdDefectRecord();
        r2.setDefectCode("weld_defect"); r2.setDefectName("焊接缺陷"); r2.setQty(40);
        ProdDefectRecord r3 = new ProdDefectRecord();
        r3.setDefectCode("surface_scratch"); r3.setDefectName("划伤"); r3.setQty(150);
        when(defectMapper.selectList(any())).thenReturn(Arrays.asList(r1, r2, r3));
        QualityServiceImpl s = newService(dailyMapper, defectMapper, processMapper, spcMapper);

        List<QualityVO.DefectItem> items = s.defectDistribution(1L, 7);

        assertEquals(2, items.size());
        assertEquals("surface_scratch", items.get(0).getDefectCode());
        assertEquals(60.0, items.get(0).getPct(), 1e-6);
        assertEquals(100.0, items.get(1).getPct(), 1e-6);
    }
}
