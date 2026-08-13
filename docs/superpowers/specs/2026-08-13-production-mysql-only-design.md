# 生产环境收敛至 MySQL 设计

**日期：** 2026-08-13
**状态：** 已确认
**目标分支：** `chore/prod-mysql-only`

## 1. 背景与目标

当前后端使用两个 Spring profile：`dev` 连接本机 MySQL，`prod` 连接 KingbaseES。部署方已经确认不再使用人大金仓，要求把生产后端切回 MySQL，并从可运行代码、构建依赖、迁移入口、测试契约和当前状态文档中移除 KingbaseES 支持。

本次改动只改变数据库运行与构建边界，不改变 REST API、业务模型、认证授权、Excel 导入导出、软删除、审计或桌面客户端契约。现有 MySQL Flyway V1–V14 是唯一保留的结构演进路径。

## 2. 决策

采用 MySQL-only 方案：

- `dev` 继续使用现有本机 MySQL 配置，保持本地联调行为不变；
- `prod` 改用 MySQL Connector/J 和 `classpath:db/migration`；
- 删除 KingbaseES、PostgreSQL JDBC 和 Flyway PostgreSQL 数据库支持依赖；
- 删除 KingbaseES 专用 SQL/Java 迁移及静态契约测试；
- 生产数据库连接和所有安全秘密继续由环境变量注入；
- 不执行 KingbaseES 到 MySQL 的数据迁移，也不修改任何已经发布的 MySQL V1–V14 迁移。

不采用“只切换 `prod`、保留 KingbaseES 资产”的最小方案，也不新增 `prod-mysql`/`prod-kingbase` 双生产 profile。部署方已经明确结束 KingbaseES 使用，继续维护双数据库会留下无验证的代码路径、额外依赖和容易误用的配置。

## 3. 配置设计

`application.yml` 继续保存公共配置并默认激活 `dev`。`application-dev.yml` 不变。`application-prod.yml` 保留 `prod` 激活条件，并配置：

```yaml
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: ${PA_DB_URL:jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai}
    username: ${PA_DB_USERNAME:personnel_archive}
    password: ${PA_DB_PASSWORD:}
  flyway:
    locations: classpath:db/migration
```

Flyway 复用应用数据源，不再需要 `PA_FLYWAY_DB_URL`。`PA_DB_URL` 的默认值只适用于数据库与服务同机且由 loopback 访问的部署；远程数据库必须覆盖完整 URL，并保留 `connectionTimeZone=Asia/Shanghai`。是否启用 TLS、证书校验及其他连接参数由实际网络拓扑决定，并通过完整 `PA_DB_URL` 配置。

不能把 `application-dev.yml` 中的本机数据库密码、token pepper、身份证 HMAC 密钥或身份证加密密钥复制到生产配置。生产首次启动仍必须提供 `PA_DB_PASSWORD`、`PA_TOKEN_PEPPER`、`PA_IDENTITY_HMAC_KEY` 和 `PA_IDENTITY_ENCRYPTION_KEY`。

## 4. 构建与迁移边界

`pom.xml` 保留：

- `org.flywaydb:flyway-mysql`；
- `com.mysql:mysql-connector-j`。

`pom.xml` 删除：

- `org.flywaydb:flyway-database-postgresql`；
- `cn.com.kingbase:kingbase8`；
- `org.postgresql:postgresql`。

删除以下 KingbaseES 专用迁移入口：

- `src/main/resources/db/kingbase/`；
- `src/main/java/db/kingbase/`。

保留 `src/main/resources/db/migration/` 和 `src/main/java/db/migration/` 中的 MySQL V1–V14。不得重写已应用迁移；生产 MySQL 空库由 Flyway 从 V1 迁移到 V14，已有 MySQL 数据库只执行其尚未应用的版本。

本次不复制 KingbaseES 数据。切换部署前，运维方必须确认目标 MySQL `personnel_archive` 数据库及最小权限账号已创建，并自行决定是否需要另行授权的数据迁移工作。

## 5. 测试设计

先修改现有测试表达目标状态，并确认它们在旧实现上因 KingbaseES 配置而失败：

- `ProductionProfileConfigurationTest` 断言 `prod` 使用 MySQL 驱动、MySQL URL、`classpath:db/migration`，并验证 `PA_DB_URL`、`PA_DB_USERNAME`、`PA_DB_PASSWORD` 覆盖；
- `DatabaseDriverAvailabilityTest` 只验证 MySQL 驱动可加载并接受 MySQL URL；
- `DefaultAdminMigrationContractTest` 只锁定 MySQL V8 默认管理员迁移，不再比较 KingbaseES 副本。

随后删除 `KingbaseMigrationContractTest` 和 KingbaseES 迁移资产，再进行最小实现修改使测试转绿。验证还包括：

- `rg` 检查运行配置、依赖、Java/SQL 迁移和现行文档中不再宣称支持 KingbaseES；
- Maven 依赖树中不存在 KingbaseES 或 PostgreSQL 驱动；
- Java 21 下执行 `./mvnw verify`；
- 检查打包 JAR 可以成功生成。

真实 MySQL Testcontainers 测试在 Docker 可用时必须执行；若当前机器无 Docker，测试报告中的跳过项必须如实披露，不能声称已经完成真实数据库迁移验证。

## 6. 文档策略

同一后端分支更新：

- `README.md`：开发与生产均为 MySQL，删除 KingbaseES 部署说明和 `PA_FLYWAY_DB_URL`；
- `AGENTS.md`：当前服务状态、技术栈、数据库规则、测试边界和后续路线收敛为 MySQL-only。

日期化的历史设计/实施计划可以保留为决策历史，但不得被当前状态文档引用为仍受支持的运行路径。

仓库根目录不是 Git 仓库，因此根 `AGENTS.md` 与 `CODE_GUIDE.md` 无法进入后端分支提交。它们仍需在本地同步为 MySQL-only，并在最终交付中明确列为未包含在远端后端提交中的配套文档变更。

## 7. 分支、提交与交付

从 `origin/dev-yx-20260724` 的 `bfe44f5` 创建隔离 worktree 和 `chore/prod-mysql-only` 分支，避免带入原工作目录已暂存的 Excel 日期修改。

实施完成后：

1. 检查分支只包含本次 MySQL-only 范围；
2. 提交配置、依赖、迁移、测试和后端文档；
3. 将 `chore/prod-mysql-only` 推送到 `origin`；
4. 报告提交哈希、远端分支、测试数量、Docker 跳过项，以及根目录配套文档无法随后端 Git 分支推送的事实。
