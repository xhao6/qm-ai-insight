# W1 制造质量数据洞察 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已跑通的智能 BI 基线（Spring Boot 2.7 后端 :8080 + Ant Design Pro 前端 :8000 + MySQL/RabbitMQ/Redis 容器）之上，增量实现制造质量数据洞察模块：数据口径文档 → Python 生成器直写 MySQL → 后端洞察接口 → 前端 ECharts 图表页。

**Architecture:** 遵循「先文档 → 再生成器 → 再接口/图表」顺序。`docs/data-model.md` 定义口径（良率/OEE/SPC/缺陷-工序相关性/季节因子，口径即核心知识沉淀）；`pipeline/`（Python, 独立子目录不入 Spring 构建）按口径生成 5 张 `prod_*` 表并直写 MySQL；后端在 `com.yupi.springbootinit` 现有分层（entity/mapper/service/controller/utils/vo）内增量新增 `quality` 洞察接口（查库+Java 计算 SPC 控制限，不调 AI）；前端新增 `/quality` 路由与 ECharts 组件化图表页。全程不破坏 yubi 原生链路（登录/上传/AI 生成图表）。

**Tech Stack:** Python 3.9+（random/math，仅 pymysql 一个第三方依赖）· MySQL 8 · Spring Boot 2.7 + MyBatis-Plus · JUnit 5 · React 18 + Ant Design Pro + echarts-for-react

**前提（已完成，勿重复）：** 本地基线跑通——后端 `mvn org.springframework.boot:spring-boot-maven-plugin:run`（JAVA_HOME=openjdk@17）:8080；前端 `./node_modules/.bin/max dev` :8000；容器 yubi-mysql/yubi-rabbitmq/yubi-redis 运行中；`backend/sql/create_table.sql` 已执行。
> 注：项目自带 `mvnw` 因 `.mvn/wrapper` 缺失不可用（基线验证已确认），本计划统一用 brew maven 的 `mvn`（3.9.16），所有 mvn 命令均需带 JAVA_HOME export（见各 Task 命令）。

---

## 数据口径总览（本计划一切数字的来源）

| 指标 | 口径 | 目标/基线 |
|------|------|----------|
| 良率 Yield | `good_qty / output_qty` | 基线 ≥ 95% |
| OEE | 可用率 Availability × 性能 Performance × 质量 Quality（三分量经产能推导自洽，int 截断允许 ±0.5% 舍入容差） | 标杆 85%（区间 74-94%） |
| 可用率 | `run_time_min / available_time_min` | 90–98% |
| 性能 | `(ideal_cycle_min × output_qty) / run_time_min` | 88–96% |
| 质量 | `good_qty / output_qty`（= 良率） | 95–99% |
| SPC | X̄-R 控制图，子组 n=5；X̄ 限 `X̄̄ ± A2·R̄`（A2=0.577），R 限 `[D3·R̄, D4·R̄]`（D3=0, D4=2.114） | 检出出界点 |
| 缺陷-工序相关性 | 缺陷类型 × 工序的贡献权重矩阵（生成器注入，接口输出热力图） | 可解释 |
| 季节因子 | 月度产量/良率波动系数数组（生成器参数） | 月度波动 |

## 文件结构

```
docs/data-model.md                          # T1 口径文档（权威）
pipeline/
  config.py                                 # T2 参数（产线/工序/日期/季节/相关性矩阵）
  db.py                                     # T2 pymysql 连接与建表
  generator.py                              # T3 核心生成逻辑
  main.py                                   # T4 CLI 入口（--days --seed --template）
  test_generator.py                         # T4 pytest 口径校验
  requirements.txt                          # T2 pymysql
backend/src/main/java/com/yupi/springbootinit/
  model/entity/ProdLine.java                # T5
  model/entity/ProdProcess.java             # T5
  model/entity/ProdDailyMetrics.java        # T5
  model/entity/ProdDefectRecord.java        # T5
  model/entity/ProdSpcSample.java           # T5
  mapper/ProdLineMapper.java                # T5（5 个 mapper 同批）
  mapper/ProdProcessMapper.java
  mapper/ProdDailyMetricsMapper.java
  mapper/ProdDefectRecordMapper.java
  mapper/ProdSpcSampleMapper.java
  utils/SpcCalculator.java                  # T6 SPC 控制限纯计算
  service/QualityService.java               # T7 聚合查询接口
  service/impl/QualityServiceImpl.java      # T7 实现
  controller/QualityController.java         # T8 REST 接口
  model/vo/QualityVO.java                   # T8 返回结构
backend/src/test/java/com/yupi/springbootinit/utils/SpcCalculatorTest.java   # T6
frontend/
  config/routes.ts                          # T10 加 /quality 路由
  src/services/quality.ts                   # T10 request 封装
  src/pages/QualityInsight/index.tsx        # T10 页面骨架（统计卡+图表挂载）
  src/pages/QualityInsight/charts.tsx       # T10 ECharts 图表组件（良率/OEE/SPC/帕累托/热力）
```

**接口契约（T8 交付，前端按此消费）：**

```
GET /api/quality/yield/trend?days=30&lineId=1
  → data: [{date:"2026-09-01", yield:96.2, target:95}, ...]
GET /api/quality/oee/trend?days=30&lineId=1
  → data: [{date, oee, availability, performance, quality}, ...]
GET /api/quality/defect/distribution?days=30&lineId=1
  → data: [{defectCode, defectName, qty, pct}, ...]（按 qty 降序，pct 累计占比）
GET /api/quality/spc?processId=1&days=30
  → data: {xBar: [{date, value}], rPoints: [{date, value}],
           xBarUcl, xBarLcl, rUcl, rLcl, xBarCenter, rCenter, outOfControl: [{date, index, type}]}
GET /api/quality/defect/process?days=30&lineId=1
  → data: {processes: ["成型","修边",...], defects: ["划伤",...], matrix: [[qty,...],...]}
GET /api/quality/summary?days=7&lineId=1
  → data: {yield, oee, defectTotal, yieldDelta, oeeDelta}
```

**SQL 口径（与生成器 DDL 一致，T5 entity 字段对齐）：**

```sql
CREATE TABLE IF NOT EXISTS prod_line (
  id bigint AUTO_INCREMENT PRIMARY KEY,
  name varchar(64) NOT NULL,
  plant varchar(64) NOT NULL,
  create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
  update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
  is_delete tinyint DEFAULT 0 NOT NULL
) COMMENT='产线' collate utf8mb4_unicode_ci;

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

**生成器参数（pipeline/config.py 常量，T2/T3 使用）：**

- 产线 3 条：冲压线 L1（工序：下料/成型/修边/质检，参数 板厚 mm target 1.20 usl 1.28 lsl 1.12）、焊接线 L2（点焊/弧焊/打磨/质检，焊接强度 kN target 18.0 usl 19.5 lsl 16.5）、总装线 L3（装配/拧紧/检测/包装，扭矩 N·m target 25.0 usl 27.0 lsl 23.0）
- 季节因子：`[1.05, 0.92, 1.02, 1.00, 1.03, 0.98, 1.01, 1.00, 1.04, 1.06, 0.95, 1.00]`（1-12 月，2 月春节低产）
- 缺陷类型：`surface_scratch 划伤 / assembly_defect 装配不良 / weld_defect 焊接缺陷 / dimension_error 尺寸偏差 / leak_test 泄漏`（severity: 划伤1 装配2 焊接3 尺寸2 泄漏3）
- 缺陷-工序权重矩阵（process × defect）：每工序一个主贡献缺陷类型（**35%-70%**），其余按固定权重（0.05-0.40）
- 每日每工序缺陷率：基线 0.8%–2.5%（与良率联动），随机波动

---

### Task 1: 数据口径文档 docs/data-model.md

**Files:**
- Create: `docs/data-model.md`

- [ ] **Step 1: 写入口径文档**

`docs/data-model.md` 内容必须覆盖（完整撰写，非占位）：项目数据纪律声明（演示数据自建、无真实业务数据）；**指标口径**：良率定义与**目标 95% 但允许日度波动 [94.5%, 99.5%]**（钳位下限即口径下限）、OEE 三分量公式与标杆 85% 及**产能推导口径（output = 可用时间/理想节拍 × 可用率 × 性能，保证三分量自洽落在区间）**、SPC X̄-R 控制限公式（含 A2/D3/D4 常数表 n=2..5，注明**仅适用计量型参数，计数型质检工序不产 SPC 样本**）、缺陷-工序相关性定义（权重矩阵与热力图解读）、**缺陷两口径一致性（prod_defect_record.qty 之和 = prod_daily_metrics.defect_qty）**、季节因子定义（作用于计划量/需求侧，不参与 OEE 计算）；**表结构**：5 张 `prod_*` 表 DDL（与上文 SQL 一致，逐字段注释口径来源，含 `actual_cycle_min` 用途说明：生成器写入供未来节拍分析，当前 OEE 性能分量用 ideal_cycle 计算）；**生成器参数**：产线/工序/参数规格限/季节因子/缺陷类型表/工序缺陷权重；**口径答疑 FAQ 章节**（公开仓库纪律：章节与全文禁止出现「面试/求职/简历」等词）：OEE 为何用三分量、SPC 出界点如何判定（控制限与规格限独立——出界点≠不合格品，仅过程失控信号）、良率与 OEE 质量分量关系、季节因子如何影响产能规划、为什么计数型质检工序不用计量控制图。

- [ ] **Step 2: 自查并提交**

Run: `grep -c "OEE" docs/data-model.md` 应 ≥ 5；确认无「求职/宝马/面试/简历」等词（`grep -nE "求职|宝马|华晨|面试|简历" docs/data-model.md` 无输出）。
Commit: `git add docs/data-model.md && git commit -m "docs: add manufacturing quality data model spec"`

---

### Task 2: pipeline 骨架（config / db / requirements）

**Files:**
- Create: `pipeline/config.py`
- Create: `pipeline/db.py`
- Create: `pipeline/requirements.txt`

- [ ] **Step 1: 写 config.py（参数常量，即数据口径的代码化）**

```python
"""制造质量数据生成器 - 参数配置（口径见 docs/data-model.md）"""
import os

DB_CONFIG = {
    "host": os.environ.get("DB_HOST", "127.0.0.1"),
    "port": int(os.environ.get("DB_PORT", "3306")),
    "user": os.environ.get("DB_USER", "root"),
    "password": os.environ.get("DB_PASSWORD", "123456"),  # 仅本地演示缺省；生产从环境变量注入
    "database": os.environ.get("DB_NAME", "yubi"),
    "charset": "utf8mb4",
}

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

SEASON_FACTOR = [1.05, 0.92, 1.02, 1.00, 1.03, 0.98, 1.01, 1.00, 1.04, 1.06, 0.95, 1.00]

DEFECT_TYPES = [
    ("surface_scratch", "划伤", 1), ("assembly_defect", "装配不良", 2),
    ("weld_defect", "焊接缺陷", 3), ("dimension_error", "尺寸偏差", 2),
    ("leak_test", "泄漏", 3),
]

# 计数型参数前缀（X̄-R 计量控制图仅适用计量型参数；命中前缀的工序不产 SPC 样本）
# 生成器与 pytest 共用此常量，避免两套判定规则分叉
COUNTING_PARAM_PREFIXES = ("外观", "焊缝", "包装")

# 缺陷-工序权重矩阵：键 = (产线, 工序) 组合（避免跨线同名工序键覆盖），值为 DEFECT_TYPES 顺序的权重，行和为 1
DEFECT_PROCESS_MATRIX = {
    ("冲压线", "下料"): [0.55, 0.10, 0.05, 0.20, 0.10], ("冲压线", "成型"): [0.60, 0.05, 0.05, 0.25, 0.05],
    ("冲压线", "修边"): [0.35, 0.10, 0.10, 0.40, 0.05], ("冲压线", "质检"): [0.25, 0.15, 0.15, 0.35, 0.10],
    ("焊接线", "点焊"): [0.10, 0.10, 0.60, 0.10, 0.10], ("焊接线", "弧焊"): [0.05, 0.05, 0.70, 0.10, 0.10],
    ("焊接线", "打磨"): [0.30, 0.10, 0.35, 0.15, 0.10], ("焊接线", "质检"): [0.10, 0.15, 0.55, 0.10, 0.10],
    ("总装线", "装配"): [0.10, 0.55, 0.05, 0.20, 0.10], ("总装线", "拧紧"): [0.05, 0.60, 0.05, 0.15, 0.15],
    ("总装线", "检测"): [0.10, 0.15, 0.10, 0.25, 0.40], ("总装线", "包装"): [0.40, 0.25, 0.05, 0.25, 0.05],
}

# 每线各工序的缺陷占比（与 LINES 工序顺序一致，行和为 1）——用于把日指标 defect_qty 分摊到工序
PROCESS_DEFECT_WEIGHT = {
    "冲压线": [0.35, 0.30, 0.20, 0.15],
    "焊接线": [0.30, 0.30, 0.25, 0.15],
    "总装线": [0.30, 0.25, 0.25, 0.20],
}
```

- [ ] **Step 2: 写 db.py（pymysql 连接 + 幂等建表）**

```python
"""数据库连接与建表（表结构与 docs/data-model.md 一致，幂等）"""
import pymysql
from config import DB_CONFIG

DDL_STATEMENTS = [
    """CREATE TABLE IF NOT EXISTS prod_line (
        id bigint AUTO_INCREMENT PRIMARY KEY,
        name varchar(64) NOT NULL,
        plant varchar(64) NOT NULL,
        create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
        update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
        is_delete tinyint DEFAULT 0 NOT NULL
    ) COMMENT='产线' collate utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS prod_process (
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
    ) COMMENT='工序' collate utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS prod_daily_metrics (
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
    ) COMMENT='产线日指标' collate utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS prod_defect_record (
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
    ) COMMENT='缺陷记录' collate utf8mb4_unicode_ci""",
    """CREATE TABLE IF NOT EXISTS prod_spc_sample (
        id bigint AUTO_INCREMENT PRIMARY KEY,
        stat_date date NOT NULL,
        process_id bigint NOT NULL,
        sample_no tinyint NOT NULL COMMENT '子组内序号 1-5',
        value double NOT NULL,
        create_time datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
        update_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
        is_delete tinyint DEFAULT 0 NOT NULL,
        KEY idx_date_process (stat_date, process_id)
    ) COMMENT='SPC样本' collate utf8mb4_unicode_ci""",
]


def get_connection():
    return pymysql.connect(**DB_CONFIG)


def init_db(connection):
    with connection.cursor() as cursor:
        for ddl in DDL_STATEMENTS:
            cursor.execute(ddl)
    connection.commit()
```

- [ ] **Step 3: 写 requirements.txt**

```
pymysql>=1.1.0
pytest>=7.0
```

- [ ] **Step 4: 验证骨架可导入 + 建表**

Run（在 `pipeline/` 下）：
```bash
python3 -m pip install -r requirements.txt
python3 -c "from db import init_db, get_connection; init_db(get_connection()); print('tables ok')"
docker exec yubi-mysql mysql -uroot -p123456 -e "USE yubi; SHOW TABLES;"  # 应含 5 张 prod_* 表
```
Expected: `tables ok`，SHOW TABLES 列出 prod_line/prod_process/prod_daily_metrics/prod_defect_record/prod_spc_sample。

- [ ] **Step 5: Commit**

```bash
git add pipeline/config.py pipeline/db.py pipeline/requirements.txt
git commit -m "feat(pipeline): add manufacturing data generator skeleton with schema DDL"
```

---

### Task 3: 生成器核心逻辑 generator.py

**Files:**
- Create: `pipeline/generator.py`

- [ ] **Step 1: 写生成器（确定性 seed；含季节因子、相关性矩阵、SPC 样本）**

```python
"""核心生成逻辑：按 docs/data-model.md 口径生成制造质量数据并写库"""
import datetime
import random

from config import (LINES, SEASON_FACTOR, DEFECT_TYPES, DEFECT_PROCESS_MATRIX,
                    PROCESS_DEFECT_WEIGHT, COUNTING_PARAM_PREFIXES)
from db import get_connection

YIELD_BASE = 0.965          # 良率基线（口径：>=95%）
AVAILABILITY_RANGE = (0.90, 0.98)
PERFORMANCE_RANGE = (0.88, 0.96)
IDEAL_CYCLE_MIN = 0.75      # 理论节拍（分钟/件）——产能 = available_time / ideal_cycle
SPC_SUBGROUP_SIZE = 5
SPC_SIGMA = 0.020           # 参数相对波动 sigma（占 target 比例）

# 工序 SPC 规格（仅计量型工序；计数型参数工序不适用 X̄-R 图，不入表）
# 键 = (产线, 工序) 组合，避免跨线同名工序覆盖
SPC_SPEC = {}
for _line in LINES:
    for _pname, _param, _target, _usl, _lsl in _line["processes"]:
        if _param.startswith(COUNTING_PARAM_PREFIXES):
            continue  # 计数型（缺陷数/件数），不适用计量控制图
        SPC_SPEC[(_line["name"], _pname)] = {"param": _param, "target": _target,
                                             "usl": _usl, "lsl": _lsl}


def _month_factor(date):
    return SEASON_FACTOR[date.month - 1]


def clamp_yield(y):
    """良率钳位到口径区间 [94.5%, 99.5%]（目标 95%，允许日度波动）"""
    return min(0.995, max(0.945, y))


def generate_line_meta(conn, rng):
    """产线与工序（含 SPC 参数规格限），返回 line_id/process_id 映射"""
    line_ids, process_ids = {}, {}
    with conn.cursor() as cursor:
        for line in LINES:
            cursor.execute("INSERT INTO prod_line (name, plant) VALUES (%s, %s)",
                           (line["name"], line["plant"]))
            line_id = cursor.lastrowid
            line_ids[line["name"]] = line_id
            for seq, (pname, pparam, target, usl, lsl) in enumerate(line["processes"], start=1):
                cursor.execute(
                    "INSERT INTO prod_process (line_id, name, seq, param_name, param_target, param_usl, param_lsl) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s)",
                    (line_id, pname, seq, pparam, target, usl, lsl))
                process_ids[(line["name"], pname)] = cursor.lastrowid
    conn.commit()
    return line_ids, process_ids


def generate_daily_metrics(conn, rng, start, end, line_ids):
    """每日指标：产量由产能推导（available/ideal_cycle × 可用率 × 性能），
    保证后端重算的 OEE 三分量落在口径区间；季节因子作用于计划量（需求侧）"""
    days = (end - start).days + 1
    with conn.cursor() as cursor:
        for i in range(days):
            day = start + datetime.timedelta(days=i)
            factor = _month_factor(day)
            for line_name, line_id in line_ids.items():
                available_time = 24 * 60 if day.weekday() < 6 else 16 * 60  # 周日短班（weekday()==6 为周日）
                capacity = available_time / IDEAL_CYCLE_MIN                  # 理论产能（件）
                availability = rng.uniform(*AVAILABILITY_RANGE)
                performance = rng.uniform(*PERFORMANCE_RANGE)
                output_qty = int(capacity * availability * performance)
                plan_qty = int(output_qty * factor * rng.uniform(1.0, 1.03))  # 计划=需求侧（含季节），低产月可低于实际
                yield_rate = clamp_yield(YIELD_BASE + rng.gauss(0, 0.008))
                good_qty = int(output_qty * yield_rate)
                defect_qty = output_qty - good_qty
                run_time = int(available_time * availability)
                cursor.execute(
                    "INSERT INTO prod_daily_metrics (stat_date, line_id, plan_qty, output_qty, good_qty, "
                    "defect_qty, available_time_min, run_time_min, ideal_cycle_min, actual_cycle_min) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                    (day, line_id, plan_qty, output_qty, good_qty, defect_qty,
                     available_time, run_time, IDEAL_CYCLE_MIN, IDEAL_CYCLE_MIN / performance))
    conn.commit()


def _allocate(total, weights):
    """最大余数法：把 total 按权重分配为整数，保证 sum(结果) == total（消除 int 截断偏差）"""
    if total <= 0:
        return [0] * len(weights)
    raw = [total * w for w in weights]
    result = [int(x) for x in raw]
    remainder = total - sum(result)
    if remainder > 0:
        idxs = sorted(range(len(raw)), key=lambda i: raw[i] - result[i], reverse=True)
        for i in idxs[:remainder]:
            result[i] += 1
    return result


def generate_defects(conn, rng, start, end, line_ids, process_ids):
    """缺陷记录：与日指标 defect_qty 严格同口径（两级最大余数法分摊，qty 之和 == defect_qty），
    按 缺陷-工序权重矩阵 注入相关性"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT stat_date, line_id, defect_qty FROM prod_daily_metrics "
            "WHERE stat_date BETWEEN %s AND %s", (start, end))
        defect_by_day_line = {(row[0], row[1]): row[2] for row in cur.fetchall()}
    with conn.cursor() as cursor:
        for (day, line_id), defect_qty in defect_by_day_line.items():
            line_name = next(n for n, i in line_ids.items() if i == line_id)
            processes = [ln["processes"] for ln in LINES if ln["name"] == line_name][0]
            proc_weights = PROCESS_DEFECT_WEIGHT[line_name]
            proc_quotas = _allocate(defect_qty, proc_weights)          # 第 1 级：分到工序
            for (pname, _p, _t, _u, _l), proc_qty in zip([p[0] for p in processes], proc_quotas):
                if proc_qty <= 0:
                    continue
                process_id = process_ids[(line_name, pname)]
                weights = DEFECT_PROCESS_MATRIX[(line_name, pname)]
                type_quotas = _allocate(proc_qty, weights)             # 第 2 级：分到缺陷类型
                for (code, name, severity), qty in zip(DEFECT_TYPES, type_quotas):
                    if qty <= 0:
                        continue
                    cursor.execute(
                        "INSERT INTO prod_defect_record (stat_date, line_id, process_id, defect_code, "
                        "defect_name, qty, severity) VALUES (%s, %s, %s, %s, %s, %s, %s)",
                        (day, line_id, process_id, code, name, qty, severity))
    conn.commit()


def generate_spc(conn, rng, start, end, process_ids):
    """SPC 样本：每计量工序每天 1 个子组（n=5），围绕 param_target 波动；
    ~2% 子组整体偏移 +8σ 制造可检出出界点（X̄ 控制限 ≈ ±1.34σ，8σ >> 1.34σ 可靠越限）"""
    days = (end - start).days + 1
    with conn.cursor() as cursor:
        for i in range(days):
            day = start + datetime.timedelta(days=i)
            for (line_name, pname), process_id in process_ids.items():
                spec = SPC_SPEC.get((line_name, pname))
                if spec is None:
                    continue  # 计数型质检工序不产 SPC 样本
                target = spec["target"]
                sigma = max(0.01, target * SPC_SIGMA)
                shift = 8.0 * sigma if rng.random() < 0.02 else 0.0
                for sample_no in range(1, SPC_SUBGROUP_SIZE + 1):
                    value = round(target + rng.gauss(0, sigma) + shift, 4)
                    cursor.execute(
                        "INSERT INTO prod_spc_sample (stat_date, process_id, sample_no, value) "
                        "VALUES (%s, %s, %s, %s)",
                        (day, process_id, sample_no, value))
    conn.commit()
```

- [ ] **Step 2: 写 main.py（CLI：清表→生成→自检打印）**

```python
"""CLI 入口：python3 main.py --days 90 --seed 42 --reset"""
import argparse
import datetime
import random

import generator
from db import get_connection, init_db

RESET_TABLES = ["prod_spc_sample", "prod_defect_record", "prod_daily_metrics",
                "prod_process", "prod_line"]


def main():
    parser = argparse.ArgumentParser(description="制造质量模拟数据生成器")
    parser.add_argument("--days", type=int, default=90)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--reset", action="store_true", help="清空 prod_* 表后重新生成")
    parser.add_argument("--end", type=str, default=None, help="结束日期 YYYY-MM-DD，默认今天")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    conn = get_connection()
    init_db(conn)
    if args.reset:
        with conn.cursor() as cursor:
            for t in RESET_TABLES:
                cursor.execute("TRUNCATE TABLE %s" % t)
        conn.commit()
    else:
        with conn.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM prod_daily_metrics")
            if cursor.fetchone()[0] > 0:
                print("警告：库中已有数据，继续将重复插入（建议 --reset 重新生成）")
    end = datetime.date.today() if not args.end else datetime.date.fromisoformat(args.end)
    start = end - datetime.timedelta(days=args.days - 1)

    line_ids, process_ids = generator.generate_line_meta(conn, rng)
    generator.generate_daily_metrics(conn, rng, start, end, line_ids)
    generator.generate_defects(conn, rng, start, end, line_ids, process_ids)
    generator.generate_spc(conn, rng, start, end, process_ids)

    with conn.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM prod_daily_metrics")
        metrics = cursor.fetchone()[0]
        cursor.execute("SELECT COUNT(*) FROM prod_defect_record")
        defects = cursor.fetchone()[0]
        cursor.execute("SELECT COUNT(*) FROM prod_spc_sample")
        spc = cursor.fetchone()[0]
    conn.close()
    print(f"generated: metrics={metrics} defects={defects} spc_samples={spc} "
          f"(days={args.days} seed={args.seed})")


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: 运行生成器（3 条线 × 90 天）**

Run: `python3 main.py --days 90 --seed 42 --reset`
Expected: `generated: metrics=270 defects=~3k-5k spc_samples=4050`
口径说明：metrics = 3 线 × 90 天 = 270；defects 为 `COUNT(*)` **行数**（qty 之和 = 各日 `defect_qty` 总和，≈**1.4w-1.8w**——日产量 ≈1660 × 缺陷率 3.5% × 270 线日，因分摊后小权重项可能舍入为 0 而行数略少于 5400）；spc = **9 个计量工序** × 90 天 × 5 样本 = 4050（质检类计数工序不产 SPC 样本）；日产量由产能推导（工作日 ≈ 0.75 节拍 → 理论产能 1920 件 × 可用率×性能 ≈ 1650 件）。

- [ ] **Step 4: 验证库内数据口径**

Run:
```bash
docker exec yubi-mysql mysql -uroot -p123456 -e "USE yubi; SELECT stat_date, line_id, good_qty, output_qty FROM prod_daily_metrics LIMIT 5;"
docker exec yubi-mysql mysql -uroot -p123456 -e "USE yubi; SELECT COUNT(*) FROM prod_spc_sample;"
docker exec yubi-mysql mysql -uroot -p123456 -e "USE yubi; SELECT SUM(qty) FROM prod_defect_record;"
```
Expected: 270 条日指标；spc 样本 4050 条（9 工序 × 90 天 × 5）；`SUM(qty)`（缺陷）≈ `SUM(defect_qty)`（日指标，两口径一致）；良率 = good/output ∈ [94.5%, 99.5%]。
若 `generated:` 打印数与上不符，以数据库实际 COUNT 为准修正 main.py 输出逻辑。

- [ ] **Step 5: Commit**

```bash
git add pipeline/generator.py pipeline/main.py
git commit -m "feat(pipeline): implement quality data generator with correlation matrix and SPC samples"
```

---

### Task 4: 生成器口径校验 pytest

**Files:**
- Create: `pipeline/test_generator.py`

- [ ] **Step 1: 写 pytest（纯函数口径校验，不依赖 DB）**

```python
"""口径校验：良率/OEE/SPC 公式/相关性/季节因子（纯计算，不连库）"""
import random
import statistics

import generator
from config import (SEASON_FACTOR, DEFECT_PROCESS_MATRIX, PROCESS_DEFECT_WEIGHT,
                    LINES, COUNTING_PARAM_PREFIXES)


def test_clamp_yield_bounds():
    assert generator.clamp_yield(0.90) == 0.945
    assert generator.clamp_yield(0.99) == 0.99
    assert generator.clamp_yield(0.999) == 0.995


def test_yield_rate_within_baseline():
    rng = random.Random(1)
    rates = [generator.clamp_yield(generator.YIELD_BASE + rng.gauss(0, 0.008))
             for _ in range(200)]
    assert min(rates) >= 0.945
    assert max(rates) <= 0.995
    # 用中位数而非均值，避免钳位偏置造成 flaky
    assert statistics.median(rates) >= 0.96


def test_month_factor_length_and_range():
    assert len(SEASON_FACTOR) == 12
    assert min(SEASON_FACTOR) >= 0.85
    assert max(SEASON_FACTOR) <= 1.10


def test_defect_matrix_rows_sum_to_one():
    for (line, process), weights in DEFECT_PROCESS_MATRIX.items():
        assert abs(sum(weights) - 1.0) < 1e-9, (line, process)


def test_defect_matrix_covers_all_processes():
    """矩阵必须覆盖每条线的全部工序（组合键，防跨线同名覆盖）"""
    expected = {(ln["name"], proc[0]) for ln in LINES for proc in ln["processes"]}
    assert expected <= set(DEFECT_PROCESS_MATRIX.keys()), \
        expected - set(DEFECT_PROCESS_MATRIX.keys())


def test_process_defect_weight_rows_sum_to_one():
    for line in LINES:
        weights = PROCESS_DEFECT_WEIGHT[line["name"]]
        assert len(weights) == len(line["processes"]), line["name"]
        assert abs(sum(weights) - 1.0) < 1e-9, line["name"]


def test_spc_spec_covers_only_variable_processes():
    """SPC_SPEC 覆盖全部计量工序（与 config.COUNTING_PARAM_PREFIXES 同源判定）"""
    all_names = {(ln["name"], proc[0]) for ln in LINES for proc in ln["processes"]}
    variable = {(ln["name"], proc[0]) for ln in LINES for proc in ln["processes"]
                if not proc[1].startswith(COUNTING_PARAM_PREFIXES)}
    assert set(generator.SPC_SPEC.keys()) == variable
    for spec in generator.SPC_SPEC.values():
        assert spec["lsl"] < spec["target"] < spec["usl"]


def test_allocate_invariant():
    """最大余数法分配：任意权重下 sum(结果) == total（缺陷两口径一致性的根基）"""
    assert generator._allocate(58, [0.35, 0.30, 0.20, 0.15]) == [20, 17, 12, 9]
    assert generator._allocate(0, [0.5, 0.5]) == [0, 0]
    assert sum(generator._allocate(7, [0.33, 0.33, 0.34])) == 7
    assert sum(generator._allocate(100, [0.25] * 4)) == 100


def test_spc_subgroup_size_constant():
    assert generator.SPC_SUBGROUP_SIZE == 5
```

- [ ] **Step 2: 运行并确认全绿**

Run: `python3 -m pytest test_generator.py -v`
Expected: 9 个 test 全部 PASS（含 clamp_yield 边界、矩阵组合键覆盖、工序缺陷权重行和、SPC 仅计量工序、最大余数法不变量）。

- [ ] **Step 3: Commit**

```bash
git add pipeline/test_generator.py
git commit -m "test(pipeline): add generator spec checks (yield/oee/spc/correlation)"
```

---

### Task 5: 后端 entity + mapper（5 表）

**Files:**
- Create: `backend/src/main/java/com/yupi/springbootinit/model/entity/ProdLine.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/model/entity/ProdProcess.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/model/entity/ProdDailyMetrics.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/model/entity/ProdDefectRecord.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/model/entity/ProdSpcSample.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/mapper/ProdLineMapper.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/mapper/ProdProcessMapper.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/mapper/ProdDailyMetricsMapper.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/mapper/ProdDefectRecordMapper.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/mapper/ProdSpcSampleMapper.java`

- [ ] **Step 1: 写 ProdDailyMetrics entity（其余 4 个按同模式，字段对齐 DDL）**

> ⚠️ 映射铁律：`application.yml` 配置了 `map-underscore-to-camel-case: false`，MyBatis-Plus 不会把 camelCase 字段转成下划线列名（反编译 mybatis-plus-core 3.5.2 证实：`TableInfo.underCamel=false` 时列名推导跳过 `camelToUnderline`）。DB 列是 snake_case，**每个字段必须显式 `@TableField("snake_case")`**，否则运行时 `Unknown column 'statDate'`。禁止改全局配置（会破坏 chart/user 基线）。

```java
package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

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
```

- [ ] **Step 2: 写其余 4 个 entity（ProdLine: id/name/plant；ProdProcess: id/lineId/name/seq/paramName/paramTarget/paramUsl/paramLsl；ProdDefectRecord: id/statDate/lineId/processId/defectCode/defectName/qty/severity；ProdSpcSample: id/statDate/processId/sampleNo/value）**

ProdSpcSample 示例（其余 3 个字段同上模式，数值字段用 BigDecimal 对齐 DDL double，**同样逐字段 @TableField**）：

```java
package com.yupi.springbootinit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * SPC 样本
 */
@TableName(value = "prod_spc_sample")
@Data
public class ProdSpcSample implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("stat_date")
    private Date statDate;

    @TableField("process_id")
    private Long processId;

    @TableField("sample_no")
    private Integer sampleNo;

    @TableField("value")
    private BigDecimal value;

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
```

字段映射速查（其余 3 个实体照此写；**全部实体都含公共字段** `@TableField("create_time") Date createTime` / `@TableField("update_time") Date updateTime` / `@TableLogic @TableField("is_delete") Integer isDelete`，与 ProdDailyMetrics 一致，勿漏）：
- `ProdLine`：`id` / `name` / `plant`
- `ProdProcess`：`id` / `line_id` / `name` / `seq` / `param_name` / `param_target` / `param_usl` / `param_lsl`
- `ProdDefectRecord`：`id` / `stat_date` / `line_id` / `process_id` / `defect_code` / `defect_name` / `qty` / `severity`
- Service 中 QueryWrapper 的裸字符串（`"line_id"`/`"stat_date"`）是真实列名，无需改动。

- [ ] **Step 3: 写 5 个 Mapper**

```java
package com.yupi.springbootinit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yupi.springbootinit.model.entity.ProdDailyMetrics;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProdDailyMetricsMapper extends BaseMapper<ProdDailyMetrics> {
}
```

（ProdLineMapper / ProdProcessMapper / ProdDefectRecordMapper / ProdSpcSampleMapper 同模式，泛型换对应 entity。）

- [ ] **Step 4: 编译验证**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && export PATH="$JAVA_HOME/bin:$PATH" && mvn -q compile`
Expected: 无错误。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/yupi/springbootinit/model/entity/Prod*.java backend/src/main/java/com/yupi/springbootinit/mapper/Prod*Mapper.java
git commit -m "feat(backend): add prod entity and mapper for quality insight"
```

---

### Task 6: SpcCalculator 纯计算 + JUnit 单元测试

**Files:**
- Create: `backend/src/main/java/com/yupi/springbootinit/utils/SpcCalculator.java`
- Test: `backend/src/test/java/com/yupi/springbootinit/utils/SpcCalculatorTest.java`

- [ ] **Step 1: 写失败测试（X̄-R 公式与教科书示例值）**

```java
package com.yupi.springbootinit.utils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SpcCalculatorTest {

    @Test
    public void testControlLimitsWithTrendData() {
        // n=5：A2=0.577, D3=0, D4=2.114
        // 5 个子组，值 = 10 + 组号*2，使每子组均值递增（10.8→18.8）、极差恒为 2
        // 控制限 = 14.8 ± 0.577×2 = [13.646, 15.954] → 10.8/12.8 低于 LCL、16.8/18.8 高于 UCL
        // 共 4 个出界点（教科书式趋势检出案例）
        List<List<Double>> subgroups = Arrays.asList(
                Arrays.asList(10.0, 12.0, 10.0, 12.0, 10.0), // mean 10.8, range 2
                Arrays.asList(12.0, 14.0, 12.0, 14.0, 12.0), // mean 12.8, range 2
                Arrays.asList(14.0, 16.0, 14.0, 16.0, 14.0), // mean 14.8, range 2
                Arrays.asList(16.0, 18.0, 16.0, 18.0, 16.0), // mean 16.8, range 2
                Arrays.asList(18.0, 20.0, 18.0, 20.0, 18.0)); // mean 18.8, range 2
        SpcCalculator.Result r = SpcCalculator.compute(subgroups, 5);
        assertEquals(14.8, r.getXBarCenter(), 1e-9);   // X̄̄ = (10.8+12.8+14.8+16.8+18.8)/5
        assertEquals(2.0, r.getRBar(), 1e-9);          // R̄ = 2
        assertEquals(14.8 + 0.577 * 2.0, r.getXBarUcl(), 1e-9);
        assertEquals(14.8 - 0.577 * 2.0, r.getXBarLcl(), 1e-9);
        assertEquals(2.114 * 2.0, r.getRUcl(), 1e-9);
        assertEquals(0.0, r.getRLcl(), 1e-9);
        // 趋势数据必然检出 4 个 X̄ 出界点（index 0/1 低于 LCL，3/4 高于 UCL）
        assertEquals(4, r.getOutOfControl().size());
        assertEquals(0, r.getOutOfControl().get(0).getIndex());
        assertEquals("xbar", r.getOutOfControl().get(0).getType());
    }

    @Test
    public void testOutOfControlDetection() {
        // 极差恒为 0 → R̄=0 → X̄ 控制限 = X̄̄ = 23.33（无带宽）→ 三个子组全部出界
        List<List<Double>> subgroups = Arrays.asList(
                Arrays.asList(10.0, 10.0, 10.0, 10.0, 10.0), // mean 10 -> 出界（< 23.33）
                Arrays.asList(10.0, 10.0, 10.0, 10.0, 10.0), // mean 10 -> 出界
                Arrays.asList(50.0, 50.0, 50.0, 50.0, 50.0)); // mean 50 -> 出界（> 23.33）
        SpcCalculator.Result r = SpcCalculator.compute(subgroups, 5);
        assertEquals(3, r.getOutOfControl().size());
        assertEquals(0, r.getOutOfControl().get(0).getIndex());
        assertEquals("xbar", r.getOutOfControl().get(0).getType());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -Dtest=SpcCalculatorTest test
```
Expected: FAIL（SpcCalculator 不存在 → 编译错误）。

- [ ] **Step 3: 实现 SpcCalculator**

```java
package com.yupi.springbootinit.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * SPC X̄-R 控制图计算（口径见 docs/data-model.md）
 * 子组大小 n 支持 2-5，常数表：A2/D3/D4
 */
public class SpcCalculator {

    private static final double[] A2 = {0, 0, 1.880, 1.023, 0.729, 0.577};
    private static final double[] D3 = {0, 0, 0, 0, 0, 0};
    private static final double[] D4 = {0, 0, 3.267, 2.574, 2.282, 2.114};

    public static Result compute(List<List<Double>> subgroups, int subgroupSize) {
        if (subgroups == null || subgroups.isEmpty()) {
            throw new IllegalArgumentException("subgroups must not be empty");
        }
        if (subgroupSize < 2 || subgroupSize > 5) {
            throw new IllegalArgumentException("subgroupSize must be 2..5");
        }
        List<Double> xBars = new ArrayList<>();
        List<Double> ranges = new ArrayList<>();
        for (List<Double> group : subgroups) {
            double sum = 0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Double v : group) {
                sum += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            xBars.add(sum / group.size());
            ranges.add(max - min);
        }
        double xBarCenter = xBars.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double rBar = ranges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double xBarUcl = xBarCenter + A2[subgroupSize] * rBar;
        double xBarLcl = xBarCenter - A2[subgroupSize] * rBar;
        double rUcl = D4[subgroupSize] * rBar;
        double rLcl = D3[subgroupSize] * rBar;

        List<OutOfControl> out = new ArrayList<>();
        for (int i = 0; i < xBars.size(); i++) {
            if (xBars.get(i) > xBarUcl || xBars.get(i) < xBarLcl) {
                out.add(new OutOfControl(i, "xbar"));
            }
            if (ranges.get(i) > rUcl || ranges.get(i) < rLcl) {
                out.add(new OutOfControl(i, "range"));
            }
        }
        return new Result(xBarCenter, rBar, xBarUcl, xBarLcl, rUcl, rLcl, out);
    }

    public static class Result {
        private final double xBarCenter, rBar, xBarUcl, xBarLcl, rUcl, rLcl;
        private final List<OutOfControl> outOfControl;

        public Result(double xBarCenter, double rBar, double xBarUcl, double xBarLcl,
                      double rUcl, double rLcl, List<OutOfControl> outOfControl) {
            this.xBarCenter = xBarCenter;
            this.rBar = rBar;
            this.xBarUcl = xBarUcl;
            this.xBarLcl = xBarLcl;
            this.rUcl = rUcl;
            this.rLcl = rLcl;
            this.outOfControl = outOfControl;
        }

        public double getXBarCenter() { return xBarCenter; }
        public double getRBar() { return rBar; }
        public double getXBarUcl() { return xBarUcl; }
        public double getXBarLcl() { return xBarLcl; }
        public double getRUcl() { return rUcl; }
        public double getRLcl() { return rLcl; }
        public List<OutOfControl> getOutOfControl() { return outOfControl; }
    }

    public static class OutOfControl {
        private final int index;
        private final String type;

        public OutOfControl(int index, String type) {
            this.index = index;
            this.type = type;
        }

        public int getIndex() { return index; }
        public String getType() { return type; }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -Dtest=SpcCalculatorTest test
```
Expected: PASS（2 tests）。同时跑全量：`mvn -q test` 不得破坏既有测试（若既有测试因无 Redis/MQ 环境失败，只关注 SpcCalculatorTest 通过即可，记录说明）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/yupi/springbootinit/utils/SpcCalculator.java backend/src/test/java/com/yupi/springbootinit/utils/SpcCalculatorTest.java
git commit -m "feat(backend): add SPC xbar-r calculator with unit tests"
```

---

### Task 7: QualityService 聚合查询

**Files:**
- Create: `backend/src/main/java/com/yupi/springbootinit/service/QualityService.java`
- Create: `backend/src/main/java/com/yupi/springbootinit/service/impl/QualityServiceImpl.java`

- [ ] **Step 1: 写接口**

```java
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
```

- [ ] **Step 2: 先建 QualityVO（T8 交付结构，此处先行定义供 service 编译）**

```java
package com.yupi.springbootinit.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 质量洞察返回结构
 */
public class QualityVO {

    @Data
    public static class YieldPoint {
        private String date;
        private Double yield;
        private Double target;
    }

    @Data
    public static class OeePoint {
        private String date;
        private Double oee;
        private Double availability;
        private Double performance;
        private Double quality;
    }

    @Data
    public static class DefectItem {
        private String defectCode;
        private String defectName;
        private Integer qty;
        private Double pct;
    }

    @Data
    public static class SpcResult {
        private List<SpcPoint> xBar;
        private List<SpcPoint> rPoints;
        private Double xBarUcl;
        private Double xBarLcl;
        private Double xBarCenter;
        private Double rUcl;
        private Double rLcl;
        private Double rCenter;
        private List<OutOfControlPoint> outOfControl;
    }

    @Data
    public static class SpcPoint {
        private String date;
        private Double value;
    }

    @Data
    public static class OutOfControlPoint {
        private String date;
        private Integer index;
        private String type;
    }

    @Data
    public static class ProcessDefectMatrix {
        private List<String> processes;
        private List<String> defects;
        private List<List<Integer>> matrix;
    }

    @Data
    public static class Summary {
        private Double yield;
        private Double oee;
        private Integer defectTotal;
        private Double yieldDelta;
        private Double oeeDelta;
    }
}
```

- [ ] **Step 3: 写实现（QueryWrapper 聚合 + SpcCalculator 组装）**

```java
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
        // 防呆：days 钳制 [1, 365]（避免负数/超范围查询）；SimpleDateFormat 非线程安全，方法内 new
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
        Map<Date, List<BigDecimal>> grouped = new LinkedHashMap<>();
        for (ProdSpcSample s : samples) {
            grouped.computeIfAbsent(s.getStatDate(), k -> new ArrayList<>()).add(s.getValue());
        }
        List<List<Double>> subgroups = new ArrayList<>();
        List<Date> dates = new ArrayList<>();   // 只收集被保留子组的日期，与 subgroups 索引严格对齐
        for (Map.Entry<Date, List<BigDecimal>> e : grouped.entrySet()) {
            List<Double> group = e.getValue().stream().map(BigDecimal::doubleValue)
                    .collect(Collectors.toList());
            if (group.size() == 5) {   // 子组大小 n=5，与生成器 SPC_SUBGROUP_SIZE 一致
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
        Map<String, Integer> processNameById = new LinkedHashMap<>();
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
        // defects 顺序固定：按 defectCode 字典序（避免 distinct 依赖查询返回顺序不稳定）
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
```

- [ ] **Step 4: 编译验证**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q compile
```
Expected: 无错误。

- [ ] **Step 5: 写 service 层单测（Mockito 注入，纯 JUnit 不连库；覆盖 OEE 三分量公式与缺陷累计占比）**

Test: `backend/src/test/java/com/yupi/springbootinit/service/impl/QualityServiceImplTest.java`

```java
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
        assertEquals("surface_scratch", items.get(0).getDefectCode());  // 150 > 100，排序确定
        assertEquals(60.0, items.get(0).getPct(), 1e-6);                // 150/250 累计
        assertEquals(100.0, items.get(1).getPct(), 1e-6);               // (150+100)/250 累计
    }
}
```

- [ ] **Step 6: 运行 service 单测**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -Dtest=QualityServiceImplTest test
```
Expected: PASS（2 tests，断言已确定化：surface_scratch 150 > weld_defect 100）。

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/yupi/springbootinit/service/QualityService.java backend/src/main/java/com/yupi/springbootinit/service/impl/QualityServiceImpl.java backend/src/main/java/com/yupi/springbootinit/model/vo/QualityVO.java backend/src/test/java/com/yupi/springbootinit/service/impl/QualityServiceImplTest.java
git commit -m "feat(backend): add quality insight service with oee/spc aggregation and service tests"
```

---

### Task 8: QualityController REST 接口

**Files:**
- Create: `backend/src/main/java/com/yupi/springbootinit/controller/QualityController.java`

- [ ] **Step 1: 写 Controller（6 个端点，路径 /api/quality/*，无需登录注解保持与原项目一致）**

```java
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
```

- [ ] **Step 2: 编译 + 重启后端 + 接口验证**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q compile
pkill -f "spring-boot-maven-plugin"; sleep 3
nohup mvn -q org.springframework.boot:spring-boot-maven-plugin:run > /tmp/yubi-backend.log 2>&1 &
# 轮询就绪：curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/doc.html  → 200
```

- [ ] **Step 3: 逐接口 curl 验证（数据已由 T3 生成器灌入）**

```bash
curl -s "http://localhost:8080/api/quality/summary?days=7&lineId=1"
curl -s "http://localhost:8080/api/quality/yield/trend?days=7&lineId=1"
curl -s "http://localhost:8080/api/quality/oee/trend?days=7&lineId=1"
curl -s "http://localhost:8080/api/quality/defect/distribution?days=7&lineId=1"
curl -s "http://localhost:8080/api/quality/spc?processId=1&days=7"
curl -s "http://localhost:8080/api/quality/defect/process?days=7&lineId=1"
```
Expected: 全部 `{"code":0,...}`；yield 在 94-100 区间、oee 在 **70-95 区间**（产能推导口径：可用率 90-98 × 性能 88-96 × 质量 94.5-99.5）、spc 有 xBarUcl/Lcl 且 center 接近工序 target、outOfControl 为少量出界点（~2% 子组整体偏移 +8σ 注入，seed 42 下每工序 0-3 个，**X̄ 图检出为主**——整子组偏移不改变组内极差，R 图基本无检出）。

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/yupi/springbootinit/controller/QualityController.java
git commit -m "feat(backend): add quality insight REST endpoints"
```

---

### Task 9: 前端路由与页面骨架

**Files:**
- Modify: `frontend/config/routes.ts`
- Create: `frontend/src/services/quality.ts`
- Create: `frontend/src/pages/QualityInsight/index.tsx`

- [ ] **Step 1: routes.ts 加路由（放在 /my_chart 之后）**

```ts
  { path: '/quality', name: '制造质量洞察', icon: 'lineChart', component: './QualityInsight' },
```

- [ ] **Step 2: 写 services/quality.ts（@umijs/max request + BaseResponse 解包）**

> ⚠️ `@umijs/max` 的 request **不做 data 解包**，resolve 的是完整响应体（yubi 的 `BaseResponse` 无 `success` 字段，umi 的 errorThrower 不触发）。必须显式声明 `BaseResponse<T>` 并取 `.data`，否则页面拿到整个响应体（`summary?.yield` 恒为 undefined、图表 `data.map is not a function`）。

```ts
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
```

- [ ] **Step 3: 页面骨架（统计卡 + 图表挂载占位，图表组件 T10 实现）**

```tsx
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
          {/* processId=2 依赖生成器 --reset 后 AUTO_INCREMENT 从 1 开始（冲压线·成型）；工序选择器留 W2 */}
          <Card title="SPC 控制图（冲压线·成型工序）"><SpcChart processId={2} /></Card>
        </Col>
      </Row>
    </PageContainer>
  );
};

export default QualityInsight;
```

- [ ] **Step 4: 临时空 charts.tsx 使编译通过，dev 自测页面骨架**

```tsx
// 占位：T10 填充真实图表
export const YieldChart: React.FC<{ lineId: number }> = () => null;
export const OeeChart: React.FC<{ lineId: number }> = () => null;
export const SpcChart: React.FC<{ processId: number }> = () => null;
export const DefectParetoChart: React.FC<{ lineId: number }> = () => null;
export const DefectProcessHeatmap: React.FC<{ lineId: number }> = () => null;
```

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/quality`（dev server 热更新，Expected 200）；浏览器登录后访问 `/quality` 看到 4 统计卡 + 5 个空图表卡。

- [ ] **Step 5: Commit**

```bash
git add frontend/config/routes.ts frontend/src/services/quality.ts frontend/src/pages/QualityInsight/
git commit -m "feat(frontend): add quality insight page skeleton and api service"
```

---

### Task 10: 前端图表组件（ECharts）

**Files:**
- Modify: `frontend/src/pages/QualityInsight/charts.tsx`

- [ ] **Step 1: 实现 charts.tsx（5 个 ECharts 组件，echarts-for-react）**

```tsx
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
  // 出界点标注（X̄ 图；整子组偏移不改组内极差，R 图以随机误报为主）
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
  // 缺陷中文名映射（后端只回 defectCode；展示用名取自 DefectItem 不可靠，此处按已知码表）
  const defectNames: Record<string, string> = {
    surface_scratch: '划伤', assembly_defect: '装配不良', weld_defect: '焊接缺陷',
    dimension_error: '尺寸偏差', leak_test: '泄漏',
  };
  const values = data.matrix
    .flatMap((row, x) => row.map((v, y) => [y, x, v] as [number, number, number]));
  // ECharts heatmap 数据项为 [xIndex, yIndex, value]：x 轴=缺陷、y 轴=工序，故缺陷索引在前
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
```

- [ ] **Step 2: dev 自测（浏览器验证 5 张图数据渲染）**

Run: 浏览器登录 `http://localhost:8000` → 访问 `/quality`（或 `/quality?lineId=1`）。Expected：统计卡有值；良率/ OEE 折线、SPC 控制限虚线、帕累托柱+累计线、热力图均渲染且数值合理（良率 94-100、OEE 70-95、SPC 中心线≈工序 target）。

- [ ] **Step 3: 前端构建校验**

Run: `cd frontend && ./node_modules/.bin/max build`
Expected: 构建成功（注意可能耗时 1-3 分钟）。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/QualityInsight/charts.tsx
git commit -m "feat(frontend): add quality insight echarts components"
```

---

### Task 11: 全量验证与 README 更新

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 回归验证（确认未破坏 yubi 原生链路）**

```bash
# 登录链路（若 testuser 不存在先注册——create_table.sql 无用户种子，新环境首次执行）
curl -s -X POST http://localhost:8080/api/user/register -H "Content-Type: application/json" \
  -d '{"userAccount":"testuser","userPassword":"12345678","checkPassword":"12345678"}' || true
curl -s -X POST http://localhost:8080/api/user/login -H "Content-Type: application/json" \
  -d '{"userAccount":"testuser","userPassword":"12345678"}'   # 期望 code:0
# 前端回归
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/                    # 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/quality             # 200
# 质量接口回归
curl -s "http://localhost:8080/api/quality/summary?days=7&lineId=1"                # code:0
```

- [ ] **Step 2: README 更新（状态勾选 + 模块说明，无个人信息）**

在 README.md 的「状态与路线图」勾选：
```markdown
- [x] 源项目落地（backend/frontend）
- [x] 数据生成设计文档（docs/data-model.md）
- [x] 制造质量洞察模块（W1）
- [ ] AIGC 分析 + 销售模板（W2）
- [ ] 测试套件 + 部署（W3）
```
并在「定位」下补一行模块说明：制造质量洞察接口 `/api/quality/*`（良率/OEE/SPC/缺陷分析）与前端 `/quality` 页面。

- [ ] **Step 3: 全量测试与提交**

```bash
# 后端单测（SPC 计算 + service 聚合）
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -Dtest=SpcCalculatorTest,QualityServiceImplTest test    # 4 PASS
# pipeline 口径测试
cd pipeline && python3 -m pytest test_generator.py -v          # 9 PASS
```
Commit:
```bash
git add README.md
git commit -m "docs: update roadmap and module description for quality insight"
git log --oneline -10    # 自查：无敏感词、无个人信息的 commit message
```

---

## 修订记录（架构审核轮次）

| 轮次 | 审核得分 | 修复项 |
|------|---------|--------|
| 初审 | 72/100 | — |
| 修订 1 | — | P0-1 实体 @TableField 显式映射（T5）；P0-2 SpcCalculatorTest 断言修正（T6）；P1-1 services 解包（T9）；P1-2 SPC 整子组 8σ 偏移（T3）；P1-3 缺陷量期望值口径统一（T3）；P1-4 pytest 去 flaky + clamp_yield 抽出（T3/T4）；P2 权重矩阵死键（T2/T4）、SimpleDateFormat 线程安全 + days 上限（T7）、service 层单测（T7） |
| 复审 | 64/100 | — |
| 修订 2 | — | P0-1 产能推导 output 消除 OEE≈250%（T3，plan_qty 由产能×季节推导）；P0-2 testOutOfControlDetection 断言改为 3 出界（T6）；P0-3 质检计数工序不产 SPC 样本（T3/T4，spc=4050）；P1-1 缺陷矩阵/SPC_SPEC 改 (线,工序) 组合键（T2/T3/T4）；P1-2 单测排序确定化 r3.qty=150（T7）；P1-3 缺陷记录与 defect_qty 同口径（T3/T4，PROCESS_DEFECT_WEIGHT）；P1-4 SpcChart 渲染 R 图 + OOC markPoint（T10）；P2 spc() 日期对齐（T7）、days 下限（T7）、X̄ 检出措辞（T8）、processId 注释（T9）、actual_cycle_min 文档说明 + 良率目标/波动口径 + 质检 SPC 边界（T1） |
| 三审 | 75/100 | — |
| 修订 3 | — | P0-1 泄漏率参数 target 居中 0.25/lsl 0.05（T2，消除 lsl<target 断言失败与负值）；P1-1 缺陷分配最大余数法 `_allocate` 保证 qty 之和==defect_qty 严格成立（T3/T4）；P1-2 热力图 [x,y] 轴修正为 [缺陷,工序]（T10）；P1-3 「口径 Q&A」更名「口径答疑 FAQ」+ grep 扩查（T1）；P1-4 计数型判定收敛 config.COUNTING_PARAM_PREFIXES 单一常量（T2/T3/T4）；P2 T11 pytest 数统一 9、登录回归加注册前置、weekday 注释、plan 注释、T5 公共字段清单、defects 字典序、get<T> code!=0 throw、main.py 重复运行警告（T3/T5/T7/T9/T11） |
| 四审 | 87/100 | — |
| 修订 4 | — | P1-1 requirements.txt 加 pytest>=7（T2）；P1-2 T6/T7 全部 mvn 命令补 JAVA_HOME export（4 处）；P2 缺陷总量预期 ≈1.4w-1.8w（T3）、主贡献措辞 35%-70%（参数表）、口径表加 ±0.5% 舍入容差、计划前提注明 mvnw 缺失改用 brew mvn（T0/前提） |

---

## Self-Review 自查

**Spec 覆盖：**
- 口径文档（良率/OEE/SPC/相关性/季节因子）→ T1 ✅
- 生成器直写 MySQL（制造模板）→ T2/T3 ✅
- 生成器口径校验 → T4 ✅
- 后端 5 实体+mapper（含 @TableField 显式映射）→ T5 ✅
- SPC 控制限计算+单测 → T6 ✅
- 洞察 service（良率/OEE/缺陷分布/SPC/相关性/摘要）+ service 单测 → T7 ✅
- 6 个 REST 端点 → T8 ✅
- 前端路由+服务封装（BaseResponse 解包）+页面+5 图表 → T9/T10 ✅
- 回归验证（不破坏 yubi 链路）+ README → T11 ✅
- 销售模板/AI 增强：明确不在本计划（W2），符合 scope 拆解 ✅

**类型一致性核查：** `QualityVO.SpcResult` 的字段（xBarUcl/xBarLcl/xBarCenter/rUcl/rLcl/rCenter）在 T7 service 写入、T8 契约、T9 TS 接口（`SpcResult`）一致；`OutOfControlPoint` 三字段一致；`ProcessDefectMatrix` 三字段一致。`SpcCalculator.Result` getter 与 T6 测试断言一致。T3 生成器 `SPC_SUBGROUP_SIZE=5` 与后端 n=5 一致；`YIELD_BASE=0.965` 与口径 ≥95% 一致。T5 实体 @TableField 列名与 DDL/QueryWrapper 裸字符串列名三方一致（`line_id`/`stat_date` 等）。缺陷矩阵与 SPC_SPEC 均为 (线, 工序) 组合键，T4 断言与 T3 消费一致。

**口径自洽性核查（第二轮新增）：** OEE 三分量由产能推导自洽——后端重算性能 = ideal×output/run = 采样性能 ∈ [88%,96%]（output = 产能×可用率×性能 的构造保证），OEE ∈ [74%, 94%] 落在标杆 85% 附近；缺陷记录 qty 之和 = defect_qty（按工序权重×类型权重分摊）；SPC 样本仅计量工序（9 工序 × 90 天 × 5 = 4050），质检计数工序明确排除。

**占位符扫描：** 无 TBD/「后续补充」；每步含完整代码或精确命令；T9 Step 4 的 `charts.tsx` 占位组件为**任务内临时文件**（T10 立即填充），非计划缺口。

**注意事项（非阻塞）：** T3 Step 4 的计数打印值可能因随机数实现细节与预期略有出入，以数据库实际 COUNT 为准；T6 Step 4 若既有测试（PostServiceTest 等）因依赖 Redis/ES 环境失败，属基线既有状态，仅需保证 SpcCalculatorTest/QualityServiceImplTest 通过；T3 缺陷行数因分摊舍入在 3k-5k 波动属正常（qty 总和口径已统一）；T9 页面 processId=2 依赖 `--reset` 后 AUTO_INCREMENT 稳定（无外键、TRUNCATE 重置，已加注释）。