# AI Insight Platform — 制造/销售数据洞察平台

> 基于智能 BI 源项目二开的**数据洞察平台**——聚焦**制造质量数据**与**销售数据**两大业务域，以 AIGC 驱动数据分析与图表生成。

## 定位

- **制造质量洞察（主）**：良率趋势 / 缺陷分布 / OEE / SPC 控制图 / 缺陷-工序相关性分析
- **销售洞察（副）**：季节性 / 促销效应 / SKU 长尾 / 区域表现
- **AIGC 分析**：自然语言提出分析需求 → AI 生成图表与结论（异步队列）
- **数据自造**：内置模拟数据生成器（双业务模板、行业参数可切换），口径文档化，演示可复现

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java Spring Boot 2.7 · MyBatis-Plus · RabbitMQ · AI 接入 |
| 前端 | React · Ant Design Pro · ECharts |
| 数据 | MySQL · Python 模拟数据生成器（pipeline/） |
| 部署 | Docker · (规划) Kubernetes / Terraform |
| 测试 | Playwright E2E（AI 生成用例，规划） |

## 快速启动

前置：JDK 17+ · Node 18+ · MySQL 8 · RabbitMQ

```bash
# 后端（先建库执行 backend/sql/create_table.sql，配置 application.yml 的 mysql/rabbitmq/AI 密钥——密钥走环境变量）
cd backend && ./mvnw spring-boot:run

# 前端
cd frontend && pnpm install && pnpm dev
```

> 包管理：后端 mvnw / 前端 pnpm（勿引入 package-lock.json）。

## 目录结构

```
backend/     Spring Boot 后端（源项目 + 二开模块）
frontend/    React 前端
pipeline/    Python 模拟数据生成器（规划）
docs/        数据口径设计文档（data-model.md 待建）
e2e/         Playwright 测试套件（规划）
```

## 状态与路线图

- [x] 源项目落地（backend/frontend）
- [x] 制造质量洞察模块（W1）
- [ ] 数据生成设计文档（docs/data-model.md）
- [ ] AIGC 分析 + 销售模板（W2）
- [ ] 测试套件 + 部署（W3）

数据口径详见 `docs/data-model.md`（规划中）。

## 模块说明

### 制造质量洞察

- **后端**：`/api/quality/*` 接口——良率 / OEE / SPC / 缺陷分析（`backend/src/main/java/com/yupi/springbootinit/` 下 `controller/`、`service/`）
- **前端**：`/quality` 页面，ECharts 可视化（`frontend/src/`）
- **数据**：Python pipeline 生成模拟制造数据（`pipeline/`）