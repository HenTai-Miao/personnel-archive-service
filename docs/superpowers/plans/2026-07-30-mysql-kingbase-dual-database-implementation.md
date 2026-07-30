# MySQL 与 KingbaseES 双数据库适配实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保持默认及 `local` 环境使用现有 MySQL，同时让 `prod` 使用 KingbaseES V9、自动建立等价 V7 结构，并让共享 MyBatis SQL 在两种数据库上保持一致语义。

**Architecture:** 应用继续使用现有 MyBatis Mapper。数据库差异被限制在 profile 数据源配置、分离的 Flyway 迁移位置和极少量共享 SQL 可移植化修改中；业务服务、事务边界、API 与前端协议不变。KingbaseES 应用连接使用官方 `kingbase8:9.0.1` 驱动，Flyway 按 KingbaseES 官方方式通过 PostgreSQL 协议执行 Kingbase 专用 V7 基线。

**Tech Stack:** Java 21、Spring Boot 4.0.7、MyBatis Spring Boot Starter 4.0.1、Flyway 11.14.1、MySQL Connector/J、KingbaseES JDBC 9.0.1、JUnit 5、AssertJ、Mockito。

## Global Constraints

- 不迁移到 MyBatis-Plus。
- `local` 与默认开发环境继续使用 MySQL；不得修改、创建、重置或删除用户现有 `local-mysql` 数据卷。
- `prod` 默认应用 URL 为 `jdbc:kingbase8://10.141.29.96:54321/personnel_archive`，HTTP 端口仍为 `18080`。
- 仓库不得保存生产数据库密码、令牌 pepper、HMAC key、AES key 或管理员密码。
- 本次只为新 KingbaseES 空库创建最终 V7 结构，不迁移现有 MySQL 数据。
- 不连接本机 KingbaseES，不运行需要 KingbaseES 实例的测试，也不把自动化测试指向用户数据库。
- MySQL 已应用的 V1 至 V7 迁移内容和校验和保持不变。
- 业务日期和家庭年龄继续按源文本存储；数据库适配不得引入日期或年龄重算。
- 每个任务结束时运行该任务的无数据库验证并提交；最终不能声称 KingbaseES 端到端运行已验证。

---

### Task 1: 增加 KingbaseES 依赖与生产 profile

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application-prod.yml`
- Modify: `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java`
- Create: `src/test/java/com/company/personnelarchive/configuration/DatabaseDriverAvailabilityTest.java`

**Interfaces:**
- Consumes: 现有 `application.yml` MySQL 默认数据源和 `PA_SERVER_PORT` 环境覆盖。
- Produces: `prod` 的 Kingbase 应用数据源、独立 Flyway 数据源、Kingbase 迁移位置及两种驱动的离线可加载契约。

- [ ] **Step 1: 扩展生产配置回归测试**

在 `ProductionProfileConfigurationTest` 中保留端口测试，并增加以下断言。测试加载 `application.yml` 后再加载 `application-prod.yml`，通过 `MockEnvironment#getRequiredProperty` 解析配置：

```java
@Test
void usesKingbaseForProductionAndKeepsMysqlForLocal() throws IOException {
    assertThat(resolvedProperty("spring.datasource.url",
        "src/main/resources/application.yml",
        "config/application-local.yml"))
        .startsWith("jdbc:mysql://127.0.0.1:3306/personnel_archive");
    assertThat(resolvedProperty("spring.datasource.url",
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEqualTo("jdbc:kingbase8://10.141.29.96:54321/personnel_archive");
    assertThat(resolvedProperty("spring.datasource.driver-class-name",
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEqualTo("com.kingbase8.Driver");
    assertThat(resolvedProperty("spring.flyway.url",
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEqualTo("jdbc:postgresql://10.141.29.96:54321/personnel_archive");
    assertThat(resolvedProperty("spring.flyway.locations",
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEqualTo("classpath:db/kingbase");
}
```

增加环境覆盖测试，设置 `PA_DB_URL`、`PA_FLYWAY_DB_URL`、`PA_DB_USERNAME` 和 `PA_DB_PASSWORD` 后断言四项覆盖都生效。辅助方法不得打印密码。

- [ ] **Step 2: 添加驱动离线加载测试**

创建 `DatabaseDriverAvailabilityTest`，只实例化驱动并检查 URL，不建立连接：

```java
class DatabaseDriverAvailabilityTest {
    @Test
    void loadsApplicationAndFlywayDriversWithoutConnecting() throws Exception {
        assertThat(new com.kingbase8.Driver()
            .acceptsURL("jdbc:kingbase8://10.141.29.96:54321/personnel_archive"))
            .isTrue();
        assertThat(new org.postgresql.Driver()
            .acceptsURL("jdbc:postgresql://10.141.29.96:54321/personnel_archive"))
            .isTrue();
    }
}
```

- [ ] **Step 3: 运行测试并确认 RED**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=ProductionProfileConfigurationTest,DatabaseDriverAvailabilityTest test
```

Expected: 配置断言失败，并因 Kingbase/PostgreSQL 驱动依赖尚未加入而编译失败；失败原因必须来自待实现能力。

- [ ] **Step 4: 增加运行时依赖**

在 `pom.xml` 保留 MySQL 与 `flyway-mysql`，增加：

```xml
<dependency>
    <groupId>cn.com.kingbase</groupId>
    <artifactId>kingbase8</artifactId>
    <version>9.0.1</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

不要加入 `mybatis-plus` 或与 Flyway 11.14.1 不匹配的第三方 Kingbase Flyway SPI。

- [ ] **Step 5: 实现 `prod` 数据源覆盖**

将 `application-prod.yml` 扩展为：

```yaml
server:
  port: ${PA_SERVER_PORT:18080}

spring:
  datasource:
    url: ${PA_DB_URL:jdbc:kingbase8://10.141.29.96:54321/personnel_archive}
    username: ${PA_DB_USERNAME:personnel_archive}
    password: ${PA_DB_PASSWORD:}
    driver-class-name: com.kingbase8.Driver
  flyway:
    url: ${PA_FLYWAY_DB_URL:jdbc:postgresql://10.141.29.96:54321/personnel_archive}
    user: ${PA_DB_USERNAME:personnel_archive}
    password: ${PA_DB_PASSWORD:}
    locations: classpath:db/kingbase
```

- [ ] **Step 6: 运行测试并确认 GREEN**

重复 Step 3 命令。Expected: 两个测试类全部通过，过程不出现数据库连接尝试。

- [ ] **Step 7: 检查依赖解析并提交**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw dependency:tree -Dincludes=cn.com.kingbase,org.postgresql,org.flywaydb
git diff --check
git add pom.xml src/main/resources/application-prod.yml \
  src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java \
  src/test/java/com/company/personnelarchive/configuration/DatabaseDriverAvailabilityTest.java
git commit -m "feat(service): configure Kingbase production datasource"
```

Expected: 依赖树同时包含 `kingbase8:9.0.1`、MySQL、Flyway MySQL、Flyway PostgreSQL 和 PostgreSQL JDBC。

---

### Task 2: 将共享 MyBatis SQL 改为双数据库可移植写法

**Files:**
- Modify: `src/main/java/com/company/personnelarchive/auth/AuthMapper.java`
- Modify: `src/main/java/com/company/personnelarchive/auth/AuthService.java`
- Modify: `src/main/java/com/company/personnelarchive/auth/LoginFailureRecorder.java`
- Modify: `src/main/java/com/company/personnelarchive/person/PersonReadMapper.java`
- Modify: `src/main/java/com/company/personnelarchive/person/PersonMapper.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ImportMapper.java`
- Create: `src/test/java/com/company/personnelarchive/auth/AuthServiceSessionPersistenceTest.java`
- Modify: `src/test/java/com/company/personnelarchive/auth/LoginFailureRecorderTest.java`
- Modify: `src/test/java/com/company/personnelarchive/person/PersonQueryServiceTest.java`
- Create: `src/test/java/com/company/personnelarchive/configuration/MapperSqlPortabilityTest.java`

**Interfaces:**
- Consumes: 现有认证时钟、Mapper generated-keys 支持、列表/家庭查询参数和更新时间字段。
- Produces: 不含 MySQL 专用函数的共享 Mapper SQL；会话主键、账号锁定和模糊搜索语义保持不变。

- [ ] **Step 1: 写会话主键回填测试**

创建 `AuthServiceSessionPersistenceTest`。使用固定 `Clock`、Mockito 和可预测的 `@Answer`：当 `insertSession(SessionInsert row)` 被调用时设置 `row.id = 42L`，然后断言 `AccessTokenRegistry.issue` 收到 session id `42`。测试必须通过真实 `AuthService.login` 或 `refresh` 业务入口触发，不能直接测试私有方法。

核心断言：

```java
doAnswer(invocation -> {
    AuthMapper.SessionInsert row = invocation.getArgument(0);
    row.id = 42L;
    return 1;
}).when(mapper).insertSession(any(AuthMapper.SessionInsert.class));

verify(accessTokens).issue(any(AuthPrincipal.class), eq(42L));
```

- [ ] **Step 2: 写锁定时限与 LIKE 转义测试**

把 `LoginFailureRecorderTest` 改为传入固定时钟：

```java
Instant now = Instant.parse("2026-07-30T01:00:00Z");
Clock clock = Clock.fixed(now, ZoneOffset.UTC);
new LoginFailureRecorder(mapper, audit, clock).record(user);
verify(mapper).recordFailedLogin(7, now.plus(Duration.ofMinutes(15)));
```

在 `PersonQueryServiceTest` 增加列表筛选用例，输入 `A!%_`，捕获 Mapper 参数并断言转义结果是 `A!!!%!_`。

- [ ] **Step 3: 写 Mapper SQL 可移植性测试**

创建 `MapperSqlPortabilityTest`，通过反射读取 Mapper 方法上的 `@Select`、`@Insert`、`@Update` 文本并断言：

```java
assertThat(allMapperSql())
    .doesNotContain("LAST_INSERT_ID(")
    .doesNotContain("DATE_ADD(")
    .doesNotContain("ORDER BY FIELD(")
    .doesNotContain("ESCAPE '\\\\'");
assertThat(allMapperSql()).contains("ESCAPE '!'");
assertThat(allMapperSql()).contains("ORDER BY CASE education_type");
```

测试扫描的 Mapper 固定为 `AuthMapper`、`PersonMapper`、`PersonReadMapper`、`ImportMapper` 和 `AuditMapper`，避免扫描整个 classpath。

- [ ] **Step 4: 运行测试并确认 RED**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=AuthServiceSessionPersistenceTest,LoginFailureRecorderTest,PersonQueryServiceTest,MapperSqlPortabilityTest test
```

Expected: 新签名/类尚不存在或 SQL 仍含 MySQL 专用函数，测试失败。

- [ ] **Step 5: 用 generated keys 保存会话主键**

在 `AuthMapper` 增加：

```java
@Insert("INSERT INTO sys_user_session (...) VALUES (...)")
@Options(useGeneratedKeys = true, keyProperty = "id")
int insertSession(SessionInsert row);

class SessionInsert {
    public Long id;
    public long tenantId;
    public long userId;
    public byte[] hash;
    public Instant accessExpiresAt;
    public Instant refreshExpiresAt;
}
```

删除 `lastInsertId()`。`AuthService.issuePair` 构造 `SessionInsert`、调用插入、检查 `row.id != null`，再把该 id 传给 `accessTokens.issue`；主键未回填时抛出 `IllegalStateException`，不得使用猜测值。

- [ ] **Step 6: 把失败锁定时限移到 Java 时钟**

`LoginFailureRecorder` 注入 `Clock`，调用：

```java
mapper.recordFailedLogin(user.id(), clock.instant().plus(Duration.ofMinutes(15)));
```

`AuthMapper` SQL 改为：

```sql
UPDATE sys_user
SET failed_login_count=failed_login_count+1,
    locked_until=CASE WHEN failed_login_count+1>=5 THEN #{lockedUntil} ELSE locked_until END,
    updated_at=CURRENT_TIMESTAMP(6)
WHERE id=#{userId}
```

- [ ] **Step 7: 替换排序与 LIKE 转义**

教育排序改为：

```sql
ORDER BY CASE education_type
    WHEN 'FULL_TIME' THEN 1
    WHEN 'IN_SERVICE' THEN 2
    WHEN 'MAIN' THEN 3
    ELSE 4
END, sort_order
```

`PersonQueryService.escapeLike` 改为：

```java
private static String escapeLike(String input) {
    return input.replace("!", "!!").replace("%", "!%").replace("_", "!_");
}
```

所有相关查询统一使用 `ESCAPE '!'`。

- [ ] **Step 8: 显式维护更新时间**

在这些 UPDATE 中加入 `updated_at=CURRENT_TIMESTAMP(6)`：

- `PersonMapper.softDeleteForOverwrite`
- `PersonMapper.softDeleteActive`
- `PersonMapper.linkReplacement`
- `ImportMapper.refreshBatch`
- `ImportMapper.completeDecision`

不得改动审计内容、软删除原因或事务边界。

- [ ] **Step 9: 运行测试并确认 GREEN**

重复 Step 4 命令。Expected: 全部通过。

- [ ] **Step 10: 运行非数据库 Mapper 相关回归并提交**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=AuthServiceSessionPersistenceTest,LoginFailureRecorderTest,AdminBootstrapTest,PersonQueryServiceTest,PersonDeletionServiceTest,ImportItemServiceTest,ImportHistoryServiceTest,MapperSqlPortabilityTest test
git diff --check
git add src/main/java src/test/java
git commit -m "refactor(service): make mapper SQL database portable"
```

---

### Task 3: 增加 KingbaseES V7 最终结构基线

**Files:**
- Create: `src/main/resources/db/kingbase/V7__kingbase_initial_schema.sql`
- Create: `src/test/java/com/company/personnelarchive/configuration/KingbaseMigrationContractTest.java`

**Interfaces:**
- Consumes: MySQL V1 至 V7 执行后的最终数据模型和 `prod` 的 `classpath:db/kingbase` 位置。
- Produces: 新 KingbaseES 空库可执行的 V7 最终结构，以及无需数据库连接的结构契约检查。

- [ ] **Step 1: 写 Kingbase 迁移静态契约测试**

创建 `KingbaseMigrationContractTest`，读取 UTF-8 SQL 并校验 15 张表：

```java
private static final List<String> TABLES = List.of(
    "sys_tenant", "sys_user", "sys_role", "sys_user_role", "sys_user_session",
    "pa_person", "pa_employment", "pa_education", "pa_work_experience",
    "pa_reward_punishment", "pa_family_member", "pa_person_photo",
    "pa_import_batch", "pa_import_item", "sys_operation_log");

@Test
void definesTheFinalV7SchemaWithoutMysqlStorageSyntax() throws IOException {
    String sql = Files.readString(Path.of(
        "src/main/resources/db/kingbase/V7__kingbase_initial_schema.sql"));
    TABLES.forEach(table -> assertThat(sql).contains("CREATE TABLE " + table));
    assertThat(sql)
        .contains("birth_date VARCHAR(64)")
        .contains("age_text TEXT")
        .contains("photo_data BYTEA")
        .contains("delete_reason TEXT")
        .contains("WHERE is_deleted = 0")
        .doesNotContain("ENGINE=InnoDB")
        .doesNotContain("CHARSET=")
        .doesNotContain("COLLATE=utf8mb4")
        .doesNotContain("AUTO_INCREMENT")
        .doesNotContain("DATETIME(")
        .doesNotContain("MEDIUMBLOB")
        .doesNotContain("LONGTEXT");
}
```

再断言十二个业务日期列均为 `VARCHAR(64)`、家庭成员约束只要求 `sort_order >= 1`、照片锚点约束存在、三个角色种子存在、active employee/HMAC 是带 `WHERE is_deleted = 0` 的唯一索引。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=KingbaseMigrationContractTest test
```

Expected: Kingbase V7 文件不存在，测试失败。

- [ ] **Step 3: 编写最终 V7 DDL**

创建完整 SQL，使用以下确定映射：

```sql
-- 示例主键与时间列；所有表按同一规则展开。
id BIGINT GENERATED BY DEFAULT AS IDENTITY,
created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
```

二进制与状态列：

```sql
id_card_ciphertext BYTEA NULL,
id_card_hmac BYTEA NULL,
is_deleted SMALLINT NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),
photo_data BYTEA NOT NULL,
safe_metadata JSON NULL
```

active 唯一性使用部分索引，不创建重复表示列：

```sql
CREATE UNIQUE INDEX uk_pa_person_active_employee_no
    ON pa_person (tenant_id, employee_no)
    WHERE is_deleted = 0 AND NULLIF(BTRIM(employee_no), '') IS NOT NULL;
CREATE UNIQUE INDEX uk_pa_person_active_id_card_hmac
    ON pa_person (tenant_id, id_card_hmac)
    WHERE is_deleted = 0 AND id_card_hmac IS NOT NULL;
```

DDL 必须完整包含 MySQL 最终结构中的外键、普通索引、检查约束、照片锚点列、导入状态列和审计列。`sys_tenant` 显式插入 id `1` 后将 identity 下一值调整为 `2`；`sys_role` 插入三个角色后将下一值调整为 `4`，避免后续自动主键碰撞。

- [ ] **Step 4: 运行测试并确认 GREEN**

重复 Step 2 命令。Expected: 静态结构契约通过，且没有数据库连接。

- [ ] **Step 5: 对照 MySQL 最终模型并提交**

Run:

```bash
rg -n '^CREATE TABLE|^CREATE (UNIQUE )?INDEX|CONSTRAINT ' \
  src/main/resources/db/kingbase/V7__kingbase_initial_schema.sql
git diff --check
git add src/main/resources/db/kingbase/V7__kingbase_initial_schema.sql \
  src/test/java/com/company/personnelarchive/configuration/KingbaseMigrationContractTest.java
git commit -m "feat(service): add Kingbase V7 schema baseline"
```

逐表对照 MySQL V1 至 V7 的最终字段，任何缺失都必须在提交前补齐。

---

### Task 4: 同步部署说明与项目知识库

**Files:**
- Create: `config/application-prod.env.example`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `../AGENTS.md`
- Modify: `../CODE_GUIDE.md`

**Interfaces:**
- Consumes: 已实现的 profile、双迁移路径和部署前置条件。
- Produces: 无秘密的生产环境变量清单，以及与实际代码一致的跨项目/服务说明。

- [ ] **Step 1: 添加生产环境变量示例**

创建：

```dotenv
SPRING_PROFILES_ACTIVE=prod
PA_SERVER_PORT=18080
PA_DB_URL=jdbc:kingbase8://10.141.29.96:54321/personnel_archive
PA_FLYWAY_DB_URL=jdbc:postgresql://10.141.29.96:54321/personnel_archive
PA_DB_USERNAME=personnel_archive
PA_DB_PASSWORD=
PA_TOKEN_PEPPER=
PA_IDENTITY_HMAC_KEY=
PA_IDENTITY_ENCRYPTION_KEY=
PA_BOOTSTRAP_ADMIN_USERNAME=
PA_BOOTSTRAP_ADMIN_PASSWORD=
PA_BOOTSTRAP_ADMIN_DISPLAY_NAME=初始管理员
```

空值表示部署时必须通过安全注入提供，不是可用默认秘密。

- [ ] **Step 2: 更新 README 与知识库**

明确记录：

- MySQL 与 KingbaseES 都是支持的持久化实现；
- 默认/`local` 用 MySQL，`prod` 用 KingbaseES；
- KingbaseES 数据库和低权限应用账号由 DBA 预创建；
- Flyway 建表但不创建数据库本身，也不搬迁 MySQL 数据；
- Kingbase 应用/Flyway 两条 JDBC URL 的原因；
- 生产部署后的必做空库迁移和业务验收；
- 本地启动脚本仍只操作现有 MySQL。

把 `AGENTS.md` 中“MySQL 是唯一数据库”“每个 schema 变更只测试 MySQL”等过时描述改成双数据库约束。`../CODE_GUIDE.md` 更新配置入口、迁移位置和运行时 SQL 可移植化边界。

- [ ] **Step 3: 检查秘密与旧描述并提交服务仓库内容**

Run:

```bash
rg -n 'MySQL 是唯一|唯一持久化|PA_DB_PASSWORD=.+|password:.+' \
  README.md AGENTS.md config/application-prod.env.example
git diff --check
git add README.md AGENTS.md config/application-prod.env.example
git commit -m "docs(service): document dual database deployment"
```

Expected: 没有真实密码；旧的“唯一 MySQL”描述不存在。根目录 `../AGENTS.md` 和 `../CODE_GUIDE.md` 不属于此 Git 仓库，保留本地修改并在交付说明中单独列出。

---

### Task 5: 最终无数据库验证与交付审计

**Files:**
- Verify only: all changed service files
- Verify only: `../AGENTS.md`
- Verify only: `../CODE_GUIDE.md`

**Interfaces:**
- Consumes: Tasks 1–4 的全部实现。
- Produces: 不连接任何数据库的最新验证证据、干净服务工作区和明确的未验证边界。

- [ ] **Step 1: 运行全部无数据库测试**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=CorsConfigurationTest,ApiContractSerializationTest,ProductionProfileConfigurationTest,DatabaseDriverAvailabilityTest,AdminBootstrapTest,LoginFailureRecorderTest,AuthServiceSessionPersistenceTest,PersonDeletionServiceTest,SnapshotWriterTest,NameNormalizerTest,SensitiveValueMaskerTest,IdentityCardTest,PersonQueryServiceTest,PersonControllerTest,BatchArchiveExportServiceTest,ArchiveExportServiceTest,ImportItemServiceTest,ImportHistoryServiceTest,ExcelTemplateV1Test,MapperSqlPortabilityTest,KingbaseMigrationContractTest test
```

Expected: 所列测试全部通过；日志中无数据库连接尝试。

- [ ] **Step 2: 编译并打包**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -DskipTests package
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 检查最终 JAR 与配置**

Run:

```bash
jar tf target/personnel-archive-service-0.1.0-SNAPSHOT.jar | \
  rg 'kingbase8-9.0.1|postgresql|flyway-database-postgresql|db/kingbase/V7__kingbase_initial_schema.sql'
unzip -p target/personnel-archive-service-0.1.0-SNAPSHOT.jar \
  BOOT-INF/classes/application-prod.yml
```

Expected: Kingbase 与 Flyway PostgreSQL 依赖、V7 基线和正确生产配置都在可执行 JAR 中。

- [ ] **Step 4: 执行最终源码审计**

Run:

```bash
rg -n 'LAST_INSERT_ID\(|DATE_ADD\(|ORDER BY FIELD\(|ESCAPE.*\\\\' src/main/java
rg -n 'MySQL 是唯一|唯一持久化' README.md AGENTS.md ../AGENTS.md ../CODE_GUIDE.md
git diff --check
git status --short
```

Expected: 第一、二个搜索无匹配；Git 工作区无未提交服务文件。根目录两个文档因父目录不是 Git 仓库而单独报告。

- [ ] **Step 5: 记录生产验收边界**

交付说明必须明确：没有 KingbaseES 实例，因此未执行数据库连接、Flyway 实际落库和 Kingbase 业务端到端测试。部署人员应在生产空库依次验证 Flyway V7、管理员引导、登录、单个导入、重复覆盖、家庭查询、软删除、单个 XLSX 导出和批量 ZIP 导出。
