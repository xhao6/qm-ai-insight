package com.yupi.springbootinit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yupi.springbootinit.model.entity.ProdDailyMetrics;
import com.yupi.springbootinit.model.entity.ProdDefectRecord;
import com.yupi.springbootinit.model.entity.ProdProcess;
import com.yupi.springbootinit.model.entity.ProdSpcSample;
import com.yupi.springbootinit.model.vo.QualityVO;
import com.yupi.springbootinit.mapper.ProdDailyMetricsMapper;
import com.yupi.springbootinit.mapper.ProdDefectRecordMapper;
import com.yupi.springbootinit.mapper.ProdProcessMapper;
import com.yupi.springbootinit.mapper.ProdSpcSampleMapper;
import com.yupi.springbootinit.service.QualityService;
import com.yupi.springbootinit.utils.SpcCalculator;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class QualityServiceImpl implements QualityService {

    private static final double YIELD_TARGET = 95.0;
    private static final int MAX_DAYS = 365;

    @Resource
    private ProdDailyMetricsMapper dailyMetricsMapper;

    @Resource
    private ProdDefectRecordMapper defectRecordMapper;

    @Resource
    private ProdProcessMapper processMapper;

    @Resource
    private ProdSpcSampleMapper spcSampleMapper;

    private Date startDate(int days) {
        days = Math.max(1, Math.min(days, MAX_DAYS));
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private String fmt(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd").format(d);
    }

    @Override
    public List<QualityVO.YieldPoint> yieldTrend(Long lineId, int days) {
        Date start = startDate(days);
        QueryWrapper<ProdDailyMetrics> qw = new QueryWrapper<>();
        qw.eq(lineId != null && lineId > 0, "line_id", lineId)
                .ge("stat_date", start)
                .orderByAsc("stat_date");
        List<ProdDailyMetrics> list = dailyMetricsMapper.selectList(qw);
        List<QualityVO.YieldPoint> result = new ArrayList<>();
        for (ProdDailyMetrics m : list) {
            QualityVO.YieldPoint p = new QualityVO.YieldPoint();
            p.setDate(fmt(m.getStatDate()));
            double yield = m.getOutputQty() == 0 ? 0
                    : m.getGoodQty() * 100.0 / m.getOutputQty();
            p.setYield(round2(yield));
            p.setTarget(YIELD_TARGET);
            result.add(p);
        }
        return result;
    }

    @Override
    public List<QualityVO.OeePoint> oeeTrend(Long lineId, int days) {
        Date start = startDate(days);
        QueryWrapper<ProdDailyMetrics> qw = new QueryWrapper<>();
        qw.eq(lineId != null && lineId > 0, "line_id", lineId)
                .ge("stat_date", start)
                .orderByAsc("stat_date");
        List<ProdDailyMetrics> list = dailyMetricsMapper.selectList(qw);
        List<QualityVO.OeePoint> result = new ArrayList<>();
        for (ProdDailyMetrics m : list) {
            QualityVO.OeePoint p = new QualityVO.OeePoint();
            p.setDate(fmt(m.getStatDate()));
            double availability = m.getAvailableTimeMin() == 0 ? 0
                    : m.getRunTimeMin() * 100.0 / m.getAvailableTimeMin();
            double performance = m.getRunTimeMin() == 0 ? 0
                    : (m.getIdealCycleMin().doubleValue() * m.getOutputQty()) * 100.0 / m.getRunTimeMin();
            double quality = m.getOutputQty() == 0 ? 0
                    : m.getGoodQty() * 100.0 / m.getOutputQty();
            double oee = availability * performance * quality / 10000.0;
            p.setAvailability(round2(availability));
            p.setPerformance(round2(performance));
            p.setQuality(round2(quality));
            p.setOee(round2(oee));
            result.add(p);
        }
        return result;
    }

    @Override
    public List<QualityVO.DefectItem> defectDistribution(Long lineId, int days) {
        Date start = startDate(days);
        QueryWrapper<ProdDefectRecord> qw = new QueryWrapper<>();
        qw.eq(lineId != null && lineId > 0, "line_id", lineId)
                .ge("stat_date", start);
        List<ProdDefectRecord> list = defectRecordMapper.selectList(qw);
        Map<String, QualityVO.DefectItem> agg = new LinkedHashMap<>();
        for (ProdDefectRecord r : list) {
            QualityVO.DefectItem item = agg.computeIfAbsent(r.getDefectCode(), k -> {
                QualityVO.DefectItem it = new QualityVO.DefectItem();
                it.setDefectCode(r.getDefectCode());
                it.setDefectName(r.getDefectName());
                it.setQty(0);
                return it;
            });
            item.setQty(item.getQty() + r.getQty());
        }
        List<QualityVO.DefectItem> items = new ArrayList<>(agg.values());
        items.sort((a, b) -> Integer.compare(b.getQty(), a.getQty()));
        int total = items.stream().mapToInt(QualityVO.DefectItem::getQty).sum();
        int acc = 0;
        for (QualityVO.DefectItem it : items) {
            acc += it.getQty();
            it.setPct(total == 0 ? 0 : round2(acc * 100.0 / total));
        }
        return items;
    }

    @Override
    public QualityVO.SpcResult spc(Long processId, int days) {
        Date start = startDate(days);
        QueryWrapper<ProdSpcSample> qw = new QueryWrapper<>();
        qw.eq("process_id", processId).ge("stat_date", start).orderByAsc("stat_date", "sample_no");
        List<ProdSpcSample> samples = spcSampleMapper.selectList(qw);
        Map<Date, List<Double>> grouped = new LinkedHashMap<>();
        for (ProdSpcSample s : samples) {
            grouped.computeIfAbsent(s.getStatDate(), k -> new ArrayList<>()).add(s.getValue());
        }
        List<List<Double>> subgroups = new ArrayList<>();
        List<Date> dates = new ArrayList<>();
        for (Map.Entry<Date, List<Double>> e : grouped.entrySet()) {
            List<Double> group = e.getValue();
            if (group.size() == 5) {
                subgroups.add(group);
                dates.add(e.getKey());
            }
        }
        QualityVO.SpcResult vo = new QualityVO.SpcResult();
        vo.setXBar(new ArrayList<>());
        vo.setRPoints(new ArrayList<>());
        vo.setOutOfControl(new ArrayList<>());
        if (subgroups.isEmpty()) {
            vo.setXBarUcl(0.0); vo.setXBarLcl(0.0); vo.setXBarCenter(0.0);
            vo.setRUcl(0.0); vo.setRLcl(0.0); vo.setRCenter(0.0);
            return vo;
        }
        SpcCalculator.Result calc = SpcCalculator.compute(subgroups, 5);
        List<SpcCalculator.OutOfControl> ooc = calc.getOutOfControl();
        for (int i = 0; i < subgroups.size(); i++) {
            List<Double> g = subgroups.get(i);
            double mean = g.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double min = g.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = g.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            QualityVO.SpcPoint xp = new QualityVO.SpcPoint();
            xp.setDate(fmt(dates.get(i)));
            xp.setValue(round2(mean));
            vo.getXBar().add(xp);
            QualityVO.SpcPoint rp = new QualityVO.SpcPoint();
            rp.setDate(fmt(dates.get(i)));
            rp.setValue(round2(max - min));
            vo.getRPoints().add(rp);
            for (SpcCalculator.OutOfControl o : ooc) {
                if (o.getIndex() == i) {
                    QualityVO.OutOfControlPoint op = new QualityVO.OutOfControlPoint();
                    op.setDate(fmt(dates.get(i)));
                    op.setIndex(i);
                    op.setType(o.getType());
                    vo.getOutOfControl().add(op);
                }
            }
        }
        vo.setXBarUcl(round2(calc.getXBarUcl()));
        vo.setXBarLcl(round2(calc.getXBarLcl()));
        vo.setXBarCenter(round2(calc.getXBarCenter()));
        vo.setRUcl(round2(calc.getRUcl()));
        vo.setRLcl(round2(calc.getRLcl()));
        vo.setRCenter(round2(calc.getRBar()));
        return vo;
    }

    @Override
    public QualityVO.ProcessDefectMatrix defectProcessMatrix(Long lineId, int days) {
        Date start = startDate(days);
        QueryWrapper<ProdDefectRecord> qw = new QueryWrapper<>();
        qw.eq(lineId != null && lineId > 0, "line_id", lineId).ge("stat_date", start);
        List<ProdDefectRecord> records = defectRecordMapper.selectList(qw);
        Map<String, String> processNameById = new LinkedHashMap<>();
        if (lineId != null && lineId > 0) {
            QueryWrapper<ProdProcess> pq = new QueryWrapper<>();
            pq.eq("line_id", lineId).orderByAsc("seq");
            for (ProdProcess p : processMapper.selectList(pq)) {
                processNameById.put(String.valueOf(p.getId()), p.getName());
            }
        } else {
            for (ProdProcess p : processMapper.selectList(new QueryWrapper<>())) {
                processNameById.put(String.valueOf(p.getId()), p.getName());
            }
        }
        List<String> processes = new ArrayList<>(processNameById.values());
        List<String> defects = records.stream().map(ProdDefectRecord::getDefectCode).distinct()
                .sorted().collect(Collectors.toList());
        List<List<Integer>> matrix = new ArrayList<>();
        for (String procId : processNameById.keySet()) {
            List<Integer> row = new ArrayList<>();
            for (String dc : defects) {
                int qty = records.stream()
                        .filter(r -> r.getProcessId().toString().equals(procId)
                                && r.getDefectCode().equals(dc))
                        .mapToInt(ProdDefectRecord::getQty).sum();
                row.add(qty);
            }
            matrix.add(row);
        }
        QualityVO.ProcessDefectMatrix vo = new QualityVO.ProcessDefectMatrix();
        vo.setProcesses(processes);
        vo.setDefects(defects);
        vo.setMatrix(matrix);
        return vo;
    }

    @Override
    public QualityVO.Summary summary(Long lineId, int days) {
        List<QualityVO.YieldPoint> yieldTrend = yieldTrend(lineId, days);
        List<QualityVO.OeePoint> oeeTrend = oeeTrend(lineId, days);
        QualityVO.Summary vo = new QualityVO.Summary();
        if (yieldTrend.isEmpty() || oeeTrend.isEmpty()) {
            vo.setYield(0.0); vo.setOee(0.0); vo.setDefectTotal(0);
            vo.setYieldDelta(0.0); vo.setOeeDelta(0.0);
            return vo;
        }
        vo.setYield(round2(yieldTrend.get(yieldTrend.size() - 1).getYield()));
        vo.setOee(round2(oeeTrend.get(oeeTrend.size() - 1).getOee()));
        QueryWrapper<ProdDefectRecord> qw = new QueryWrapper<>();
        qw.eq(lineId != null && lineId > 0, "line_id", lineId).ge("stat_date", startDate(days));
        List<ProdDefectRecord> records = defectRecordMapper.selectList(qw);
        vo.setDefectTotal(records.stream().mapToInt(ProdDefectRecord::getQty).sum());
        vo.setYieldDelta(round2(yieldTrend.get(yieldTrend.size() - 1).getYield()
                - yieldTrend.get(0).getYield()));
        vo.setOeeDelta(round2(oeeTrend.get(oeeTrend.size() - 1).getOee()
                - oeeTrend.get(0).getOee()));
        return vo;
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
