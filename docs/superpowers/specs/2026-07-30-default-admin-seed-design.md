# 双数据库默认管理员 V8 设计

## 目标

通过 Flyway 为 MySQL 和 KingbaseES 同时初始化一个可登录的管理员账号：

- 显示名称：`人资部门`
- 登录账号：`hr_6298bc833379`
- 权限：`ADMIN`
- 密码：使用用户指定值，但仓库只保存 BCrypt cost 12 哈希，任何 SQL、测试、文档和日志都不得出现明文。

## 迁移版本与文件

不修改已经发布的 MySQL V1–V7 或 KingbaseES V7 基线，新增两个逻辑等价的 V8：

- `src/main/resources/db/migration/V8__seed_default_admin.sql`
- `src/main/resources/db/kingbase/V8__seed_default_admin.sql`

`dev` profile 运行 MySQL V8，`prod` profile 在 KingbaseES V7 基线之后运行 KingbaseES V8。两套迁移都通过租户代码 `DEFAULT` 和角色代码 `ADMIN` 查询外键，不依赖角色或用户自增 ID。

## dev/prod 配置收口

后端只保留 `dev` 和 `prod` 两个运行环境，不再使用 `local` profile 或工程根下的外置 `config/` 目录：

- `src/main/resources/application.yml`：只保存两个环境共用的应用名、HTTP 端口、上传限制、Jackson、MyBatis、安全期限、Excel 模板和管理员引导占位；设置默认 profile 为 `dev`，不包含数据库 URL、账号或 Flyway 路径。
- `src/main/resources/application-dev.yml`：激活条件为 `dev`，连接用户现有的本机 MySQL，使用 `classpath:db/migration`。现有本机联调值从 `config/application-local.yml` 原样迁入，确保不创建、不重置 Docker 数据卷。
- `src/main/resources/application-prod.yml`：激活条件为 `prod`，应用使用 Kingbase JDBC，Flyway 使用指向同一 KingbaseES 数据库的 PostgreSQL JDBC，迁移位置为 `classpath:db/kingbase`；生产秘密继续只从环境变量注入。

删除整个 `config/` 目录，不再保留生产变量示例文件。根 `启动本地测试.command` 改为检查 classpath 下的 `application-dev.yml` 并以 `dev` profile 启动；配置测试、README、`AGENTS.md` 和 `CODE_GUIDE.md` 中的 `local` 服务 profile 与旧路径引用同步改为 `dev`。前端 `.env.local` 仍表示 Vite 本机模式，不属于后端 Spring profile，本次不改名。

## 账号写入语义

迁移必须确定性地保证该凭据可以登录：

1. 账号不存在时，创建启用状态的用户，写入规范化账号、显示名称和 BCrypt 哈希。
2. 同租户同规范化账号已存在时，将显示名称、密码哈希和启用状态重置为本设计值，同时清零失败次数并解除账号锁定。
3. 使用数据库各自的 upsert 语法：MySQL `ON DUPLICATE KEY UPDATE`，KingbaseES `ON CONFLICT ... DO UPDATE`。
4. 查询该用户和 `ADMIN` 角色后写入 `sys_user_role`；已经存在的关联保持不变，不产生重复记录。
5. 显式更新 `updated_at`，不依赖 MySQL 的 `ON UPDATE` 行为。

如果 V1/V7 应有的默认租户或 `ADMIN` 角色缺失，V8 不得通过硬编码其他 ID 绕过结构异常。正常 Flyway 顺序会先建立这两项种子数据。

## 密码和安全边界

应用使用 `BCryptPasswordEncoder(12)`，迁移中的哈希必须由同一算法生成，并在写入迁移前由一次性离线进程验证 `matches=true`。明文只用于这次哈希生成和验证，不写入源文件、测试断言、Maven 参数、日志或提交信息；交付记录只报告验证结果，不展示输入值。

这是用户明确要求的固定初始凭据。部署完成后应受内部凭据管理约束；本次不扩展密码修改、首次登录强制改密或用户管理界面。

现有 `AdminBootstrap` 保留以避免无关重构，但 V8 执行后数据库已有用户，因此新的标准 Flyway 数据库不会再触发“空用户库”管理员引导。生产环境示例和说明不再把 `PA_BOOTSTRAP_ADMIN_*` 作为首次启动必需项。

## 验证

新增无数据库迁移契约测试，校验：

- MySQL 与 KingbaseES V8 都存在；
- 两者包含相同账号、显示名称和 ADMIN 关联；
- 两者包含同一个 BCrypt cost 12 哈希；
- 两者均不含用户指定的明文密码；
- MySQL 使用 `ON DUPLICATE KEY UPDATE`，KingbaseES 使用 `ON CONFLICT`。

扩展真实 MySQL Testcontainers 迁移测试，验证 Flyway 到 V8 后用户存在、启用、显示名称正确、哈希与 V8 契约一致，并且 `ADMIN` 角色关联唯一。该集成测试只使用隔离的 Testcontainers 数据库，不连接用户现有的 MySQL Docker。密码与哈希的对应关系由前述一次性离线验证确认，避免把明文固化进测试源码。

根据既定限制，本次不连接 KingbaseES；Kingbase V8 只做静态契约、资源打包和 Flyway 位置检查。真实 KingbaseES 登录仍属于部署验收边界。

## 文档同步

更新服务 `README.md`、服务 `AGENTS.md`、根 `AGENTS.md` 和 `CODE_GUIDE.md`：默认数据从“一个租户＋三个角色”调整为“一个租户＋三个角色＋一个固定 ADMIN 登录”，Flyway 当前版本调整为 V8，配置说明只保留 `dev`/`prod`，并明确仓库不保存默认管理员明文密码。
