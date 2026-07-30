# dev/prod 配置与默认管理员收口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将后端配置收口为公共配置加 `dev`/`prod` 两个 profile，删除外置 `config/`，并补齐已提交 MySQL/KingbaseES V8 固定 `ADMIN` 登录的集成断言与文档。

**Architecture:** `application.yml` 只承载公共配置并默认启用 `dev`；`application-dev.yml` 使用用户现有本机 MySQL 和 MySQL Flyway 路径；`application-prod.yml` 使用 Kingbase JDBC 与 PostgreSQL 协议 Flyway 路径。已经提交的两套 V8 继续负责创建或重置固定管理员，本任务不改写 V1–V8。

**Tech Stack:** Java 21、Spring Boot 4.0.7、Spring Security BCrypt、MyBatis、Flyway 11.14.1、MySQL 8.4、KingbaseES V9、JUnit 5、AssertJ、Vitest。

## Global Constraints

- 后端运行环境只有 `dev` 和 `prod`；前端 Vite `.env.local` 不改名。
- 删除 `personnel-archive-service/config/`，不再创建同类外置配置目录或生产变量示例文件。
- `dev` 继续连接用户现有的 MySQL Docker；不创建、不重置、不删除其容器或数据卷。
- `prod` 应用 URL 默认 `jdbc:kingbase8://10.141.29.96:54321/personnel_archive`，Flyway URL 默认 `jdbc:postgresql://10.141.29.96:54321/personnel_archive`，HTTP 端口保持 `18080`。
- MySQL 使用 `classpath:db/migration`；KingbaseES 使用 `classpath:db/kingbase`。
- 已提交 V8 的管理员显示名称为 `人资部门`、规范化账号为 `hr_6298bc833379`、角色为 `ADMIN`。
- 管理员密码明文不得写入 SQL、Java、测试、YAML、Markdown、日志或提交信息；已提交的两套 V8 和静态测试保持原有、已离线验证的 BCrypt cost 12 哈希不变。
- `application-dev.yml` 只迁移旧开发配置中的 MySQL 连接值和三项安全密钥，不迁移旧 `bootstrap-admin` 明文账号密码块。
- 公共配置保留空的 `PA_BOOTSTRAP_ADMIN_*` 环境变量回退以兼容旧库，但标准 V8 数据库不会触发二次管理员创建。
- 不连接用户 MySQL，不运行 Testcontainers，不连接 KingbaseES，不拉取数据库镜像；只运行无数据库测试、静态契约、启动器预检和打包。
- 当前服务工作区已有相关的 README、旧计划和 `config` 重命名修改；执行时吸收这些改动，不重置其他用户文件。

---

### Task 1: 拆分公共、dev 与 prod 配置并删除外置 config

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/MySqlTimestampMappingIntegrationTest.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/resources/application-dev.yml`
- Modify: `src/main/resources/application-prod.yml`
- Delete: `config/application-local.yml`
- Delete: `config/application-prod.yml`
- Delete through tracked history: `config/application-prod.env.example`
- Modify outside service Git: `../启动本地测试.command`
- Verify without modification: `../personnel-archive-desktop-b/tests/unit/root-launcher-promotion.test.ts`

**Interfaces:**
- Consumes: `config/application-local.yml` 中现有 MySQL 连接值和三项本机安全密钥。
- Produces: 公共 `application.yml`、MySQL `application-dev.yml`、Kingbase `application-prod.yml`，以及用 `dev` 启动后端的根启动器。

- [ ] **Step 1: 扩展 profile 配置失败测试**

在 `ProductionProfileConfigurationTest` 中把旧开发路径与 profile 改为 `application-dev.yml`/`dev`，并加入以下契约：

```java
@Test
void separatesCommonDevAndProductionDatabaseSettings() throws IOException {
    assertThat(resolvedProperty("spring.profiles.default", Map.of(),
        "src/main/resources/application.yml")).isEqualTo("dev");
    assertThat(optionalProperty("spring.datasource.url", Map.of(),
        "src/main/resources/application.yml")).isEmpty();
    assertThat(optionalProperty("spring.flyway.locations", Map.of(),
        "src/main/resources/application.yml")).isEmpty();

    assertThat(resolvedProperty("spring.datasource.url", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-dev.yml"))
        .startsWith("jdbc:mysql://127.0.0.1:3306/personnel_archive");
    assertThat(resolvedProperty("spring.datasource.driver-class-name", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-dev.yml"))
        .isEqualTo("com.mysql.cj.jdbc.Driver");
    assertThat(resolvedProperty("spring.flyway.locations", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-dev.yml"))
        .isEqualTo("classpath:db/migration");
    assertThat(resolvedProperty("personnel-archive.bootstrap-admin.password", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-dev.yml"))
        .isEmpty();

    assertThat(resolvedProperty("spring.datasource.url", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEqualTo("jdbc:kingbase8://10.141.29.96:54321/personnel_archive");
    assertThat(resolvedProperty("spring.flyway.locations", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEqualTo("classpath:db/kingbase");
    assertThat(Files.exists(Path.of("config"))).isFalse();
}
```

增加可选属性读取助手，并把现有 YAML 加载逻辑提取为一个返回 `MockEnvironment` 的 `environment(...)`，让必填与可选断言复用相同解析过程：

```java
private static Optional<String> optionalProperty(
    String property, Map<String, String> overrides, String... configPaths
) throws IOException {
    return Optional.ofNullable(environment(overrides, configPaths).getProperty(property));
}
```

保留端口和生产环境变量覆盖测试。

- [ ] **Step 2: 运行配置测试并确认 RED**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=ProductionProfileConfigurationTest test
```

Expected: 因 `application-dev.yml` 尚不存在、公共配置仍含数据库配置或 `config/` 仍存在而失败；不得尝试连接数据库。

- [ ] **Step 3: 创建 application-dev.yml 并收口公共配置**

使用 `apply_patch` 创建 `src/main/resources/application-dev.yml`：

```yaml
spring:
  config:
    activate:
      on-profile: dev
  datasource:
    # url、username、password 使用被删除开发配置中的现有值
    driver-class-name: com.mysql.cj.jdbc.Driver
  flyway:
    locations: classpath:db/migration
```

只迁移旧文件中 `personnel-archive.security` 的三项本机密钥；不迁移 `bootstrap-admin` 块，不复制重复的 `server.port`。

把 `application.yml` 的数据库 URL、账号、密码、驱动和 Flyway location 删除，加入默认 profile，并保留公共 Flyway 开关：

```yaml
spring:
  application:
    name: personnel-archive-service
  profiles:
    default: dev
  flyway:
    enabled: true
```

上传限制、Jackson、MyBatis、HTTP 端口、安全期限、Excel、日志和空的管理员环境变量回退保持公共配置。

在 `application-prod.yml` 的 `spring` 下加入：

```yaml
spring:
  config:
    activate:
      on-profile: prod
```

Kingbase 应用 URL/驱动、Flyway PostgreSQL URL/驱动、生产环境变量和迁移 location 保持不变。

- [ ] **Step 4: 删除外置 config 并更新根启动器**

使用 `apply_patch` 删除 `config/application-local.yml` 和当前已暂存重命名得到的 `config/application-prod.yml`，再用 `git add -A config` 收敛为整个目录删除。

在 `../启动本地测试.command` 只改两处：

```zsh
[[ -f "$SERVICE_DIR/src/main/resources/application-dev.yml" ]] || \
  fail "缺少后端开发配置：$SERVICE_DIR/src/main/resources/application-dev.yml"
```

```zsh
nohup ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev >> "$SERVICE_LOG" 2>&1 &
```

MySQL 容器检查、端口、日志过滤和 Electron 启动逻辑不变。

- [ ] **Step 5: 更新 MySQL 时区集成测试的配置来源**

把 `MySqlTimestampMappingIntegrationTest` 的参数改为：

```java
@ValueSource(strings = "src/main/resources/application-dev.yml")
```

测试源码仍从开发 URL 读取 `connectionTimeZone=Asia/Shanghai` 参数；本轮不运行该 Testcontainers 测试。

- [ ] **Step 6: 运行无数据库 GREEN 验证**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=ProductionProfileConfigurationTest,DatabaseDriverAvailabilityTest test
/bin/zsh ../启动本地测试.command --check
(cd ../personnel-archive-desktop-b && \
  pnpm vitest run tests/unit/root-launcher-promotion.test.ts)
```

Expected: 配置测试、启动器预检和前端启动器契约通过；不启动数据库、后端或 Electron。

- [ ] **Step 7: 提交服务配置变更**

Run:

```bash
git diff --check
git add src/main/resources/application.yml \
  src/main/resources/application-dev.yml \
  src/main/resources/application-prod.yml \
  src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java \
  src/test/java/com/company/personnelarchive/configuration/MySqlTimestampMappingIntegrationTest.java
git add -A config
git commit -m "refactor(service): split dev and prod profiles"
```

根启动器属于非 Git 根目录，保留本地修改并在最终交付中单独列出。

---

### Task 2: 补齐已提交默认管理员 V8 的 MySQL 集成断言

**Files:**
- Verify unchanged: `src/main/resources/db/migration/V8__seed_default_admin.sql`
- Verify unchanged: `src/main/resources/db/kingbase/V8__seed_default_admin.sql`
- Verify unchanged: `src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: 已提交 V8 和 `DefaultAdminMigrationContractTest.DEFAULT_PASSWORD_HASH`。
- Produces: 隔离 MySQL 测试源码中的 Flyway V1–V8、默认管理员状态和唯一 `ADMIN` 关联断言；本轮只编译，不启动 Testcontainers。

- [ ] **Step 1: 验证已提交 V8 的静态契约基线**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=DefaultAdminMigrationContractTest,KingbaseMigrationContractTest test
```

Expected: 静态契约通过，不连接数据库；两份 V8 和已有哈希保持不变。

- [ ] **Step 2: 扩展 MySQL 迁移集成测试源码**

在 `MySqlMigrationIntegrationTest` 的 `Flyway.migrate()` 和 `JdbcTemplate` 初始化之后、插入 `test_admin` 之前加入：

```java
assertThat(jdbc.queryForObject(
    "SELECT version FROM flyway_schema_history "
        + "WHERE success=1 ORDER BY installed_rank DESC LIMIT 1",
    String.class)).isEqualTo("8");
var defaultAdmin = jdbc.queryForMap("""
    SELECT username,normalized_username,password_hash,display_name,
           failed_login_count,locked_until
    FROM sys_user
    WHERE tenant_id=1 AND normalized_username='hr_6298bc833379'
    """);
assertThat(defaultAdmin)
    .containsEntry("username", "hr_6298bc833379")
    .containsEntry("normalized_username", "hr_6298bc833379")
    .containsEntry("password_hash", DefaultAdminMigrationContractTest.DEFAULT_PASSWORD_HASH)
    .containsEntry("display_name", "人资部门")
    .containsEntry("failed_login_count", 0)
    .containsEntry("locked_until", null);
assertThat(jdbc.queryForObject(
    "SELECT COUNT(*) FROM sys_user WHERE tenant_id=1 "
        + "AND normalized_username='hr_6298bc833379' AND is_enabled=1",
    Integer.class)).isEqualTo(1);
assertThat(jdbc.queryForObject("""
    SELECT COUNT(*)
    FROM sys_user_role ur
    JOIN sys_user u ON u.id=ur.user_id AND u.tenant_id=ur.tenant_id
    JOIN sys_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id
    WHERE u.normalized_username='hr_6298bc833379' AND r.role_code='ADMIN'
    """, Integer.class)).isEqualTo(1);
```

不得在测试中加入明文密码；本轮不运行 Testcontainers。

- [ ] **Step 3: 运行无数据库契约与编译验证**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=DefaultAdminMigrationContractTest,KingbaseMigrationContractTest,MapperSqlPortabilityTest test
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -DskipTests package
```

Expected: 静态测试通过；打包成功并编译 MySQL 集成测试源码，但不启动数据库容器。

- [ ] **Step 4: 提交集成测试**

Run:

```bash
git diff --check
git add src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java
git commit --only \
  src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java \
  -m "test(service): verify seeded administrator migration"
```

---

### Task 3: 同步默认数据、profile 和启动说明

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify outside service Git: `../AGENTS.md`
- Modify outside service Git: `../CODE_GUIDE.md`
- Modify: `docs/superpowers/specs/2026-07-30-mysql-kingbase-dual-database-design.md`
- Modify: `docs/superpowers/plans/2026-07-30-mysql-kingbase-dual-database-implementation.md`
- Modify: `docs/superpowers/plans/2026-07-29-unified-backend-port-implementation.md`
- Modify: `docs/superpowers/plans/2026-07-24-jdbc-timezone-implementation.md`
- Modify: `docs/superpowers/plans/2026-07-29-label-anchored-workbook-import-implementation.md`
- Modify in frontend Git: `../personnel-archive-desktop-b/AGENTS.md`

**Interfaces:**
- Consumes: Tasks 1–2 的最终配置路径、profile 名称和已有 V8 默认数据。
- Produces: 当前知识库不再把外置 `config/application-*` 或后端 `local` profile 描述为有效运行方式。

- [ ] **Step 1: 更新服务 README 与 AGENTS**

在 README 明确：

- `application.yml` 是公共配置且默认 `dev`；
- `application-dev.yml` 使用现有 MySQL；
- `application-prod.yml` 使用 KingbaseES；
- Flyway 默认数据包含默认租户、三个角色，以及账号 `hr_6298bc833379`/显示名称“人资部门”的 `ADMIN`；
- 密码由项目负责人单独保管，仓库只含 BCrypt 哈希；
- 不再要求标准首次启动提供 `PA_BOOTSTRAP_ADMIN_*`，但旧引导代码暂时保留兼容；
- 生产部署必须注入数据库和三项安全密钥，不再提供 `config/` 示例文件。

在服务 AGENTS 将 MySQL 迁移更新为 V1–V8、Kingbase 更新为 V7–V8，并把后端开发 profile 的当前说明改为 `dev`。

- [ ] **Step 2: 更新根知识库和指定历史文档**

在根 `AGENTS.md`/`CODE_GUIDE.md` 同步：

- 根启动器使用 `dev` profile；
- 公共/dev/prod 三个 classpath 文件的职责；
- 外置 `config/` 已删除；
- V8 默认管理员及无明文边界；
- MySQL/Kingbase 迁移版本范围。

只在以下历史文档中把现存旧路径/profile 改为当前事实，不改写其历史任务结论：

```text
docs/superpowers/specs/2026-07-30-mysql-kingbase-dual-database-design.md
docs/superpowers/plans/2026-07-30-mysql-kingbase-dual-database-implementation.md
docs/superpowers/plans/2026-07-29-unified-backend-port-implementation.md
docs/superpowers/plans/2026-07-24-jdbc-timezone-implementation.md
docs/superpowers/plans/2026-07-29-label-anchored-workbook-import-implementation.md
```

- [ ] **Step 3: 更新前端知识库并验证指定当前文档**

将 `../personnel-archive-desktop-b/AGENTS.md` 的根启动器说明从后端旧开发 profile 改为 `dev`；前端 `.env.local` 保持不变。

Run:

```bash
rg -n 'config/application-(local|prod)|spring-boot.run.profiles=local|on-profile: local' \
  README.md AGENTS.md src ../AGENTS.md ../CODE_GUIDE.md ../启动本地测试.command \
  docs/superpowers/specs/2026-07-30-mysql-kingbase-dual-database-design.md \
  docs/superpowers/plans/2026-07-30-mysql-kingbase-dual-database-implementation.md \
  docs/superpowers/plans/2026-07-29-unified-backend-port-implementation.md \
  docs/superpowers/plans/2026-07-24-jdbc-timezone-implementation.md \
  docs/superpowers/plans/2026-07-29-label-anchored-workbook-import-implementation.md \
  ../personnel-archive-desktop-b/AGENTS.md
```

Expected: 无匹配。

- [ ] **Step 4: 提交两个子仓库文档**

服务仓库：

```bash
git diff --check
git add README.md AGENTS.md \
  docs/superpowers/specs/2026-07-30-mysql-kingbase-dual-database-design.md \
  docs/superpowers/plans/2026-07-30-mysql-kingbase-dual-database-implementation.md \
  docs/superpowers/plans/2026-07-29-unified-backend-port-implementation.md \
  docs/superpowers/plans/2026-07-24-jdbc-timezone-implementation.md \
  docs/superpowers/plans/2026-07-29-label-anchored-workbook-import-implementation.md
git commit -m "docs(service): document dev prod and default admin"
```

前端仓库：

```bash
git -C ../personnel-archive-desktop-b add AGENTS.md
git -C ../personnel-archive-desktop-b commit -m "docs(desktop): align backend dev profile"
```

根 `AGENTS.md`、`CODE_GUIDE.md` 和 `启动本地测试.command` 不属于 Git 仓库，保留本地修改并单独报告。

---

### Task 4: 最终无数据库验证与交付审计

**Files:**
- Verify only: all changed service files
- Verify only: `../启动本地测试.command`
- Verify only: `../AGENTS.md`
- Verify only: `../CODE_GUIDE.md`
- Verify only: `../personnel-archive-desktop-b/AGENTS.md`

**Interfaces:**
- Consumes: Tasks 1–3 的完整结果。
- Produces: 无数据库连接的最新测试/打包证据，以及明确的真实数据库验收边界。

- [ ] **Step 1: 运行全部相关无数据库测试**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=CorsConfigurationTest,ApiContractSerializationTest,ProductionProfileConfigurationTest,DatabaseDriverAvailabilityTest,DefaultAdminMigrationContractTest,KingbaseMigrationContractTest,MapperSqlPortabilityTest,AdminBootstrapTest,LoginFailureRecorderTest,AuthServiceSessionPersistenceTest,PersonQueryServiceTest,ImportItemServiceTest,ImportHistoryServiceTest test
```

Expected: 0 failures/errors，日志中没有数据库连接。

- [ ] **Step 2: 验证启动器和前端契约**

Run:

```bash
/bin/zsh ../启动本地测试.command --check
(cd ../personnel-archive-desktop-b && \
  pnpm vitest run tests/unit/root-launcher-promotion.test.ts)
```

Expected: 两条命令成功，不启动 MySQL、后端或 Electron。

- [ ] **Step 3: 打包并检查 JAR 内容**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -DskipTests package
jar tf target/personnel-archive-service-0.1.0-SNAPSHOT.jar | \
  rg 'application-(dev|prod).yml|db/(migration|kingbase)/V8__seed_default_admin.sql'
```

Expected: `BUILD SUCCESS`；JAR 包含两个环境文件和两份 V8，不包含 `BOOT-INF/classes/config/`。

- [ ] **Step 4: 执行最终源码与 Git 审计**

Run:

```bash
test ! -d config
rg -n 'config/application-(local|prod)|spring-boot.run.profiles=local|on-profile: local' \
  README.md AGENTS.md src ../AGENTS.md ../CODE_GUIDE.md ../启动本地测试.command \
  docs/superpowers/specs/2026-07-30-mysql-kingbase-dual-database-design.md \
  docs/superpowers/plans/2026-07-30-mysql-kingbase-dual-database-implementation.md \
  docs/superpowers/plans/2026-07-29-unified-backend-port-implementation.md \
  docs/superpowers/plans/2026-07-24-jdbc-timezone-implementation.md \
  docs/superpowers/plans/2026-07-29-label-anchored-workbook-import-implementation.md \
  ../personnel-archive-desktop-b/AGENTS.md
git diff --check
git status --short
git -C ../personnel-archive-desktop-b diff --check
git -C ../personnel-archive-desktop-b status --short
```

Expected: 旧配置/旧 profile 搜索无匹配；服务与前端仓库无未提交文件。根目录三个文件因父目录不是 Git 仓库而单独列出。

- [ ] **Step 5: 记录未执行边界**

交付说明必须明确：未连接用户 MySQL、未启动 Testcontainers、未连接 KingbaseES，因此不声称真实数据库迁移或登录已经验证。用户自行测试时分别确认 MySQL V8/Kingbase V8 执行、指定账号登录、显示名称、ADMIN 权限、同名账号重置、角色不重复，以及导入/查询/导出主链路。
