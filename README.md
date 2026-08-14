# 人员档案数据服务

技术项目名为 `personnel-archive-service`。Java 21 / Spring Boot 4 模块化单体仅使用 MySQL，Flyway 是唯一数据库结构变更入口。`application.yml` 保存公共配置并默认启用 `dev`；`application-dev.yml` 和 `application-prod.yml` 都使用 MySQL V1–V15 迁移路径。

Excel 业务字段均可留空；仅对已填写的身份证执行格式与加密保护，并只在非空姓名和非空身份证同时命中同一有效档案时要求覆盖或跳过。

内部 GitLab 项目：<http://49.232.13.110:8099/personnel-archive/personnel-archive-service>

完整的后端文件职责、事务/安全/导入导出流程和后续优化顺序见 [`../CODE_GUIDE.md`](../CODE_GUIDE.md)。修改模块或流程时必须同步更新该导读与本目录 `AGENTS.md`。

## dev / 本地 MySQL 启动

`application-dev.yml` 沿用当前本机 MySQL 连接和三项开发安全值；数据库 `personnel_archive` 必须预先存在。可用本目录 `docker-compose.yml` 在 loopback 启动 MySQL 8.4.3，也可通过以下环境变量覆盖开发值：

`PA_DB_URL`、`PA_DB_USERNAME`、`PA_DB_PASSWORD`、`PA_TOKEN_PEPPER`、`PA_IDENTITY_HMAC_KEY`、`PA_IDENTITY_ENCRYPTION_KEY`。

本地自定义 MySQL `PA_DB_URL` 必须包含 `connectionTimeZone=Asia/Shanghai`。MySQL `DATETIME` 保存中国本地时间，服务将其映射为准确的 UTC `Instant` 后再通过 API 输出；不要在客户端固定增减八小时。

Flyway V8 保留历史默认管理员种子，V15 再将同一用户 ID 轮换为账号 `hr_dept`、显示名称“人资部门”和唯一 `ADMIN` 角色，同时解除锁定并撤销该用户的既有会话。密码只以经过验证的 BCrypt cost 12 哈希存在于迁移中，明文由部署负责人单独保管；若历史账号和目标账号同时存在或默认用户缺失，V15 会失败而不会合并或静默新建用户。

`AdminBootstrap` 兼容代码暂时保留：仅当数据库没有任何用户且显式提供管理员引导变量时才会运行；标准新库会依次应用 V8 和 V15，因此不会触发该流程。

```bash
./mvnw verify
./mvnw spring-boot:run
```

macOS 本机联调可在仓库根目录双击 `启动本地测试.command`，它只会在需要时启动已有 MySQL 容器，并以 `dev` profile 运行服务；不会创建或重建数据库卷。

## 生产 MySQL

`prod` profile 固定监听 `18080`，使用 MySQL Connector/J 和 `classpath:db/migration`。数据源固定指向同机 `127.0.0.1:3306/personnel_archive`，固定使用已经提供的应用账号和密码，并保留 `connectionTimeZone=Asia/Shanghai`。按当前生产交付约定，`PA_DB_*`、`PA_SERVER_PORT` 和 `PA_LOG_*` 均不能覆盖这些值；数据库密码不得打印到终端、日志或文档中。

三项安全值也按用户明确要求固定在 `prod` 配置中，生产启动不再读取外部密钥文件。Flyway 使用同一应用数据源执行 MySQL V1–V15，不再使用独立的 Flyway URL。部署前由 DBA 创建数据库 `personnel_archive`、固定应用账号并完成库级授权；Flyway 不创建数据库本身，也不迁移其他数据库产品中的数据。若以后修改数据库密码或三项安全值，必须重新完成 Java 21 验证并制作离线包；已有数据只能继续使用原 HMAC/AES 值。

部署后须在目标 MySQL 验收迁移、默认管理员登录、导入、覆盖、查询、软删除和 XLSX/ZIP 导出。当前离线交付的固定数据库凭据和三项安全值都包含在 JAR 中，因此 JAR 必须作为敏感生产安装介质保管，不得提交、推送、公开上传或发送给无关人员。

生产 Excel 模板路径固定为 `classpath:/excel/personnel-archive-template-v1.xlsx`；参考空表中的失效命名范围不会被使用。

## 统信 UOS 离线部署

`deployment/uos/` 是同机 MySQL 生产部署的唯一离线包源。先在 Java 21 下完成
`./mvnw verify` 并确认可执行 JAR，再使用只复制白名单文件的制作脚本：

```bash
./deployment/uos/build-bundle.sh \
  target/personnel-archive-service-0.1.0-SNAPSHOT.jar \
  /目标输出目录
```

制作结果包含同名目录、`.tar.gz` 和 `.tar.gz.sha256`，遇到同名产物自动追加
`-1`、`-2`，不会覆盖旧包。制作脚本会核对 Spring Boot 启动入口、`prod` 配置、
Flyway V1/V14/V15 和生产 Excel 模板，并为目录内每个白名单文件生成 `SHA256SUMS`；它
不会重新执行 Maven。制作器会从临时生产 JAR 副本中移除内嵌的
`application-dev.yml`，原始 Maven JAR 和本地联调配置保持不变。按用户明确要求，最终
JAR 内含固定 `prod` 数据库连接（包括已提供的应用密码）和三项固定安全值，因此离线包
本身必须按敏感生产安装介质保管；制作器不会携带人员数据、本机附件或 macOS
`._` AppleDouble 元数据。

目标统信 UOS 电脑须预装 Java 21、MySQL 8 和基本系统命令。先按包内
`docs/02-数据库授权.txt` 在目标机核对 `personnel_archive@127.0.0.1` 的固定账号授权，
再以 root 运行 `./install.sh`。数据库连接已经固定在 `prod` 配置中，安装器不会询问或
记录数据库密码；三项安全值已经固定在 prod JAR 中，普通重复安装保留全部日志，
`--no-start` 可跳过本次启用/启动。安装器不联网、不调用包管理器，
也不尝试从制作电脑连接目标 MySQL。

固定安装位置为 `/data/personnel-archive-service`，不会改动 `/opt` 中的 MySQL、JDK 或
其他软件。systemd 服务以无登录权限的 `personnel-archive` 用户运行，不需要外部环境
文件。首次只传固定路径的生产 JAR 和 `启动prod环境.sh` 时，脚本也能创建服务账号、
日志目录与 systemd 单元后启动。安装、授权、验证、日志查询和恢复命令均以中文文档随包交付。

## 生产运行日志

`prod` 默认同时保留控制台日志和每日纯文本文件日志。当天第一条日志直接写入进程工作
目录下的 `./log/log.YYYY-MM-DD.log`，例如 `./log/log.2026-08-13.log`。跨日后直接写入
下一日期文件；同一天服务重启时继续追加当天文件。

日志不压缩，不按大小分片，不限制保留天数或历史总量，也不自动删除。生产配置没有
单独的通用活动日志名，没有 `.gz`、`%i`、`max-file-size`、`max-history` 或
`total-size-cap`，并且不接受 `PA_LOG_*` 环境变量覆盖。运维人员需要自行监控 `/data`
磁盘空间。

标准 systemd 安装路径下可分别查看文件日志和控制台日志：

```bash
tail -F /data/personnel-archive-service/log/log.$(date +%F).log
journalctl -u personnel-archive-service -f
```

服务端为每个 HTTP 请求生成自己的 `X-Request-Id`，响应头、错误响应、通用请求完成日志和同一请求内的数据库审计记录使用同一个编号。可以按编号检索一次请求的认证、导入、查询、敏感字段/照片查看、删除及导出检查点。客户端提供的请求编号不会被信任。

文件日志用于故障诊断，`sys_operation_log` 中的数据库审计记录仍是合规审计的权威来源。运行日志只记录内部 ID、数量、固定结果码、字节数、耗时、异常类型和受限代码位置；不得记录密码、令牌、数据库秘密、用户名、姓名、身份证、电话、地址、健康数据、搜索词、删除原因、工作簿文件名/路径/哈希/单元格内容、照片内容、请求正文、查询字符串或请求头。生产环境不得开启 SQL 参数、请求正文或 MyBatis/JDBC DEBUG 日志。
