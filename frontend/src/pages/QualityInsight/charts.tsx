import React, { useEffect, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import {
  getYieldTrend,
  getOeeTrend,
  getSpc,
  getDefectDistribution,
  getDefectProcessMatrix,
  YieldPoint,
  OeePoint,
  SpcResult,
  DefectItem,
  ProcessDefectMatrix,
} from '@/services/quality';

const useData = <T,>(fetcher: () => Promise<T>, deps: unknown[] = []) => {
  const [data, setData] = useState<T | null>(null);
  useEffect(() => {
    fetcher().then(setData);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return data;
};

const LINE_COLORS = ['#1677ff', '#52c41a', '#faad14', '#f5222d', '#722ed1'];

export const YieldChart: React.FC<{ lineId: number }> = ({ lineId }) => {
  const data = useData(() => getYieldTrend(30, lineId), [lineId]);
  if (!data || data.length === 0) return <div>暂无数据</div>;
  const dates = data.map((d: YieldPoint) => d.date);
  return (
    <ReactECharts
      style={{ height: 320 }}
      option={{
        tooltip: { trigger: 'axis' },
        legend: { data: ['良率', '基线'] },
        xAxis: { type: 'category', data: dates },
        yAxis: { type: 'value', min: 90, max: 100 },
        series: [
          { name: '良率', type: 'line', data: data.map((d: YieldPoint) => d.yield), smooth: true },
          { name: '基线', type: 'line', data: data.map((d: YieldPoint) => d.target), lineStyle: { type: 'dashed' } },
        ],
      }}
    />
  );
};

export const OeeChart: React.FC<{ lineId: number }> = ({ lineId }) => {
  const data = useData(() => getOeeTrend(30, lineId), [lineId]);
  if (!data || data.length === 0) return <div>暂无数据</div>;
  return (
    <ReactECharts
      style={{ height: 320 }}
      option={{
        tooltip: { trigger: 'axis' },
        legend: { data: ['OEE', '可用率', '性能', '质量'] },
        xAxis: { type: 'category', data: data.map((d: OeePoint) => d.date) },
        yAxis: { type: 'value', min: 70, max: 100 },
        series: [
          { name: 'OEE', type: 'line', data: data.map((d: OeePoint) => d.oee), lineStyle: { width: 3 } },
          { name: '可用率', type: 'line', data: data.map((d: OeePoint) => d.availability), lineStyle: { type: 'dashed' } },
          { name: '性能', type: 'line', data: data.map((d: OeePoint) => d.performance), lineStyle: { type: 'dashed' } },
          { name: '质量', type: 'line', data: data.map((d: OeePoint) => d.quality), lineStyle: { type: 'dashed' } },
        ],
      }}
    />
  );
};

export const SpcChart: React.FC<{ processId: number }> = ({ processId }) => {
  const data = useData(() => getSpc(processId, 30), [processId]);
  if (!data || data.xBar.length === 0) return <div>暂无数据</div>;
  const dates = data.xBar.map((p) => p.date);
  const controlLine = (name: string, value: number, yAxisIndex = 0) => ({
    name,
    type: 'line' as const,
    yAxisIndex,
    data: dates.map(() => value),
    lineStyle: { type: 'dashed' as const },
    symbol: 'none',
  });
  const oocMark = data.outOfControl
    .filter((p) => p.type === 'xbar')
    .map((p) => ({ coord: [dates[p.index], data.xBar[p.index].value] as [string, number] }));
  return (
    <ReactECharts
      style={{ height: 380 }}
      option={{
        tooltip: { trigger: 'axis' },
        legend: { data: ['X̄', 'UCL', 'LCL', '中心线', 'R', 'R-UCL'] },
        xAxis: { type: 'category', data: dates },
        yAxis: [
          { type: 'value', name: 'X̄' },
          { type: 'value', name: 'R', scale: true },
        ],
        series: [
          {
            name: 'X̄', type: 'line', data: data.xBar.map((p) => p.value), smooth: true,
            markPoint: {
              symbol: 'pin', symbolSize: 42, itemStyle: { color: '#f5222d' },
              label: { show: false },
              data: oocMark,
            },
          },
          controlLine('UCL', data.xBarUcl),
          controlLine('LCL', data.xBarLcl),
          controlLine('中心线', data.xBarCenter),
          { name: 'R', type: 'line', yAxisIndex: 1, data: data.rPoints.map((p) => p.value), smooth: true },
          controlLine('R-UCL', data.rUcl, 1),
        ],
      }}
    />
  );
};

export const DefectParetoChart: React.FC<{ lineId: number }> = ({ lineId }) => {
  const data = useData(() => getDefectDistribution(30, lineId), [lineId]);
  if (!data || data.length === 0) return <div>暂无数据</div>;
  const names = data.map((d: DefectItem) => d.defectName);
  return (
    <ReactECharts
      style={{ height: 320 }}
      option={{
        tooltip: { trigger: 'axis' },
        legend: { data: ['缺陷数', '累计占比'] },
        xAxis: { type: 'category', data: names },
        yAxis: [
          { type: 'value', name: '缺陷数' },
          { type: 'value', name: '累计占比', max: 100 },
        ],
        series: [
          { name: '缺陷数', type: 'bar', data: data.map((d: DefectItem) => d.qty), itemStyle: { color: LINE_COLORS[0] } },
          { name: '累计占比', type: 'line', yAxisIndex: 1, data: data.map((d: DefectItem) => d.pct), lineStyle: { color: LINE_COLORS[2] } },
        ],
      }}
    />
  );
};

export const DefectProcessHeatmap: React.FC<{ lineId: number }> = ({ lineId }) => {
  const data = useData(() => getDefectProcessMatrix(30, lineId), [lineId]);
  if (!data || data.processes.length === 0) return <div>暂无数据</div>;
  const defectNames: Record<string, string> = {
    surface_scratch: '划伤', assembly_defect: '装配不良', weld_defect: '焊接缺陷',
    dimension_error: '尺寸偏差', leak_test: '泄漏',
  };
  const values = data.matrix
    .flatMap((row, x) => row.map((v, y) => [y, x, v] as [number, number, number]));
  return (
    <ReactECharts
      style={{ height: 320 }}
      option={{
        tooltip: { position: 'top' },
        grid: { left: 120, bottom: 90 },
        xAxis: { type: 'category', data: data.defects.map((d) => defectNames[d] || d), splitArea: { show: true } },
        yAxis: { type: 'category', data: data.processes, splitArea: { show: true } },
        visualMap: { min: 0, max: Math.max(...values.map((v) => v[2]), 1), calculable: true, orient: 'horizontal', left: 'center', bottom: 0 },
        series: [
          {
            type: 'heatmap',
            data: values,
            label: { show: true },
          },
        ],
      }}
    />
  );
};
