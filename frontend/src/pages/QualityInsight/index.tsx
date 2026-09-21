import { PageContainer } from '@ant-design/pro-components';
import { Card, Col, Row, Statistic, Select } from 'antd';
import React, { useEffect, useState } from 'react';
import { getSummary, Summary } from '@/services/quality';
import { YieldChart, OeeChart, SpcChart, DefectParetoChart, DefectProcessHeatmap } from './charts';

const QualityInsight: React.FC = () => {
  const [lineId, setLineId] = useState<number>(1);
  const [summary, setSummary] = useState<Summary | null>(null);

  useEffect(() => {
    getSummary(7, lineId).then((data) => setSummary(data));
  }, [lineId]);

  return (
    <PageContainer
      title="制造质量数据洞察"
      extra={
        <Select
          value={lineId}
          style={{ width: 160 }}
          onChange={setLineId}
          options={[
            { value: 1, label: '冲压线' },
            { value: 2, label: '焊接线' },
            { value: 3, label: '总装线' },
          ]}
        />
      }
    >
      <Row gutter={16}>
        <Col span={6}>
          <Card><Statistic title="当前良率" value={summary?.yield ?? 0} suffix="%" /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="当前 OEE" value={summary?.oee ?? 0} suffix="%" /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="近 7 日缺陷" value={summary?.defectTotal ?? 0} /></Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="良率环比" value={summary?.yieldDelta ?? 0} suffix="%" />
          </Card>
        </Col>
      </Row>
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={12}><Card title="良率趋势"><YieldChart lineId={lineId} /></Card></Col>
        <Col span={12}><Card title="OEE 趋势与三因子"><OeeChart lineId={lineId} /></Card></Col>
      </Row>
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={12}><Card title="缺陷分布（帕累托）"><DefectParetoChart lineId={lineId} /></Card></Col>
        <Col span={12}><Card title="缺陷-工序相关性"><DefectProcessHeatmap lineId={lineId} /></Card></Col>
      </Row>
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={24}>
          <Card title="SPC 控制图（冲压线·成型工序）"><SpcChart processId={2} /></Card>
        </Col>
      </Row>
    </PageContainer>
  );
};

export default QualityInsight;
