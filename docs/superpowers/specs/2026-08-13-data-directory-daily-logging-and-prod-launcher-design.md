# `/data` 生产部署、每日永久日志与启动脚本修订设计

**日期：** 2026-08-13  
**状态：** 用户已确认需求，等待书面复核  
**目标分支：** `chore/prod-mysql-only`  
**前置设计：** `2026-08-13-offline-production-bundle-and-operational-logging-design.md`

## 1. 修订目标

本设计是既有统信 UOS 离线生产部署设计的增量修订。数据库模型、REST API、
Flyway V1–V14、Java 21、MySQL 连接目标和业务规则保持不变，只调整以下交付行为：

1. 服务从 `/opt/personnel-archive-service` 改装到
   `/data/personnel-archive-service`；`/opt` 留给目标电脑既有的 MySQL 和 JDK；
2. 生产文件日志取消单文件大小、保留天数和历史总量限制，改为每个自然日一份、
   每日归档后 gzip 压缩、永久保留；
3. 把用户列出的服务启动、配置、数据库、Flyway、认证、导入、查询、敏感查看、
   照片、导出、删除和未知异常全部纳入可验证的日志覆盖表；
4. 在离线包内提供 `start-prod.sh`，并在桌面另外交付中文名
   `启动prod环境.sh`，用于启动已安装的 systemd 生产服务。

目标电脑仍然不可从构建机连接。所有目标数据库、systemd 和真实业务验收都由操作员
在离线电脑执行。

## 2. 最终目录和权限

安装后的固定布局为：

```text
/data/personnel-archive-service/
├── personnel-archive-service.jar       # root:personnel-archive，0640
└── log/                                # personnel-archive:personnel-archive，0750
    ├── personnel-archive-service.log   # 当天活动日志，0640
    ├── personnel-archive-service.YYYY-MM-DD.log.gz
    └── install-YYYYMMDD-HHMMSS.随机值.log

/etc/personnel-archive-service/
├── personnel-archive-service.env       # root:root，0600
└── backup/                             # root:root，0700

/etc/systemd/system/
└── personnel-archive-service.service   # root:root，0644
```

安装器不得创建、移动、覆盖或删除 `/opt` 下的 MySQL、JDK 或其他文件。Java 21 可以
实际安装在 `/opt`；安装器只通过 `command -v java` 和 `readlink -f` 解析正在生效的
Java 绝对路径，再把该路径写入 systemd 单元。

安装器在创建项目目录前确认 `/data` 存在、是目录、不是符号链接并且所在文件系统至少
有 2 GiB 可用空间。不会自动格式化、挂载或创建 `/data` 文件系统。服务账号只能写
`/data/personnel-archive-service/log`，不能修改 JAR、环境文件或 systemd 单元。

如果目标电脑曾试装过旧 `/opt/personnel-archive-service` 版本，新安装器不自动删除旧
目录；它只安装并启动 `/data` 版本，并在文档中要求操作员验收成功后再人工判断是否
清理旧目录，避免误删 `/opt` 中其他软件。

## 3. 每日一份、永久保留的文件日志

### 3.1 已选实现

新增 classpath `logback-spring.xml`。生产 profile 使用 Logback
`TimeBasedRollingPolicy`，不再使用 Spring Boot 默认的
`SizeAndTimeBasedRollingPolicy`：

```xml
<rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
    <fileNamePattern>${PA_LOG_ARCHIVE_PATTERN}</fileNamePattern>
</rollingPolicy>
```

生产环境的两个路径属性为：

```text
PA_LOG_FILE=/data/personnel-archive-service/log/personnel-archive-service.log
PA_LOG_ARCHIVE_PATTERN=/data/personnel-archive-service/log/personnel-archive-service.%d{yyyy-MM-dd}.log.gz
```

仓库内默认值仍使用相对工作目录：

```text
./log/personnel-archive-service.log
./log/personnel-archive-service.%d{yyyy-MM-dd}.log.gz
```

systemd 把 `WorkingDirectory` 固定到 `/data/personnel-archive-service`，同时由受保护环境
文件注入绝对日志路径，因此正常部署时日志位置不依赖启动命令所在目录。

### 3.2 明确取消的设置

最终 `application-prod.yml`、`logback-spring.xml`、systemd 环境文件和安装脚本中均不
得存在以下生产轮转设置：

```text
logging.logback.rollingpolicy.max-file-size
logging.logback.rollingpolicy.max-history
logging.logback.rollingpolicy.total-size-cap
PA_LOG_MAX_FILE_SIZE
PA_LOG_MAX_HISTORY
PA_LOG_TOTAL_SIZE_CAP
```

历史文件名不包含 `%i`，因此不会因为同一天达到某个大小而生成 `.0`、`.1` 等分片。
`maxHistory` 和 `totalSizeCap` 都不配置，Logback 不自动删除历史文件。日期模式只含
`yyyy-MM-dd`，所以在有下一条日志事件时按自然日切换；`.gz` 后缀使已结束日期的日志
自动压缩。

当天始终向 `personnel-archive-service.log` 追加；跨日后将上一日归档成唯一的
`personnel-archive-service.YYYY-MM-DD.log.gz`。同一天内服务重启继续追加活动文件，
不会按进程启动次数额外创建日志。

### 3.3 无限保留的已知后果

该策略没有任何自动删除边界，满足用户“不要时长、不要最大限制”的要求，但也意味着
无法保证磁盘永不写满。应用和安装器不得擅自删除历史日志。运维文档和启动脚本必须
显示：

```bash
df -h /data
du -sh /data/personnel-archive-service/log
```

磁盘趋满时由公司运维人员先备份再人工清理已确认不再需要的历史日志。文件日志之外，
systemd journal 仍保留为第二诊断通道，其保存策略由目标 UOS 自身管理。

### 3.4 非生产 profile

`dev` 继续使用 Spring Boot 默认控制台日志，不因本次修订自动生成生产文件日志。
`logback-spring.xml` 使用 Spring profile 区分：`prod` 同时输出控制台和每日文件，
非 `prod` 只输出控制台。控制台输出继续进入 systemd journal。

## 4. 完整业务日志验收矩阵

“完整日志”定义为关键业务流程每项都有稳定的开始、成功和失败事件，并能通过服务端
`requestId` 串联；它不表示记录业务字段内容、密码或二进制数据。正常阶段使用
`INFO`，可预期业务拒绝使用 `WARN`，未知异常使用 `ERROR`。

所有完成或失败事件都记录 `durationMs`。HTTP 请求内的事件使用同一个服务端 UUID；
非 HTTP 的启动事件使用日志关联默认值 `requestId=system`。

| 领域 | 必须存在的事件 | 允许记录 | 禁止记录 |
|---|---|---|---|
| 服务生命周期 | `service_starting`、`service_ready`、`service_start_failed` | 版本、Java 版本、profile、端口、日志绝对路径、阶段、耗时 | 环境变量内容、秘密值 |
| 安全配置 | `security_configuration_checked` | 三项密钥各自的 configured 布尔值 | 密钥值、长度、摘要 |
| 数据库连接 | `database_connection_started/completed/failed` | host、port、schema、结果、异常类型、耗时 | 用户名、密码、完整 JDBC 查询参数 |
| Flyway | `flyway_migration_started/completed/failed` | 执行前版本、目标/最终版本、迁移数量、结果、异常类型、耗时 | SQL 参数、数据库凭据 |
| HTTP | `http_request_completed` | 方法、匹配后的路由模板、状态、内部 userId、结果、耗时 | 原始 URI、查询串、请求头、正文 |
| 登录/刷新/退出 | `auth_*_started/completed/failed` | 内部 userId、固定结果或错误码、耗时 | 用户名原文、密码、token、Cookie |
| 导入批次 | `import_batch_started/completed/failed` | operatorId、batchId、文件数、结果计数、耗时 | 文件名、路径、哈希、Excel 内容 |
| 导入单项 | `import_item_started/parsed/completed/failed` | batchId、itemId、序号、文件字节数、段落计数、结果码、personId、耗时 | 单元格、姓名、身份证、手机号、工作簿字节 |
| 重复决定 | `import_decision_started/completed/failed` | itemId、operatorId、personId、`OVERWRITE`/`SKIP`、结果、耗时 | 档案字段内容 |
| 导入历史 | `import_history_started/completed/failed` | operatorId、页码、大小、返回数、总数、耗时 | 文件名和错误消息正文 |
| 档案列表 | `person_list_started/completed/failed` | operatorId、页码、大小、返回数、总数、耗时 | 姓名、过滤值 |
| 家庭搜索 | `family_search_started/completed/failed` | operatorId、页码、大小、返回数、总数、耗时 | 搜索词、姓名 |
| 档案详情 | `person_detail_started/completed/failed` | operatorId、personId、结果、耗时 | 档案字段内容 |
| 敏感字段 | `sensitive_value_started/completed/failed` | operatorId、personId、字段类型、结果、耗时 | 身份证或手机号实际值 |
| 照片 | `photo_started/completed/failed` | operatorId、personId、字节数、结果、耗时 | 图片内容、原文件名 |
| 单份导出 | `archive_export_started/completed/failed` | operatorId、personId、字节数、结果、耗时 | 工作簿内容、含姓名文件名 |
| 批量导出 | `batch_export_started/completed/failed` | operatorId、数量、ZIP 字节数、结果、耗时 | 档案内容、ZIP 条目名 |
| 删除 | `person_deletion_started/completed/failed` | operatorId、personId、结果、错误码、耗时 | 删除原因正文、档案字段 |
| 未知异常 | `request_failed` | requestId、失败阶段、异常类型、有界代码位置 | 异常消息、SQL、请求内容 |

现有代码已经覆盖其中大多数事件。本次实现重点补齐：

- 数据库连接与 Flyway 的显式统一事件和耗时；
- 三项安全密钥分别记录 configured 布尔值；
- 导入批次、历史读取、档案列表、搜索、详情、敏感查看和照片的缺失开始/失败事件；
- 应用上下文启动失败时的安全 `service_start_failed` 事件；
- 每个新增事件的成功、业务失败、未知失败和敏感哨兵测试。

Flyway 继续执行原生 INFO 日志，同时增加应用自有的固定事件。应用自有迁移策略在调用
`flyway.migrate()` 前安全读取当前版本，记录开始时间；完成后只记录迁移结果对象中的
版本、执行数量和耗时；异常时使用现有安全异常摘要，仅输出类型与有界代码位置。

## 5. 日志与数据恢复的边界

文件日志用于定位、追溯和运维记录，不能把数据库恢复到某个时间点。真正的数据一致性
和恢复仍由以下机制保证：

- 每个工作簿独立数据库事务，失败时整份回滚；
- `OVERWRITE` 保留旧快照并建立新旧人员内部 ID 链接；
- 删除使用软删除并保留子表、照片和导入历史；
- `sys_operation_log` 保存权威操作审计；
- 公司运维执行加密 MySQL 备份并验证恢复。

日志中的内部 ID、事件结果和 requestId 用于确认需要检查的事务或快照，但安装包不提供
根据文本日志自动改写数据库的“回滚脚本”。

## 6. systemd 与安装器修订

systemd 单元改为：

```text
WorkingDirectory=/data/personnel-archive-service
ExecStart=<Java 21绝对路径> -jar /data/personnel-archive-service/personnel-archive-service.jar
ReadWritePaths=/data/personnel-archive-service/log
ConditionPathIsReadWrite=/data/personnel-archive-service/log
ConditionPathExists=/data/personnel-archive-service/personnel-archive-service.jar
```

数据库与安全配置继续通过：

```text
EnvironmentFile=/etc/personnel-archive-service/personnel-archive-service.env
SPRING_PROFILES_ACTIVE=prod
```

安装器的完整性校验、Java 21 检查、专用账号、无回显密码输入、本地密钥生成、
`--reconfigure` 只替换数据库密码、systemd 启用和 18080 就绪检查保持不变。
安装日志改写到 `/data/personnel-archive-service/log`。安装器和文档中的旧 `/opt` 项目
路径必须全部消失，但检查 Java 实际路径时允许结果位于 `/opt`。

## 7. 独立 prod 启动脚本

仓库新增 `deployment/uos/start-prod.sh`，构建器将它列入白名单、`SHA256SUMS` 和
`.tar.gz`。最终桌面另外生成内容完全相同、可单独传输的：

```text
/Users/zhongerbing/Desktop/启动prod环境.sh
```

脚本只负责启动已经由 `install.sh` 安装完成的服务，不从解压目录直接执行 JAR，也不
安装或重新配置数据库。执行方式：

```bash
chmod +x 启动prod环境.sh
sudo ./启动prod环境.sh
```

行为顺序：

1. 要求 root，设置安全 `umask`，不启用 `set -x`；
2. 检查 systemd 正在运行、服务单元、`/data` JAR、日志目录和 root-only 环境文件存在；
3. 只检查环境文件的所有者和权限，不读取、`source`、打印或复制内容；
4. 检查 systemd 单元实际指向 `/data`，拒绝误启动旧 `/opt` 服务；
5. 输出 Java 版本、`df -h /data` 和日志目录 `du -sh`；
6. 服务未运行时执行 `systemctl enable --now personnel-archive-service.service`；已运行时
   不重启，只继续健康检查；
7. 最多等待 60 秒，要求服务为 `active` 且 TCP 18080 已监听；
8. 成功时显示状态、日志路径和常用查看命令后退出 0；
9. 失败时显示不含环境内容的 systemd 状态、最近 journal 和文件日志末尾，再退出非零。

脚本不保持实时日志窗口，不会因终端关闭而停止后台服务。需要持续查看时由操作者另行
执行 `journalctl -fu personnel-archive-service.service` 或 `tail -F`。

## 8. 离线包与桌面交付

新离线目录至少包含：

```text
人员档案数据服务-统信UOS离线部署-20260813[-N]/
├── personnel-archive-service.jar
├── install.sh
├── start-prod.sh
├── service/personnel-archive-service.service.template
├── mysql/01-轮换应用密码并授权.sql
├── docs/01-安装前检查.txt
├── docs/02-数据库授权.txt
├── docs/03-离线安装与验证.txt
├── docs/04-日常运维与日志排查.txt
├── docs/05-故障恢复.txt
├── BUILD-INFO.txt
└── SHA256SUMS
```

构建器继续使用防覆盖名称，不能覆盖现有桌面三件套。目录、`.tar.gz` 和外部
`.tar.gz.sha256` 必须来自同一暂存目录，内部清单增加 `start-prod.sh`。桌面的中文启动
脚本生成后另附自己的 `.sha256`，以便单文件传输后验证。

最终 JAR 仍移除临时生产副本内的 `application-dev.yml`，不包含真实密码、环境文件、
附件、人员数据、工作簿上传、照片或数据库转储。构建机不连接目标数据库。

## 9. 测试与验收

实施遵循先失败测试、后最小实现：

1. 生产日志配置测试断言存在每日 `%d{yyyy-MM-dd}.log.gz`，不存在 `%i`、
   `max-file-size`、`max-history`、`total-size-cap` 及对应环境变量；
2. Logback 配置测试断言 `prod` 使用 `TimeBasedRollingPolicy`，控制台和文件 appender
   同时启用，非 `prod` 不创建生产文件 appender；
3. 业务日志测试逐行覆盖第 4 节事件矩阵，并用密码、令牌、用户名、姓名、身份证、
   手机号、搜索词、删除原因、文件名和异常消息哨兵断言不落日志；
4. Flyway 日志测试覆盖空库、已是 V14、迁移成功和迁移异常，断言版本、数量、结果和
   耗时字段存在而 JDBC 凭据不存在；
5. UOS 合约测试断言安装路径、systemd 单元、环境日志路径、磁盘检查全部使用 `/data`，
   同时拒绝出现旧项目路径 `/opt/personnel-archive-service`；
6. 启动脚本测试覆盖 shell 语法、root 保护、配置权限检查、旧单元拒绝、已运行幂等、
   启动成功、60 秒失败诊断以及不读取环境文件；
7. 构建器测试断言 `start-prod.sh` 位于白名单、内部清单和压缩包，压缩包没有
   `._` 或 `__MACOSX`；
8. Java 21 下执行完整 `./mvnw verify`，Docker 可用时运行真实 MySQL 8.4
   Testcontainers 的 V1–V14 和业务流程；
9. 从刚验证的 Maven JAR 制作新的防覆盖离线包，校验内部和外部 SHA-256；
10. 检查最终 JAR 不含 `application-dev.yml`，扫描离线目录、压缩包和中文启动脚本，
    确认不含附件旧密码、本地开发秘密或任何明文秘密赋值；
11. 验证用户现有 `ExcelArchiveReader.java` 修改和已暂存
    `ExcelNumericDatePrecisionTest.java` 未被覆盖，并执行其对应测试；
12. 更新服务 `AGENTS.md`、根 `AGENTS.md`、根 `CODE_GUIDE.md` 和全部 UOS 文档中的
    路径、每日轮转、无限保留、业务事件和启动命令说明。

目标离线电脑最终验收：

- `/data/personnel-archive-service/personnel-archive-service.jar` 存在；
- 文件日志持续写入 `/data/personnel-archive-service/log`；
- 跨日后仅产生一个对应日期的 `.log.gz`，没有 `.0/.1` 大小分片；
- `systemctl is-active personnel-archive-service.service` 为 `active`；
- 18080 端口监听，Flyway 最大成功版本为 14；
- 以不含真实个人信息的测试档案完成登录、导入、查询、查看、导出、删除和退出冒烟；
- 每个流程能以 requestId 找到开始、成功或失败事件；
- 日志不存在密码、密钥、token、身份证、手机号、照片、Excel 内容和删除原因正文；
- `启动prod环境.sh` 在服务停止时能启动，在服务已运行时保持幂等；
- 运维人员知晓历史日志不会自动删除，已建立磁盘空间人工检查安排。

## 10. 完成标准

本次修订完成需要同时满足：

- 第 3 节禁止的所有大小、天数和总量设置从最终生产配置与交付包中消失；
- `/opt/personnel-archive-service` 从运行配置、脚本、文档和最终包中消失；
- 第 4 节事件矩阵全部有自动化证据，且敏感哨兵不落日志；
- Java 21 完整验证通过，Docker 跳过情况如实说明；
- 新 JAR、目录包、压缩包、校验文件和中文启动脚本位于桌面并通过重算校验；
- 用户未提交的 Excel 日期修改保持原样；
- 不连接或修改目标离线 MySQL，不改动本机构建环境中的 `/opt` 或 `/data`。
