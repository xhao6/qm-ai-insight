# 制造质量数据模型规范

> 本文档是数据管道的唯一权威口径定义。所有生成器、查询、前端展示必须严格遵循此文档。

---

## 1. 数据纪律声明

- **全部演示数据由自建模拟数据生成器产出**，不引入任何真实业务数据。
- 生成器参数化，支持制造/销售双模板切换（本文档仅覆盖制造模板）。
- 每张表的 `create_time` / `update_time` 由生成器写入，时区统一 UTC+8。

---

## 2. 指标口径

### 2.1 良率 (Yield)

**定义**：良率 = 通过质检的合格产品数量 / 当日总产出数量 × 100%

**基线与波动区间**：

| 参数 | 值 | 说明 |
|------|-----|------|
| 基线目标 | ≥95% | 口径下限；允许日度波动 [94.5%, 99.5%] |
| 生成器中心值 | 96.5% | 正态分布均值，确保日度良率大多 ≥95% |

生成器在 [94.5%, 99.5%] 区间内以 96.5% 为均值生成随机良率值，确保日度良率大多达到或超过 95% 基线目标。

> **良率与 OEE 的关系**：良率直接对应 OEE 三分量中的 **质量分量 (Quality)**，二者数值完全相同（见 2.2 节）。

### 2.2 OEE (Overall Equipment Effectiveness)

**公式**：

```
OEE = 可用率 (Availability) × 性能 (Performance) × 质量分量 (Quality)
```

**三个分量的定义与生成区间**：

| 分量 | 定义 | 公式 | 生成区间 | 标杆 |
|------|------|------|----------|------|
| 可用率 (A) | 实际运行时间 / 计划生产时间 | `运行时间 / 计划时间` | 90% ~ 98% | 95% |
| 性能 (P) | 实际产出速率 / 理想产出速率 | `(产出数量 × ideal_cycle) / 运行时间` | 88% ~ 96% | 92% |
| 质量分量 (Q) | 合格品数量 / 总产出数量 | 即良率 | 94.5% ~ 99.5% | 96.5% |

**产能推导口径**：

```
output = (可用时间 / ideal_cycle) × 可用率 × 性能
```

其中：
- `可用时间` = 计划生产时间（由班次定义，如 8h/班）
- `ideal_cycle` = 理想节拍时间（单位：秒/件），各工序不同
- `output` = 该工序理论产出数量（整数，向下取整）

保证三分量乘积自洽落在区间内：OEE 总值范围 **74% ~ 94%**，标杆 **85%**。

**示例**：若某工序 ideal_cycle = 10s，计划时间 = 28800s（8小时），可用率 = 95%，性能 = 92%，质量分量 = 97%：
- 产出 = (28800 / 10) × 0.95 × 0.92 = 2540 件
- OEE = 0.95 × 0.92 × 0.97 = 0.848 ≈ 84.8%

### 2.3 SPC 控制图 (X̄-R Chart)

**适用范围**：仅用于**计量型参数**（如冲压件厚度、焊缝宽度等连续型测量值）。**计数型质检工序**（如表面缺陷目检）**不产生 SPC 样本**。

**控制限公式**：

```
X̄ 图：
  UCL = X̄̄ + A2 × R̄
  CL  = X̄̄
  LCL = X̄̄ - A2 × R̄

R 图：
  UCL = D4 × R̄
  CL  = R̄
  LCL = D3 × R̄
```

其中：
- X̄̄ = 所有子组均值的均值
- R̄ = 所有子组极差的均值
- n = 子组大小（取样件数）

**常用常数表（n = 2 ~ 5）**：

| 子组大小 n | A2    | D3  | D4    |
|------------|-------|-----|-------|
| 2          | 1.880 | 0   | 3.267 |
| 3          | 1.023 | 0   | 2.574 |
| 4          | 0.729 | 0   | 2.282 |
| 5          | 0.577 | 0   | 2.114 |

> 本项目默认子组大小 **n = 5**，对应 A2 = 0.577, D3 = 0, D4 = 2.114。

**出界点判定**：数据点超出控制限（UCL/LCL）即为**出界点**。出界点表示**过程失控信号**，**不等于不合格品**——控制限与规格限是两个独立概念（见 FAQ 2.6.2）。

### 2.4 缺陷-工序相关性

**权重矩阵**：定义每种缺陷类型在各工序的发生概率权重，用于生成器分配缺陷到对应工序。

```
缺陷权重矩阵 (行=缺陷类型, 列=工序)
              下料  成型  修边  质检  点焊  弧焊  打磨
surface_scratch  0.1  0.3  0.2  0.0  0.1  0.0  0.3
assembly_defect  0.0  0.0  0.0  0.0  0.0  0.0  0.0  (仅总装)
weld_defect      0.0  0.0  0.0  0.0  0.4  0.5  0.1
dimension_error  0.3  0.4  0.2  0.0  0.0  0.0  0.1
leak_test        0.0  0.0  0.0  0.0  0.3  0.3  0.4
```

> 实际权重表以生成器代码 `pipeline/generator.py` 中的 `DEFECT_PROCESS_WEIGHTS` 字典为准。

**热力图解读**：热力图以颜色深浅表示缺陷类型在各工序的相对发生概率。颜色越深表示该缺陷类型在该工序越容易发生，用于指导质量改进优先级。

### 2.5 缺陷两口径一致性

系统中存在两个记录缺陷数量的字段：

| 来源表 | 字段 | 含义 |
|--------|------|------|
| `prod_defect_record` | `qty` | 按缺陷类型逐条记录的缺陷数量 |
| `prod_daily_metrics` | `defect_qty` | 当日汇总的缺陷总数 |

**一致性约束**：

```sql
SUM(prod_defect_record.qty) WHERE process_date = ? AND line_id = ?
    = prod_daily_metrics.defect_qty WHERE metric_date = ? AND line_id = ?
```

生成器保证：每日各工序各缺陷类型的 `qty` 之和，等于该日该产线的 `defect_qty`。

### 2.6 季节因子

**定义**：季节因子是一个长度为 12 的数组，作用于**计划量/需求侧**，反映月度产能波动。

```python
SEASON_FACTOR = [1.05, 0.92, 1.02, 1.00, 1.03, 0.98, 1.01, 1.00, 1.04, 1.06, 0.95, 1.00]
#              Jan  Feb  Mar  Apr  May  Jun  Jul  Aug  Sep  Oct  Nov  Dec
```

**作用方式**：

```
adjusted_plan = base_plan × SEASON_FACTOR[month - 1]
```

- `base_plan` = 基础计划产量
- `adjusted_plan` = 调整后的计划产量
- 季节因子**不参与 OEE 计算**，仅影响计划排产

**季节因子与 OEE 的关系**：季节因子通过调整计划量间接影响"计划生产时间"，进而影响 OEE 可用率分量的分母（计划时间），但不影响可用率分子（实际运行时间）和性能、质量分量。

---

## 3. 表结构 (DDL)

### 3.1 prod_line（产线）

```sql
CREATE TABLE IF NOT EXISTS prod_line (
  id bigint AUTO_INCREMENT PRIMARY KEY,
  name varchar(64) NOT NULL,
  plant varchar(64) NOT NULL,
  create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
  is_delete tinyint DEFAULT 0 NOT NULL
) COMMENT='产线' collate utf8mb4_unicode_ci;
```

### 3.2 prod_process（工序）

```sql
CREATE TABLE IF NOT EXISTS prod_process (
  id bigint AUTO_INCREMENT PRIMARY KEY,
  line_id bigint NOT NULL,
  name varchar(64) NOT NULL,
  seq int NOT NULL,
  param_name varchar(64) NOT NULL,
  param_target double NOT NULL,
  param_usl double NOT NULL,
  param_lsl double NOT NULL,
  create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
  is_delete tinyint DEFAULT 0 NOT NULL,
  KEY idx_line (line_id)
) COMMENT='工序' collate utf8mb4_unicode_ci;
```

### 3.3 prod_daily_metrics（产线日指标）

```sql
CREATE TABLE IF NOT EXISTS prod_daily_metrics (
  id bigint AUTO_INCREMENT PRIMARY KEY,
  stat_date date NOT NULL,
  line_id bigint NOT NULL,
  plan_qty int NOT NULL,
  output_qty int NOT NULL,
  good_qty int NOT NULL,
  defect_qty int NOT NULL,
  available_time_min int NOT NULL,
  run_time_min int NOT NULL,
  ideal_cycle_min double NOT NULL,
  actual_cycle_min double NOT NULL,
  create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
  is_delete tinyint DEFAULT 0 NOT NULL,
  UNIQUE KEY uk_date_line (stat_date, line_id)
) COMMENT='产线日指标' collate utf8mb4_unicode_ci;
```

### 3.4 prod_defect_record（缺陷记录）

```sql
CREATE TABLE IF NOT EXISTS prod_defect_record (
  id bigint AUTO_INCREMENT PRIMARY KEY,
  stat_date date NOT NULL,
  line_id bigint NOT NULL,
  process_id bigint NOT NULL,
  defect_code varchar(32) NOT NULL,
  defect_name varchar(64) NOT NULL,
  qty int NOT NULL,
  severity tinyint NOT NULL COMMENT '1轻微 2一般 3严重',
  create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
  is_delete tinyint DEFAULT 0 NOT NULL,
  KEY idx_date_line (stat_date, line_id)
) COMMENT='缺陷记录' collate utf8mb4_unicode_ci;
```

### 3.5 prod_spc_sample（SPC 样本）

```sql
CREATE TABLE IF NOT EXISTS prod_spc_sample (
  id bigint AUTO_INCREMENT PRIMARY KEY,
  stat_date date NOT NULL,
  process_id bigint NOT NULL,
  sample_no tinyint NOT NULL COMMENT '子组内序号 1-5',
  value double NOT NULL,
  create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
  is_delete tinyint DEFAULT 0 NOT NULL,
  KEY idx_date_process (stat_date, process_id)
) COMMENT='SPC样本' collate utf8mb4_unicode_ci;
```

> **`actual_cycle_min` 说明**：`prod_daily_metrics.actual_cycle_min` 由生成器写入（= ideal_cycle_min / performance），供未来节拍分析使用。当前 OEE 性能分量计算使用 `ideal_cycle_min` 而非实际节拍。

**两口径一致性保证**：生成器按工序权重×类型权重两级最大余数法分摊日 `defect_qty` 到 `prod_defect_record`，确保 `SUM(qty)` 严格等于 `prod_daily_metrics.defect_qty`。

---

## 4. 生成器参数

### 4.1 产线与工序定义

```python
LINES = [
    {"name": "冲压线", "plant": "Plant A",
     "processes": [("下料", "板厚", 1.20, 1.28, 1.12), ("成型", "板厚", 1.20, 1.28, 1.12),
                   ("修边", "毛刺高度", 0.30, 0.45, 0.15), ("质检", "外观缺陷数", 0.0, 2.0, 0.0)]},
    {"name": "焊接线", "plant": "Plant A",
     "processes": [("点焊", "焊接强度", 18.0, 19.5, 16.5), ("弧焊", "焊接强度", 18.0, 19.5, 16.5),
                   ("打磨", "表面粗糙度", 3.2, 4.0, 2.4), ("质检", "焊缝缺陷数", 0.0, 3.0, 0.0)]},
    {"name": "总装线", "plant": "Plant B",
     "processes": [("装配", "扭矩", 25.0, 27.0, 23.0), ("拧紧", "扭矩", 25.0, 27.0, 23.0),
                   ("检测", "泄漏率", 0.25, 0.5, 0.05), ("包装", "包装缺陷数", 0.0, 1.0, 0.0)]},
]
```

每条线 4 道工序，元组格式：`(工序名, 参数名, 目标值, USL, LSL)`。计数型参数（外观缺陷数/焊缝缺陷数/包装缺陷数/泄漏率以外的计数工序）不适用 X̄-R 控制图。

### 4.2 计数型参数前缀

```python
COUNTING_PARAM_PREFIXES = ("外观", "焊缝", "包装")
```

生成器与 pytest 共用此常量：命中前缀的工序不产 SPC 样本（X̄-R 仅适用计量型参数）。

### 4.3 季节因子

```python
SEASON_FACTOR = [1.05, 0.92, 1.02, 1.00, 1.03, 0.98, 1.01, 1.00, 1.04, 1.06, 0.95, 1.00]
#              1月   2月   3月   4月   5月   6月   7月   8月   9月  10月  11月  12月
```

### 4.4 缺陷类型定义

```python
DEFECT_TYPES = [
    ("surface_scratch", "划伤", 1),
    ("assembly_defect", "装配不良", 2),
    ("weld_defect", "焊接缺陷", 3),
    ("dimension_error", "尺寸偏差", 2),
    ("leak_test", "泄漏", 3),
]
```

元组格式：`(缺陷代码, 中文名称, 严重等级)`。严重等级：1=轻微, 2=一般, 3=严重。

### 4.5 缺陷-工序权重矩阵

键 = `(产线, 工序)` 组合（避免跨线同名工序键覆盖），值为 DEFECT_TYPES 顺序的权重，行和为 1：

```python
DEFECT_PROCESS_MATRIX = {
    ("冲压线", "下料"): [0.55, 0.10, 0.05, 0.20, 0.10],
    ("冲压线", "成型"): [0.60, 0.05, 0.05, 0.25, 0.05],
    ("冲压线", "修边"): [0.35, 0.10, 0.10, 0.40, 0.05],
    ("冲压线", "质检"): [0.25, 0.15, 0.15, 0.35, 0.10],
    ("焊接线", "点焊"): [0.10, 0.10, 0.60, 0.10, 0.10],
    ("焊接线", "弧焊"): [0.05, 0.05, 0.70, 0.10, 0.10],
    ("焊接线", "打磨"): [0.30, 0.10, 0.35, 0.15, 0.10],
    ("焊接线", "质检"): [0.10, 0.15, 0.55, 0.10, 0.10],
    ("总装线", "装配"): [0.10, 0.55, 0.05, 0.20, 0.10],
    ("总装线", "拧紧"): [0.05, 0.60, 0.05, 0.15, 0.15],
    ("总装线", "检测"): [0.10, 0.15, 0.10, 0.25, 0.40],
    ("总装线", "包装"): [0.40, 0.25, 0.05, 0.25, 0.05],
}
```

每工序一个主贡献缺陷类型（35%-70%），其余按固定权重（0.05-0.40）。

### 4.6 工序缺陷占比权重

每线各工序的缺陷占比（与 LINES 工序顺序一致，行和为 1）——用于把日指标 `defect_qty` 分摊到工序：

```python
PROCESS_DEFECT_WEIGHT = {
    "冲压线": [0.35, 0.30, 0.20, 0.15],
    "焊接线": [0.30, 0.30, 0.25, 0.15],
    "总装线": [0.30, 0.25, 0.25, 0.20],
}
```

---

## 5. 口径答疑 FAQ

### 5.1 OEE 为何采用三分量模型？

OEE（Overall Equipment Effectiveness）是制造业通用的设备综合效率指标，由三个独立分量相乘得到：

- **可用率**回答"设备有多少时间在运转"
- **性能**回答"运转时是否达到设计速度"
- **质量分量**回答"产出中有多少是合格品"

三者相乘消除了单一指标的片面性。例如：仅看产出数量高，可能是以牺牲质量为代价；仅看良率高，可能是设备长期停机只生产少量精品。OEE 让管理者同时看到三个维度的表现，是设备效率的综合衡量。

### 5.2 SPC 出界点如何判定？控制限与规格限有什么区别？

**出界点判定**：当数据点超出控制限（UCL 或 LCL）时，判定为出界点。出界点是一个**过程失控信号**，表示该工序的统计分布发生了异常偏移或散差增大。

**控制限 vs 规格限**：

| 概念 | 控制限 (UCL/LCL) | 规格限 (USL/LSL) |
|------|-------------------|-------------------|
| 来源 | 过程数据计算（X̄̄ ± A2×R̄） | 客户/设计要求 |
| 含义 | 过程自身的自然波动边界 | 产品可接受的允许范围 |
| 性质 | 动态，随过程变化 | 固定不变 |

**关键区别**：一个数据点超出控制限**不代表它是不合格品**。例如：控制限是 2.00 ± 0.03（过程散差），规格限是 1.95 ~ 2.05（客户要求）。若测得 2.035，超出控制限但仍在规格限内——它是合格品，但过程已失控，需要调查原因。

### 5.3 良率与 OEE 质量分量的关系

良率 = 合格品数 / 总产出数，这与 OEE 质量分量的定义完全一致。因此：

```
OEE 中的质量分量 = 良率值
```

二者使用相同数据、相同公式，只是在不同上下文中被引用：良率是质量管理的直接指标，质量分量是 OEE 体系中的一个分量。

### 5.4 季节因子如何影响产能规划？

季节因子作用于**计划产量**（需求侧），不直接参与 OEE 计算：

```
调整后计划量 = 基础计划量 × 月度季节因子
```

- 旺季（如 10 月因子 1.06）：计划量上浮 6%，需要更多资源投入
- 淡季（如 2 月因子 0.92）：计划量下调 8%，可安排设备维护

季节因子通过调整计划量间接影响 OEE 的"计划生产时间"分母：计划量增加通常意味着加班或增加班次，计划时间增加；反之减少。但实际运行时间和性能、质量分量不受季节因子直接驱动。

### 5.5 为什么计数型质检工序不用计量控制图？

计数型质检工序（如目检、包装后检）的输出是"合格/不合格"的二分类结果，不是连续的计量测量值。X̄-R 控制图需要**连续型数据**（如尺寸、温度、压力）才能计算均值和极差。

对于计数型数据，应使用：
- **p 图**（不合格品率控制图）
- **np 图**（不合格品数控制图）
- **c 图**（缺陷数控制图）
- **u 图**（单位缺陷数控制图）

本项目中，计数型质检工序（各线第 4 道工序）仅记录缺陷数量，不产生 SPC 样本数据。计量型工序（第 1~3 道中 `is计量型=1` 的工序）才产出 X̄-R 控制图数据。

### 5.6 OEE 与良率的综合关系

OEE 综合反映设备效率，良率是其质量维度：

```
OEE = 可用率 × 性能 × 良率
```

- 良率下降 → OEE 下降，即使设备运转正常且速度达标
- OEE 下降但良率不变 → 问题在可用率或性能维度
- OEE 目标 85% 需要三个分量协同达标，单一指标优秀不足以保证整体效率

这种分解使得改进方向明确：是该修设备（提升可用率）、调速度（提升性能），还是抓质量（提升良率）。
