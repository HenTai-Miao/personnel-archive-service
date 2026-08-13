# 统信 UOS 离线生产部署包与安全运行日志设计

**日期：** 2026-08-13
**状态：** 用户已完成书面复核；实施中
**目标分支：** `chore/prod-mysql-only`
**目标系统：** 已安装 JDK 21、MySQL 8、systemd 的统信 UOS 离线电脑

## 1. 背景与目标

目标电脑处于离线环境，文件可以逐个传入，但 Codex 无法也不应尝试连接该电脑。目标电脑已经完成 MySQL 的基础安装：数据库 `personnel_archive` 和应用账号 `personnel_archive@127.0.0.1` 已创建，数据库使用 `utf8mb4` 与 `utf8mb4_0900_ai_ci`。现有记录没有证明应用账号的库级授权已经完成，而且记录中的旧应用密码已经进入过明文附件/工具输出，因此该密码必须作废并在目标电脑上重新生成。

后端当前已经是 Java 21、Spring Boot 4、MySQL-only，并且 `prod` 默认连接同机 `127.0.0.1:3306/personnel_archive`、使用 `classpath:db/migration` 执行 Flyway V1–V14。此次工作不改变数据库模型、REST API 或业务规则，重点完成以下交付能力：

- 构建可在目标 JDK 21 上运行的 Spring Boot 可执行 JAR；
- 制作不依赖网络的一键安装包，在统信 UOS 上安装并托管为 systemd 服务；
- 安装时安全接收轮换后的数据库密码，并在目标电脑本地生成三项安全密钥；
- 保证生产日志写入 JAR 同级的 `log/` 目录，并保留 systemd journal 作为第二诊断通道；
- 为请求、认证、导入、查询、敏感查看、导出、删除和异常增加安全的关键阶段日志；
- 提供数据库授权、安装、验证、运维和故障排查说明；
- 生成 SHA-256 校验文件，并把最终离线包与压缩包放到用户桌面；
- 保留并验证当前工作区中尚未提交的 Excel 数值日期精度修复，不覆盖、不回退该修改。

## 2. 已选方案与备选方案

### 2.1 已选：分层安全运行日志与完整离线部署包

采用三层诊断信息：

1. Spring Boot 文件日志记录通用框架日志和安全业务检查点；
2. 同一进程的控制台输出由 systemd journal 保存；
3. 安装脚本独立记录逐步安装结果。

业务日志采用固定事件名、内部 ID、结果码和耗时，不记录用户提供的字段值。每个 HTTP 请求由服务端生成关联号，写入 MDC、响应头和错误响应安全详情，从而可以从客户端报错追踪到完整服务端路径。

这是可诊断性、磁盘容量和个人信息保护之间的推荐平衡。

### 2.2 未选：只增加 `logging.file.name`

这个方案能把现有控制台日志复制到文件，但当前业务代码只有极少数日志点，导入单文件未知异常甚至会被转换为稳定错误码而不留下足够定位信息。它不能满足“发生问题时知道失败在哪一步”的目标。

### 2.3 未选：全局 DEBUG、SQL 参数和请求/响应正文日志

这个方案表面上信息最全，但会把密码、令牌、身份证、手机号、地址、Excel 内容、照片元数据或删除原因写入日志，也会快速消耗离线电脑磁盘。它违反本项目敏感数据边界，因此明确禁止。

## 3. 安装后目录与权限

固定安装布局如下：

```text
/opt/personnel-archive-service/
├── personnel-archive-service.jar       # root:root，0644
├── personnel-archive-service.jar.previous（升级时才可能存在）
└── log/                                # personnel-archive:personnel-archive，0750
    ├── personnel-archive-service.log
    ├── personnel-archive-service.YYYY-MM-DD.N.log.gz
    └── install-YYYYMMDD-HHMMSS.log

/etc/personnel-archive-service/
└── personnel-archive-service.env       # root:root，0600

/etc/systemd/system/
└── personnel-archive-service.service   # root:root，0644
```

安装脚本创建不可登录的专用系统账号和同名组 `personnel-archive`。JAR 与服务定义由 root 管理，运行账号只能写入 `log/`。数据库密码和三项安全密钥只保存在 root 可读的环境文件中；systemd 以管理器权限读取环境文件，再以低权限账号启动 Java 进程。

安装或升级不得删除现有日志。首次安装时才生成三项安全密钥；重复安装发现已有环境文件时默认保留全部秘密。显式重新配置流程只重新提示并替换数据库密码，必须原样保留 token pepper、身份证 HMAC 密钥和身份证 AES 密钥。HMAC/AES 密钥与既有数据库内容绑定，普通重新配置时重生成会造成历史身份证密文不可读、重复检索键失效；真正的密钥轮换必须使用单独的数据迁移方案，不属于本安装包。重新配置前创建 root-only 备份，备份文件名不包含任何秘密值。

## 4. 生产配置设计

现有 `application-prod.yml` 继续使用以下数据库契约：

```text
PA_DB_URL=jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai
PA_DB_USERNAME=personnel_archive
PA_DB_PASSWORD=<只在目标电脑交互输入>
```

服务端口继续默认 `18080`，允许 `PA_SERVER_PORT` 覆盖。数据库密码、token pepper、身份证 HMAC 密钥和身份证 AES-256 密钥不得出现在仓库、JAR、打包目录、校验清单、安装输出或日志中。

生产文件日志增加以下可覆盖配置：

```yaml
logging:
  file:
    name: ${PA_LOG_FILE:./log/personnel-archive-service.log}
  logback:
    rollingpolicy:
      file-name-pattern: ${PA_LOG_ARCHIVE_PATTERN:./log/personnel-archive-service.%d{yyyy-MM-dd}.%i.log.gz}
      max-file-size: ${PA_LOG_MAX_FILE_SIZE:20MB}
      max-history: ${PA_LOG_MAX_HISTORY:30}
      total-size-cap: ${PA_LOG_TOTAL_SIZE_CAP:1GB}
      clean-history-on-start: false
```

Spring Boot 默认只输出控制台日志；配置 `logging.file.name` 后同时写入文件，相对路径以进程工作目录解析。参考：[Spring Boot Logging](https://docs.spring.io/spring-boot/reference/features/logging.html) 和 [Spring Boot 4 Logging How-to](https://docs.spring.io/spring-boot/4.0/how-to/logging.html)。

systemd 单元把 `WorkingDirectory` 固定为 `/opt/personnel-archive-service`，并在环境文件中使用绝对 `PA_LOG_FILE` 与 `PA_LOG_ARCHIVE_PATTERN`，形成双重保证：正常 systemd 启动时日志一定进入 JAR 同级 `log/`；手工启动说明则要求先切换到 JAR 所在目录。

日志默认单文件最大 20 MB，按日期与序号滚动，历史压缩保存 30 个周期，总量上限 1 GB。保留上限防止离线机器因日志无限增长耗尽磁盘。SQL 语句参数、MyBatis DEBUG、Hikari DEBUG、HTTP 正文和 multipart 内容保持关闭。

## 5. systemd 服务设计

安装脚本检测 `java` 的绝对路径并确认其主版本为 21，再把该绝对路径写入服务单元。服务单元至少包含：

- `WorkingDirectory=/opt/personnel-archive-service`；
- `EnvironmentFile=/etc/personnel-archive-service/personnel-archive-service.env`；
- `User=personnel-archive` 与 `Group=personnel-archive`；
- `ExecStart=<检测到的 Java 21 绝对路径> -jar /opt/personnel-archive-service/personnel-archive-service.jar`；
- `Restart=on-failure`、合理的重启间隔和启动频率限制；
- `SuccessExitStatus=143` 与 `UMask=0027`；
- `NoNewPrivileges=true`、`PrivateTmp=true` 和兼容 UOS 的基础文件系统保护；
- 明确允许服务账号写入 `/opt/personnel-archive-service/log`；
- 标准输出和标准错误进入 journal，不覆盖文件日志。

服务以 `SPRING_PROFILES_ACTIVE=prod` 启动。首次启动时 Flyway 使用同一个应用数据源执行 V1–V14。数据库不可达、权限不足、密钥缺失或 Flyway 失败都会让服务启动失败；systemd 在有限次数重试后停止，避免无限循环刷满日志。

## 6. 安装包内容与离线流程

桌面交付目录使用日期化名称，包含：

```text
人员档案数据服务-统信UOS离线部署-20260813/
├── personnel-archive-service.jar
├── install.sh
├── service/
│   └── personnel-archive-service.service.template
├── mysql/
│   └── 01-轮换应用密码并授权.sql
├── docs/
│   ├── 01-安装前检查.txt
│   ├── 02-数据库授权.txt
│   ├── 03-离线安装与验证.txt
│   ├── 04-日常运维与日志排查.txt
│   └── 05-故障恢复.txt
└── SHA256SUMS
```

同时生成同名 `.tar.gz`，方便一次传输；目录形式保留给用户逐个传文件。压缩包本身也生成独立 SHA-256 校验值。

安装脚本完全离线运行，不调用 `apt`、`yum`、`dnf`、`curl`、`wget` 或任何网络地址。执行顺序为：

1. 校验 root、systemd、CPU/操作系统基本环境和可用磁盘；
2. 校验 JAR 与模板文件的 SHA-256；
3. 定位并验证 Java 21；
4. 创建服务账号、安装目录和日志目录；
5. 安全提示输入数据库新密码，输入不回显且拒绝换行等不安全内容；
6. 仅在首次安装时，在目标电脑使用加密安全随机源生成 token pepper、HMAC key 和 32 字节 AES key；重新配置只替换数据库密码并保留这三项密钥；
7. 使用适合 systemd EnvironmentFile 的转义写入临时配置，检查权限后原子替换；
8. 安装 JAR 与 systemd 单元，执行 `daemon-reload` 和 `enable`；
9. 启动服务并进行有时限的进程/端口检查；
10. 输出成功摘要，或输出日志位置、状态命令和下一步排障命令。

脚本使用显式步骤日志而不是 shell `set -x`。密码输入、随机密钥生成、环境文件写入和 systemd 环境加载步骤只记录“成功/失败”，永不回显值或打印环境文件内容。

## 7. MySQL 交接设计

目标电脑必须以有管理权限的 MySQL 账号执行以下等价操作：

```sql
ALTER USER 'personnel_archive'@'127.0.0.1'
    IDENTIFIED BY RANDOM PASSWORD
    PASSWORD EXPIRE NEVER;

GRANT ALL PRIVILEGES ON `personnel_archive`.*
    TO 'personnel_archive'@'127.0.0.1';

SHOW GRANTS FOR 'personnel_archive'@'127.0.0.1';
```

SQL 文件只含命令与说明，不含实际密码。操作者从 MySQL 输出中取得新随机密码，并直接输入安装脚本。文档提醒操作者不要把新密码再次保存到聊天、截图、普通文本或安装日志。

安装脚本不自动使用 root 数据库账号，也不自动执行授权 SQL，避免在磁盘或进程参数中处理 MySQL 管理员密码。目标数据库连接验证只发生在目标电脑启动服务时；本地构建和测试绝不尝试连接离线电脑。

首次成功启动后，用只读命令确认：

- `SHOW GRANTS` 包含 `personnel_archive` 库权限；
- `flyway_schema_history` 的最大成功版本是 `14`；
- 业务表已创建；
- 默认管理员迁移成功，但任何文档和日志都不得展示其明文密码。

## 8. 请求关联与日志格式

新增请求关联过滤器，在每次请求进入时生成服务端 UUID，不信任或复用外部提交的关联号。关联号写入：

- SLF4J MDC 的 `requestId`；
- 每条应用日志的关联区；
- 响应头 `X-Request-Id`；
- 未知错误响应现有 `details` 映射中的 `requestId`。

请求结束必须在 `finally` 中清理 MDC，避免线程池复用导致请求编号串线。通用请求完成日志只记录 HTTP 方法、Spring 匹配后的路由模板、状态码、已认证内部用户 ID（如有）和耗时；不得记录原始 URI 查询串、Authorization 头、Cookie、请求正文或客户端上传文件名。

日志采用稳定键值格式，例如：

```text
event=import_item_completed requestId=<uuid> batchId=15 itemId=42 result=IMPORTED durationMs=816
```

所有自由文本在进入日志前都被排除或映射成固定枚举/错误码，防止换行注入和个人信息泄露。

## 9. 业务日志检查点

日志级别约定：正常阶段与结果使用 `INFO`；可预期业务拒绝、无权限、输入校验和暂时故障使用 `WARN`；未预期异常使用 `ERROR`。业务审计表继续作为合规事实来源，文件日志只用于运行诊断，不替代审计。

| 领域 | 开始/成功检查点 | 失败检查点 | 允许字段 | 明确禁止字段 |
|---|---|---|---|---|
| 服务启动 | profile、应用版本、Java 版本、端口、日志路径 | 配置类别、异常类型 | 固定配置元数据 | 秘密值、完整环境变量 |
| 数据库/Flyway | 数据库 host/port/schema、迁移版本、耗时 | 阶段、异常类型 | 非秘密连接目标 | 密码、SQL 参数 |
| HTTP | 方法、路由模板、状态、耗时 | 状态、错误码 | requestId、内部 userId | 查询串、请求头、正文 |
| 登录/刷新/退出 | 动作、结果、内部 userId | 固定失败码 | 内部 ID | 用户名原文、密码、token、IP |
| 导入批次 | batchId、文件数、汇总、耗时 | 固定错误码 | 批次/条目/人员内部 ID | Excel 内容、哈希、路径、文件名、姓名 |
| 导入单项 | itemId、序号、校验/待决定/导入/覆盖/跳过结果 | 固定错误码、异常类型 | 状态枚举、计数、耗时 | 单元格值、身份证、手机号、工作簿字节 |
| 列表/搜索/详情 | 页码、返回数、personId、耗时 | 固定错误码 | 分页和内部 ID | 搜索词、姓名和档案字段 |
| 敏感字段/照片 | personId、字段类型、结果、大小 | 固定错误码 | 操作者、内部 ID | 解密值、照片字节、原文件名 |
| 单份/批量导出 | personId/数量、结果、字节数、耗时 | 固定错误码 | 内部 ID、计数 | Excel 内容、导出文件名中的姓名 |
| 删除 | personId、操作者、结果 | 固定错误码 | 内部 ID | 删除原因和档案字段 |

未知异常日志记录请求号、失败事件、异常类、因果异常类和有限数量的调用栈位置，不记录异常消息。这样可以定位代码路径，同时避免第三方解析器或数据库异常消息把 Excel 值、路径或 SQL 参数带入日志。单文件导入的 `RuntimeException` 捕获路径必须留下这种安全错误日志，不能再无声吞掉。

## 10. 启动与安装事件

应用就绪事件记录：

- `event=service_ready`；
- 应用版本和 Java 主版本；
- 活动 profile；
- 服务端口；
- 生产日志绝对路径；
- 数据库目标的 host、port、schema（从 JDBC URL 安全解析）；
- 启动总耗时。

只记录三项安全密钥“已配置”的布尔检查，不输出密钥值、长度、摘要或其他可推断内容；数据库密码不参与任何日志字段。Flyway 自身 INFO 日志保留，但不把 Flyway/MyBatis/Hikari 调高到可能打印连接属性或 SQL 参数的 DEBUG 级别。

安装日志使用中文编号步骤，例如：

```text
[步骤 1/10] 检查离线运行环境
[步骤 2/10] 校验部署文件
...
[步骤 10/10] 检查服务启动结果
```

每步包含时间、结果和非秘密错误摘要。脚本从创建安装目录与 `log/` 后开始把输出追加到日期化安装日志；在此之前仅输出无秘密的预检信息。

## 11. 故障处理与恢复

- **Java 不是 21：** 安装在复制 JAR 前失败，明确显示检测到的主版本和配置 JDK 21 的方法。
- **校验和不匹配：** 不安装任何载荷，提示重新传输对应文件。
- **环境文件已有配置：** 默认完整保留；显式重新配置时先备份，只安全替换数据库密码并原样保留三项既有密钥，再使用原子方式落盘。
- **日志目录不可写：** 启动前失败并修复目录所有权，不能在无文件日志的情况下把服务标记为安装成功。
- **数据库不可达或密码错误：** 服务启动失败，文件日志和 journal 均保留 MySQL/Flyway 的安全失败类型；安装脚本不回显密码。
- **授权不足：** Flyway 失败并停止启动，文档引导重新执行 `SHOW GRANTS` 和库级授权。
- **Flyway 失败：** 不修改迁移文件、不执行 `repair` 或手工跳过版本；先备份数据库并根据日志定位具体版本。
- **磁盘趋满：** Logback 轮转上限限制历史日志；运维文档提供 `du`、当前日志大小和 journal 占用检查命令。
- **升级启动失败：** 保留新旧 JAR和全部日志，不盲目自动回退。Flyway 可能已经推进数据库版本，只有确认旧 JAR 与当前 schema 兼容后才能人工恢复 `.previous`。
- **进程反复失败：** systemd 启动频率限制会停止重试，防止日志风暴；使用状态、journal 和文件日志三方定位。

任何卸载或清理流程都不得默认删除 `/etc/personnel-archive-service` 的秘密配置、`log/` 或数据库。若未来提供删除命令，必须单独明确确认数据范围。

## 12. 实施边界与预计改动

预计在后端仓库中增加或修改：

- `application-prod.yml`：生产文件日志和轮转参数；
- 请求关联过滤器、应用就绪日志组件和安全异常摘要工具；
- 认证、导入、查询、敏感查看、导出与删除的关键阶段日志；
- 全局异常处理：复用请求关联号并返回安全 requestId；
- 日志配置、关联号、敏感数据不落日志和业务事件测试；
- `deployment/uos/`：安装脚本、systemd 模板、MySQL 指令和离线运维文档；
- 构建/组装脚本：复制已验证 JAR、生成清单与 SHA-256；
- `README.md`、服务 `AGENTS.md`、根 `CODE_GUIDE.md` 和根 `AGENTS.md` 的现状说明。

不会修改：

- MySQL V1–V14 已应用迁移；
- 数据库结构与业务数据；
- Electron 客户端 API 地址或打包；
- Excel 模板；
- 默认管理员账号契约；
- 目标电脑防火墙、网络、MySQL 服务配置或 JDK 安装。

当前 `ExcelArchiveReader.java` 和 `ExcelNumericDatePrecisionTest.java` 的未提交修改属于用户工作，实施时原样保留并纳入验证；不将其误报为本次日志/部署代码，也不通过 reset、checkout 或 clean 丢弃。

## 13. 测试与验证设计

遵循先测试后实现：

1. 扩展生产配置测试，断言日志文件、轮转默认值和环境变量覆盖；
2. 增加请求关联过滤器测试，验证 UUID、响应头、MDC 清理和不记录查询串；
3. 增加全局异常测试，验证同一 requestId 被复用且异常消息不落日志；
4. 使用内存日志 appender 验证认证、导入、敏感查看、导出和删除的固定事件；
5. 使用哨兵密码、令牌、身份证、手机号、搜索词、文件名和删除原因，断言它们均不出现在日志；
6. 验证单文件导入未知异常有 ERROR 事件，但不泄露异常消息；
7. 验证安装脚本 shell 语法、root/Java/checksum 预检、秘密不回显和重复安装策略；
8. 验证 systemd 模板包含专用账号、固定工作目录、绝对日志路径和安全重启策略；
9. Java 21 下执行 `./mvnw verify`，如 Docker 不可用必须如实报告 Testcontainers 跳过；
10. 执行 `./mvnw package` 并检查可执行 JAR 内含生产配置、模板和 MySQL Flyway V1–V14；
11. 对工作区与交付目录执行秘密扫描，确认不包含附件旧密码或任何新秘密；
12. 组装桌面目录和 `.tar.gz`，从清单重新校验所有 SHA-256；
13. 在不连接目标数据库的前提下完成所有本地验证，明确把目标启动/Flyway/接口验证列为离线电脑验收步骤。

不会为了测试启动 `prod` 去连接用户本机或离线电脑的真实 MySQL。数据库集成验证只允许使用项目既有的隔离 Testcontainers；没有 Docker 时不伪造成功。

## 14. 离线电脑验收标准

操作者在目标电脑完成以下检查后，部署才算真正上线：

- `java -version` 显示 Java 21；
- `SHOW GRANTS FOR 'personnel_archive'@'127.0.0.1'` 显示目标库权限；
- `systemctl is-enabled personnel-archive-service` 为 enabled；
- `systemctl is-active personnel-archive-service` 为 active；
- `ss` 显示服务监听 `18080`；
- `flyway_schema_history` 最大成功版本为 V14；
- `/opt/personnel-archive-service/log/personnel-archive-service.log` 存在且持续追加；
- `journalctl -u personnel-archive-service` 能看到同一启动过程；
- 制造一次不含真实个人信息的失败请求后，可以用 `X-Request-Id` 在文件日志中找到对应记录；
- 日志中不存在数据库密码、三项安全密钥、访问令牌或测试哨兵敏感值；
- 使用测试档案完成登录、导入、列表、详情、导出和退出的最小冒烟流程。

本地交付只能证明 JAR、配置、脚本、测试和校验包完整；由于目标电脑离线且不可连接，不能提前声称目标 MySQL、systemd 或真实业务流程已经验收通过。

## 15. 交付完成标准

本次任务完成需要同时满足：

- 设计与实施计划已复核；
- 日志安全边界、轮转、requestId 和关键业务检查点有自动化测试；
- Java 21 Maven 验证与 JAR 构建通过，Docker 跳过情况透明；
- 离线部署目录与压缩包位于用户桌面，校验和可重算；
- 打包内容没有真实数据库密码或其他秘密；
- 现有 Excel 数值日期修改未丢失，并通过其对应测试；
- 所有受影响的 `README.md`、服务 `AGENTS.md`、根 `CODE_GUIDE.md` 和根 `AGENTS.md` 已同步；
- 最终交付说明明确区分“本地已验证”和“目标离线电脑待执行验收”。
