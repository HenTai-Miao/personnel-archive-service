# 人员档案数据服务

技术项目名为 `personnel-archive-service`。Java 21 / Spring Boot 4 模块化单体仅使用 MySQL，Flyway 是唯一数据库结构变更入口。`application.yml` 保存公共配置并默认启用 `dev`；`application-dev.yml` 和 `application-prod.yml` 都使用 MySQL V1–V14 迁移路径。

Excel 业务字段均可留空；仅对已填写的身份证执行格式与加密保护，并只在非空姓名和非空身份证同时命中同一有效档案时要求覆盖或跳过。

内部 GitLab 项目：<http://49.232.13.110:8099/personnel-archive/personnel-archive-service>

完整的后端文件职责、事务/安全/导入导出流程和后续优化顺序见 [`../CODE_GUIDE.md`](../CODE_GUIDE.md)。修改模块或流程时必须同步更新该导读与本目录 `AGENTS.md`。

## dev / 本地 MySQL 启动

`application-dev.yml` 沿用当前本机 MySQL 连接和三项开发安全值；数据库 `personnel_archive` 必须预先存在。可用本目录 `docker-compose.yml` 在 loopback 启动 MySQL 8.4.3，也可通过以下环境变量覆盖开发值：

`PA_DB_URL`、`PA_DB_USERNAME`、`PA_DB_PASSWORD`、`PA_TOKEN_PEPPER`、`PA_IDENTITY_HMAC_KEY`、`PA_IDENTITY_ENCRYPTION_KEY`。

本地自定义 MySQL `PA_DB_URL` 必须包含 `connectionTimeZone=Asia/Shanghai`。MySQL `DATETIME` 保存中国本地时间，服务将其映射为准确的 UTC `Instant` 后再通过 API 输出；不要在客户端固定增减八小时。

Flyway V8 会为默认租户创建一个启用的 `ADMIN` 登录：账号 `hr_6298bc833379`、显示名称“人资部门”。密码只以 BCrypt cost 12 哈希存在于迁移中，明文由部署负责人单独保管。

`AdminBootstrap` 兼容代码暂时保留：仅当数据库没有任何用户且显式提供管理员引导变量时才会运行；标准新库已由 V8 创建用户，因此不会触发该流程。

```bash
./mvnw verify
./mvnw spring-boot:run
```

macOS 本机联调可在仓库根目录双击 `启动本地测试.command`，它只会在需要时启动已有 MySQL 容器，并以 `dev` profile 运行服务；不会创建或重建数据库卷。

## 生产 MySQL

`prod` profile 默认监听 `18080`，使用 MySQL Connector/J 和 `classpath:db/migration`。默认数据源指向同机 `127.0.0.1:3306/personnel_archive`；远程或不同端口的生产数据库必须通过 `PA_DB_URL` 覆盖完整 JDBC URL，并保留 `connectionTimeZone=Asia/Shanghai`。

生产首次启动前至少配置 `SPRING_PROFILES_ACTIVE=prod`、`PA_DB_USERNAME`、`PA_DB_PASSWORD` 和三项安全密钥。Flyway 使用同一应用数据源执行 MySQL V1–V14，不再使用独立的 Flyway URL。部署前由 DBA 创建数据库 `personnel_archive`、账号并授予最小权限；Flyway 不创建数据库本身，也不迁移其他数据库产品中的数据。

部署后须在目标 MySQL 验收迁移、默认管理员登录、导入、覆盖、查询、软删除和 XLSX/ZIP 导出。生产凭据和三项安全密钥只能通过部署环境或秘密管理系统注入。仓库不提供外置 `config/`，也不保存任何生产秘密。

生产 Excel 模板路径固定为 `classpath:/excel/personnel-archive-template-v1.xlsx`；参考空表中的失效命名范围不会被使用。
