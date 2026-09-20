# 本机部署归档（Local Deployment）

> 记录 2026-09-20 在本机（macOS, Apple Silicon）从零跑通前后端基线的完整过程：环境、容器、初始化、启动命令、验证结果与坑点。
> 用途：本地开发环境复现与排障参考；部署形态为「中间件容器化 + 应用本机运行」。

## 1. 环境概览

| 组件 | 版本 | 安装方式 | 说明 |
|------|------|---------|------|
| JDK | 17.0.20.1 (Homebrew) | `brew install openjdk@17` | keg-only，需显式 JAVA_HOME |
| Maven | 3.9.16 | `brew install maven` | 项目自带 `mvnw` wrapper 不完整（`.mvn/wrapper` 缺失），改用 brew maven |
| Node | v26.8.2 | 已有 | ≥18 即可 |
| pnpm | 11.5.0 | 已有 | 前端包管理（禁用 npm） |
| Docker 运行时 | colima 0.10.3 + docker 29.8.1 | `brew install colima docker docker-compose` | colima 轻量 VM（vz 虚拟化），替代 Docker Desktop |
| MySQL | mysql:8（容器） | Docker | 端口 3306 |
| RabbitMQ | rabbitmq:3-management（容器） | Docker | 端口 5672（AMQP）/ 15672（管理台） |
| Redis | redis:7（容器） | Docker | 端口 6379 |

## 2. Docker 运行时（colima）

### 启动与代理

```bash
colima start -f        # 首次启动需下载 VM 镜像（GitHub），可能较慢/中断，重试即可
colima status          # 确认 running
docker version         # 验证 daemon
```

**关键坑：colima VM 内拉镜像不走宿主代理，导致 `docker pull` 卡死。**

本机有代理（127.0.0.1:7893），需注入 VM。编辑 `~/.colima/default/colima.yaml` 的 `env` 段：

```yaml
env:
  http_proxy: http://host.lima.internal:7893
  https_proxy: http://host.lima.internal:7893
  no_proxy: localhost,127.0.0.1,192.168.5.0/24
```

然后 `colima restart`。`host.lima.internal` 是 VM 内访问宿主的固定域名（本例解析到 192.168.5.2）。

验证 VM 内网络：

```bash
LIMA_HOME=~/.colima/_lima limactl shell colima sh -c \
  'curl -s -m 8 -o /dev/null -w "%{http_code}\n" -x http://host.lima.internal:7893 https://registry-1.docker.io/v2/'
# 401 = 通（Docker Registry 未认证的预期响应）
```

## 3. 中间件容器

```bash
# MySQL（建库 yubi，root/123456 为本地演示口令，生产走环境变量）
docker run -d --name yubi-mysql --restart unless-stopped \
  -e MYSQL_ROOT_PASSWORD=123456 -e MYSQL_DATABASE=yubi \
  -p 3306:3306 mysql:8

# RabbitMQ（含管理台）
docker run -d --name yubi-rabbitmq --restart unless-stopped \
  -p 5672:5672 -p 15672:15672 rabbitmq:3-management

# Redis（源项目 RedissonConfig 启动即连，非可选）
docker run -d --name yubi-redis --restart unless-stopped \
  -p 6379:6379 redis:7
```

常用管理命令：

```bash
docker ps                          # 容器状态
docker logs -f yubi-mysql          # 看日志
docker exec yubi-mysql mysqladmin ping -uroot -p123456 --silent   # MySQL 就绪探测
```

## 4. 初始化

### 4.1 建库建表

```bash
docker exec -i yubi-mysql mysql -uroot -p123456 < backend/sql/create_table.sql
# 验证
docker exec yubi-mysql mysql -uroot -p123456 -e "USE yubi; SHOW TABLES;"   # chart, user
```

### 4.2 RabbitMQ 队列声明（必须手动执行）

后端 `@RabbitListener` 消费的队列不会自动创建，缺失会导致**应用启动后监听容器 fatal 退出**：

```bash
# bi_queue（BiMessageConsumer 业务队列）
docker exec yubi-rabbitmq rabbitmqadmin declare exchange name=bi_exchange type=direct durable=true
docker exec yubi-rabbitmq rabbitmqadmin declare queue name=bi_queue durable=true
docker exec yubi-rabbitmq rabbitmqadmin declare binding source=bi_exchange destination=bi_queue routing_key=bi_routingKey

# code_queue（bizmq/MyMessageConsumer 示例队列）
docker exec yubi-rabbitmq rabbitmqadmin declare exchange name=code_exchange type=direct durable=true
docker exec yubi-rabbitmq rabbitmqadmin declare queue name=code_queue durable=true
docker exec yubi-rabbitmq rabbitmqadmin declare binding source=code_exchange destination=code_queue routing_key=my_routingKey
```

## 5. 后端启动

```bash
cd backend

# JDK 17 必须显式指定（brew maven 默认绑定 Java 27，Spring Boot 2.7 不兼容）
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# 编译
mvn compile

# 启动（注意：必须用全坐标插件，见坑点 2）
nohup mvn -q org.springframework.boot:spring-boot-maven-plugin:run > /tmp/yubi-backend.log 2>&1 &

# 就绪探测
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/doc.html   # 200
```

配置文件 `backend/src/main/resources/application.yml` 本地默认值：MySQL `localhost:3306/yubi`（root/123456）、RabbitMQ `localhost:5672`（guest/guest）、Redis `localhost:6379`、AI 密钥为占位符（走环境变量）。

## 6. 前端启动

```bash
cd frontend
pnpm install    # postinstall 的 husky 会报 .git 找不到，属预期警告，不影响依赖安装

# dev 模式直接跑 max（绕过 pnpm run 的依赖自检，见坑点 5）
REACT_APP_ENV=dev MOCK=none UMI_ENV=dev nohup ./node_modules/.bin/max dev > /tmp/yubi-frontend.log 2>&1 &

curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/   # 200
```

**前置改动**：`frontend/config/proxy.ts` 补充 dev 段代理（源项目缺失，dev 下 `/api` 请求会打到前端自身）：

```ts
dev: {
  '/api/': {
    target: 'http://localhost:8080',
    changeOrigin: true,
  },
},
```

## 7. 端到端验证清单（2026-09-20 通过）

| 环节 | 路径 | 结果 |
|------|------|------|
| 接口文档 | `http://localhost:8080/api/doc.html` | HTTP 200 |
| 未登录态 | `GET /api/user/get/login` | `{"code":40100,"message":"未登录"}` |
| 注册 | `POST /api/user/register` | `code:0`，返回新用户 id |
| 登录 | `POST /api/user/login` | `code:0`，下发 Session Cookie |
| 会话保持 | 带 Cookie `GET /api/user/get/login` | `code:0`，返回用户信息 |
| 前端首页 | `http://localhost:8000/` | HTTP 200 |
| 前端代理→后端 | `GET http://localhost:8000/api/user/get/login` | 经代理转发至 8080 正常 |

> AI 生成图表链路（上传 Excel → AI → ECharts）本阶段未验证：AI 密钥未配置，留待二开阶段接入。

## 8. 坑点速查

| # | 现象 | 原因 | 解法 |
|---|------|------|------|
| 1 | `./mvnw` 报 wrapper 缺失 | 源项目 `.mvn/wrapper/` 未入库 | 改用 brew maven |
| 2 | `mvn spring-boot:run` 报 No plugin found for prefix | spring-boot 插件组不在默认 pluginGroups | 全坐标 `mvn org.springframework.boot:spring-boot-maven-plugin:run` |
| 3 | 启动报 `Connection refused: 6379` | RedissonConfig 启动即连 Redis | 必须起 Redis 容器 |
| 4 | 启动后监听容器 fatal，应用退出，报 `no queue 'xxx_queue'` | `@RabbitListener` 队列需手动声明 | 见 §4.2 |
| 5 | `pnpm dev` 自动重跑 install 并失败 | pnpm 依赖自检触发；postinstall husky 找不到 .git 返回非 0 | 直接 `./node_modules/.bin/max dev` |
| 6 | `docker pull` 卡死 | colima VM 不走宿主代理 | colima.yaml 注入 `host.lima.internal` 代理后 restart |
| 7 | `mvn -version` 显示 Java 27 | brew maven 默认依赖 openjdk 最新版 | 构建时显式 `JAVA_HOME` 指向 openjdk@17 |
| 8 | dev 下前端 API 全部 404 | proxy.ts 缺 dev 段 | 补 dev 代理到 8080（见 §6） |

## 9. 服务启停速记

```bash
# 容器（含 colima 依赖）
colima stop        # 停 VM（容器一并停）；colima start -f 恢复
docker start yubi-mysql yubi-rabbitmq yubi-redis   # 仅 VM 运行但容器停了时

# 后端
pkill -f "spring-boot-maven-plugin"   # 停
# 前端
pkill -f "max dev"                    # 停
```