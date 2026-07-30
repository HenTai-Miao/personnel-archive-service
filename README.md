# 人员档案数据服务

技术项目名为 `personnel-archive-service`。Java 21 / Spring Boot 4 模块化单体，支持 MySQL 与 KingbaseES V9，Flyway 是唯一数据库结构变更入口。默认及 `local` profile 使用 MySQL，`prod` profile 使用 KingbaseES。

Excel 业务字段均可留空；仅对已填写的身份证执行格式与加密保护，并只在非空姓名和非空身份证同时命中同一有效档案时要求覆盖或跳过。

内部 GitLab 项目：<http://49.232.13.110:8099/personnel-archive/personnel-archive-service>

完整的后端文件职责、事务/安全/导入导出流程和后续优化顺序见 [`../CODE_GUIDE.md`](../CODE_GUIDE.md)。修改模块或流程时必须同步更新该导读与本目录 `AGENTS.md`。

## 本地 MySQL 启动

必须通过环境变量提供数据库凭据和至少 32 字节的令牌、身份 HMAC 密钥，以及 Base64 编码的 32 字节 AES 密钥。数据库 `personnel_archive` 必须预先存在；可用本目录 `docker-compose.yml` 在 loopback 启动 MySQL 8.4.3：

`PA_DB_URL`、`PA_DB_USERNAME`、`PA_DB_PASSWORD`、`PA_TOKEN_PEPPER`、`PA_IDENTITY_HMAC_KEY`、`PA_IDENTITY_ENCRYPTION_KEY`。

本地自定义 MySQL `PA_DB_URL` 必须包含 `connectionTimeZone=Asia/Shanghai`。MySQL `DATETIME` 保存中国本地时间，服务将其映射为准确的 UTC `Instant` 后再通过 API 输出；不要在客户端固定增减八小时。

Flyway V8 会为默认租户创建一个启用的 `ADMIN` 登录：账号 `hr_6298bc833379`、显示名称“人资部门”。密码只以 BCrypt cost 12 哈希存在于迁移中，明文由部署负责人单独保管。

`AdminBootstrap` 兼容代码暂时保留：仅当数据库没有任何用户且显式提供管理员引导变量时才会运行；标准新库已由 V8 创建用户，因此不会触发该流程。

```bash
./mvnw verify
./mvnw spring-boot:run
```

macOS 本机联调可在仓库根目录双击 `启动本地测试.command`，它会启动现有 MySQL 容器、以 `local` profile 运行服务并先验证管理员登录；不会重建数据库卷。

## 生产 KingbaseES

`prod` profile 默认监听 `18080`，应用数据源是 `jdbc:kingbase8://10.141.29.96:54321/personnel_archive`。Flyway 按 KingbaseES 官方兼容方式使用独立的 PostgreSQL 协议 URL `jdbc:postgresql://10.141.29.96:54321/personnel_archive`，但两条 URL 指向同一 KingbaseES 数据库。

部署前由 DBA 创建空数据库 `personnel_archive`、账号并授予该 schema 建表及运行所需的最小权限。Flyway 建立 V8 最终结构、初始角色和默认管理员，不创建数据库本身，也不迁移已有 MySQL 数据。生产变量清单见 `config/application-prod.yml`；示例中的秘密为空，必须通过部署环境或秘密管理系统注入。

生产首次启动前至少配置 `SPRING_PROFILES_ACTIVE=prod`、`PA_DB_USERNAME`、`PA_DB_PASSWORD` 和三项安全密钥。`PA_DB_URL` 与 `PA_FLYWAY_DB_URL` 可覆盖默认服务器地址。部署后须在 KingbaseES 空库实际验收迁移、默认管理员登录、导入、覆盖、查询、软删除和 XLSX/ZIP 导出；本仓库的离线测试不会连接本地或生产 KingbaseES。

生产 Excel 模板路径固定为 `classpath:/excel/personnel-archive-template-v1.xlsx`；参考空表中的失效命名范围不会被使用。
