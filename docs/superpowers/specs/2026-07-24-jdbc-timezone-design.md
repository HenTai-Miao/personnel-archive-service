# JDBC 时间戳时区修复设计

> 本文关于业务 `DATE`/`LocalDate` 不变的描述是当时的历史范围；业务日期已由 2026-07-29 来源值保真设计和 Flyway V7 改为 `VARCHAR` 来源字符串。本文的 `DATETIME(6)` 时区规则仍然有效。

## 背景与目标

人员档案列表把数据库中的 `2026-07-24 11:06:01.887644` 显示成了 `2026/07/24 19:06`。数据库、macOS 与 Electron 均使用中国标准时间；偏移来自 JDBC 默认连接把 MySQL `DATETIME(6)` 的中国本地墙上时间误解释成 UTC。

目标是让数据库时间戳以 `Asia/Shanghai` 解释，继续通过 API 输出准确的 UTC `Instant`，由桌面端按系统时区显示。现有数据库值不迁移，前端不增加固定减八小时逻辑。

## 方案比较与决定

1. **采用：统一 JDBC 连接时区为 `Asia/Shanghai`。** 把 Connector/J 9.7 的旧别名 `serverTimezone=UTC` 替换为正式参数 `connectionTimeZone=Asia/Shanghai`。这与当前数据库已保存的中国本地 `DATETIME` 值一致，改动最小且不需要数据迁移。
2. **不采用：把全部数据库时间迁移为 UTC。** 该方案需要迁移所有 `DATETIME(6)` 列、调整 MySQL 会话时区并审计历史数据，超出本次修复范围，且会给当前正确的墙上时间值带来迁移风险。
3. **禁止：前端固定减八小时。** 这会把存储层错误复制到多个页面，并在不同系统时区下再次产生错误。

## 时间语义与数据流

- MySQL `DATETIME(6)` 继续保存中国本地墙上时间，现有记录保持不变。
- JDBC 连接用 `connectionTimeZone=Asia/Shanghai` 解释这些值；例如数据库 `11:06` 映射为 `03:06Z`。
- Java 读取模型继续使用 `Instant`。
- Jackson 继续使用 UTC 序列化，不改为中国时区；API 返回的 `Z` 表示正确时间点。
- Electron 继续使用 `Intl.DateTimeFormat` 按运行系统时区显示，不做人工偏移。
- `DATE` / `LocalDate` 业务字段（出生日期、参加工作日期等）不经过时区换算，本次不修改。

## 代码与配置范围

- 修改主配置和本地测试配置中的 JDBC URL，使用 Connector/J 9.7 的 `connectionTimeZone=Asia/Shanghai`。
- 在服务端真实 MySQL 集成测试中复用生产默认 JDBC 查询参数，写入固定的 `DATETIME(6)` 值并断言读取出的 `Instant`。
- 更新服务 README、服务知识库和根 `CODE_GUIDE.md` 中的时间戳策略，明确自定义 `PA_DB_URL` 也必须保持 `Asia/Shanghai` 连接语义。
- 不修改数据库迁移、表结构、业务数据、OpenAPI 字段形状或桌面端格式化代码。

## 回归测试

新增真实 MySQL 回归场景：

1. Given：测试容器中存在固定 `DATETIME(6)` 值 `2026-07-24 11:06:01.887644`，连接保留主配置的 JDBC 查询参数。
2. When：通过 Connector/J 读取为 `java.sql.Timestamp` / `Instant`。
3. Then：结果必须为 `2026-07-24T03:06:01.887644Z`，在 `Asia/Shanghai` 渲染后仍为 `11:06`。

完整验证运行 `./mvnw verify`，并在本地已运行的 MySQL/服务/桌面端链路中确认档案列表不再多八小时。Docker 不可用时不能把 Testcontainers 跳过视为通过。

## 风险与失败边界

- `PA_DB_URL` 可覆盖默认 URL；部署文档必须明确自定义值包含 `connectionTimeZone=Asia/Shanghai`，否则仍可能恢复错误解释。
- 不启用 `forceConnectionTimeZoneToSession=true`：地理时区强制写入 MySQL 会话依赖服务端时区表，而当前修复只需让驱动正确解释既有中国本地 `DATETIME`。数据库部署继续保持中国标准时间。
- 若将来决定全面采用 UTC 存储，应单独设计数据迁移，不能与本修复混合。
