import { request } from '@umijs/max';

interface BaseResponse<T> {
  code: number;
  data: T;
  message: string;
}

async function get<T>(url: string): Promise<T> {
  const res = await request<BaseResponse<T>>(url);
  if (res.code !== 0) {
    throw new Error(res.message || '请求失败');
  }
  return res.data;
}

export interface YieldPoint {
  date: string;
  yield: number;
  target: number;
}

export interface OeePoint {
  date: string;
  oee: number;
  availability: number;
  performance: number;
  quality: number;
}

export interface DefectItem {
  defectCode: string;
  defectName: string;
  qty: number;
  pct: number;
}

export interface SpcPoint {
  date: string;
  value: number;
}

export interface OutOfControlPoint {
  date: string;
  index: number;
  type: string;
}

export interface SpcResult {
  xBar: SpcPoint[];
  rPoints: SpcPoint[];
  xBarUcl: number;
  xBarLcl: number;
  xBarCenter: number;
  rUcl: number;
  rLcl: number;
  rCenter: number;
  outOfControl: OutOfControlPoint[];
}

export interface ProcessDefectMatrix {
  processes: string[];
  defects: string[];
  matrix: number[][];
}

export interface Summary {
  yield: number;
  oee: number;
  defectTotal: number;
  yieldDelta: number;
  oeeDelta: number;
}

export const getSummary = (days = 7, lineId = 1) =>
  get<Summary>(`/api/quality/summary?days=${days}&lineId=${lineId}`);

export const getYieldTrend = (days = 30, lineId = 1) =>
  get<YieldPoint[]>(`/api/quality/yield/trend?days=${days}&lineId=${lineId}`);

export const getOeeTrend = (days = 30, lineId = 1) =>
  get<OeePoint[]>(`/api/quality/oee/trend?days=${days}&lineId=${lineId}`);

export const getDefectDistribution = (days = 30, lineId = 1) =>
  get<DefectItem[]>(`/api/quality/defect/distribution?days=${days}&lineId=${lineId}`);

export const getSpc = (processId: number, days = 30) =>
  get<SpcResult>(`/api/quality/spc?processId=${processId}&days=${days}`);

export const getDefectProcessMatrix = (days = 30, lineId = 1) =>
  get<ProcessDefectMatrix>(`/api/quality/defect/process?days=${days}&lineId=${lineId}`);
