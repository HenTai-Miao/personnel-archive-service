# 人员档案数据服务

技术项目名为 `personnel-archive-service`。Java 21 / Spring Boot 4 模块化单体，MySQL 是唯一持久化存储，Flyway 是唯一数据库结构变更入口。

Excel 业务字段均可留空；仅对已填写的身份证执行格式与加密保护，并只在非空姓名和非空身份证同时命中同一有效档案时要求覆盖或跳过。

内部 GitLab 项目：<http://49.232.13.110:8099/personnel-archive/personnel-archive-service>

完整的后端文件职责、事务/安全/导入导出流程和后续优化顺序见 [`../CODE_GUIDE.md`](../CODE_GUIDE.md)。修改模块或流程时必须同步更新该导读与本目录 `AGENTS.md`。

## 本地启动

必须通过环境变量提供数据库凭据和至少 32 字节的令牌、身份 HMAC 密钥，以及 Base64 编码的 32 字节 AES 密钥。数据库 `personnel_archive` 必须预先存在；可用本目录 `docker-compose.yml` 在 loopback 启动 MySQL 8.4.3：

`PA_DB_URL`、`PA_DB_USERNAME`、`PA_DB_PASSWORD`、`PA_TOKEN_PEPPER`、`PA_IDENTITY_HMAC_KEY`、`PA_IDENTITY_ENCRYPTION_KEY`。

首次且仅在数据库没有任何用户时，可显式提供 `PA_BOOTSTRAP_ADMIN_USERNAME`、至少 12 字符的 `PA_BOOTSTRAP_ADMIN_PASSWORD` 和可选 `PA_BOOTSTRAP_ADMIN_DISPLAY_NAME` 创建 ADMIN。系统没有默认账号或默认密码；创建后应移除这三个引导变量。

```bash
./mvnw verify
./mvnw spring-boot:run
```

macOS 本机联调可在仓库根目录双击 `启动本地测试.command`，它会启动现有 MySQL 容器、以 `local` profile 运行服务并先验证管理员登录；不会重建数据库卷。

生产 Excel 模板路径固定为 `classpath:/excel/personnel-archive-template-v1.xlsx`；参考空表中的失效命名范围不会被使用。
