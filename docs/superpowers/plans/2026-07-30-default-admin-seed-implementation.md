# 双数据库默认管理员 V8 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 MySQL 与 KingbaseES 两套 Flyway V8 确定性地创建或重置指定管理员账号，并赋予唯一 `ADMIN` 角色关联，同时保证仓库不保存明文密码。

**Architecture:** 两套数据库分别使用自身的 upsert 语法，但共享相同账号、显示名称和已离线验证的 BCrypt cost 12 哈希。迁移只通过默认租户代码和角色代码解析外键，不依赖自增 ID；静态契约锁定双脚本等价性，隔离的 MySQL Testcontainers 测试验证真实迁移结果。

**Tech Stack:** Java 21、Spring Boot 4.0.7、Spring Security BCrypt、Flyway 11.14.1、MySQL 8.4.3、KingbaseES V9 SQL、JUnit 5、AssertJ、Testcontainers。

## Global Constraints

- 不修改已发布的 MySQL V1–V7 或 KingbaseES V7；只新增 V8。
- 显示名称固定为 `人资部门`，规范化登录账号固定为 `hr_6298bc833379`，权限固定为 `ADMIN`。
- 两套 V8 只保存已离线验证 `matches=true` 的 BCrypt cost 12 哈希 `$2a$12$ZeUKR0IcwyMU1Wye4d6T9Oc/NG3ZRF3a69JSHnnXiPrHuLqxI/nBi`；任何新 SQL、测试、文档、日志和提交信息都不得出现明文密码。
- 同名账号存在时必须重置显示名称、密码哈希、启用状态、失败次数和锁定状态，并更新 `password_changed_at`/`updated_at`。
- 用户与角色外键通过 `tenant_code='DEFAULT'`、`role_code='ADMIN'` 查询，不硬编码用户或角色 ID。
- 默认/`local` 仍使用 MySQL，`prod` 仍使用 KingbaseES；不得连接本地或生产 KingbaseES。
- 不得把自动化测试指向用户的 `local-mysql`。MySQL Testcontainers 只在本机已有 `mysql:8.4.3` 镜像时运行，不允许为了本任务拉取镜像。
- 保留当前工作区已有的 `config/application-prod.env.example -> config/application-prod.yml` 暂存重命名、README 路径修改和旧计划修改，不重置、不覆盖；提交时明确检查并只纳入本任务需要的内容。

---

### Task 1: 用离线契约锁定双 V8 管理员种子

**Files:**
- Create: `src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java`
- Create: `src/main/resources/db/migration/V8__seed_default_admin.sql`
- Create: `src/main/resources/db/kingbase/V8__seed_default_admin.sql`

**Interfaces:**
- Consumes: MySQL `sys_tenant/sys_user/sys_role/sys_user_role` 最终 V7 结构、KingbaseES 同名最终结构，以及两套 profile 的既有 Flyway locations。
- Produces: 包级常量 `DefaultAdminMigrationContractTest.DEFAULT_PASSWORD_HASH`，供真实 MySQL 迁移测试核对数据库哈希；两套 Flyway V8 产生等价默认管理员状态。

- [ ] **Step 1: 写双迁移失败契约测试**

创建以下测试；测试不包含明文密码：

```java
package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAdminMigrationContractTest {
    static final String DEFAULT_PASSWORD_HASH =
        "$2a$12$ZeUKR0IcwyMU1Wye4d6T9Oc/NG3ZRF3a69JSHnnXiPrHuLqxI/nBi";
    private static final Path MYSQL = Path.of(
        "src/main/resources/db/migration/V8__seed_default_admin.sql");
    private static final Path KINGBASE = Path.of(
        "src/main/resources/db/kingbase/V8__seed_default_admin.sql");

    @Test
    void seedsTheSameBcryptAdminInBothDatabases() throws IOException {
        String mysql = Files.readString(MYSQL);
        String kingbase = Files.readString(KINGBASE);

        for (String sql : new String[]{mysql, kingbase}) {
            assertThat(sql)
                .contains("'hr_6298bc833379'")
                .contains("'人资部门'")
                .contains("tenant_code = 'DEFAULT'")
                .contains("role_code = 'ADMIN'")
                .contains("'" + DEFAULT_PASSWORD_HASH + "'")
                .contains("failed_login_count = 0")
                .contains("locked_until = NULL")
                .contains("password_changed_at = CURRENT_TIMESTAMP(6)")
                .contains("updated_at = CURRENT_TIMESTAMP(6)");
        }
        assertThat(DEFAULT_PASSWORD_HASH)
            .startsWith("$2a$12$")
            .hasSize(60);
    }

    @Test
    void usesDatabaseSpecificIdempotentUpserts() throws IOException {
        assertThat(Files.readString(MYSQL))
            .contains("ON DUPLICATE KEY UPDATE")
            .contains("role_id = VALUES(role_id)");
        assertThat(Files.readString(KINGBASE))
            .contains("ON CONFLICT (tenant_id, normalized_username)")
            .contains("ON CONFLICT (tenant_id, user_id, role_id) DO NOTHING");
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=DefaultAdminMigrationContractTest test
```

Expected: 两个 V8 文件不存在，测试以 `NoSuchFileException` 失败；失败原因只来自待实现迁移。

- [ ] **Step 3: 新增 MySQL V8**

创建 `src/main/resources/db/migration/V8__seed_default_admin.sql`：

```sql
-- Seed the explicitly approved initial administrator without retaining its plaintext password.
INSERT INTO sys_user
    (tenant_id, username, normalized_username, password_hash, display_name,
     is_enabled, failed_login_count, locked_until)
VALUES
    ((SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT'),
     'hr_6298bc833379',
     'hr_6298bc833379',
     '$2a$12$ZeUKR0IcwyMU1Wye4d6T9Oc/NG3ZRF3a69JSHnnXiPrHuLqxI/nBi',
     '人资部门',
     1,
     0,
     NULL)
ON DUPLICATE KEY UPDATE
    username = VALUES(username),
    password_hash = VALUES(password_hash),
    display_name = VALUES(display_name),
    is_enabled = 1,
    failed_login_count = 0,
    locked_until = NULL,
    password_changed_at = CURRENT_TIMESTAMP(6),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO sys_user_role (tenant_id, user_id, role_id)
VALUES (
    (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT'),
    (SELECT id FROM sys_user
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND normalized_username = 'hr_6298bc833379'),
    (SELECT id FROM sys_role
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND role_code = 'ADMIN')
)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
```

子查询缺失时应触发非空/外键约束失败，不能通过硬编码 ID 掩盖种子结构异常。

- [ ] **Step 4: 新增 KingbaseES V8**

创建 `src/main/resources/db/kingbase/V8__seed_default_admin.sql`：

```sql
-- Seed the explicitly approved initial administrator without retaining its plaintext password.
INSERT INTO sys_user
    (tenant_id, username, normalized_username, password_hash, display_name,
     is_enabled, failed_login_count, locked_until)
VALUES
    ((SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT'),
     'hr_6298bc833379',
     'hr_6298bc833379',
     '$2a$12$ZeUKR0IcwyMU1Wye4d6T9Oc/NG3ZRF3a69JSHnnXiPrHuLqxI/nBi',
     '人资部门',
     1,
     0,
     NULL)
ON CONFLICT (tenant_id, normalized_username) DO UPDATE SET
    username = EXCLUDED.username,
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    is_enabled = 1,
    failed_login_count = 0,
    locked_until = NULL,
    password_changed_at = CURRENT_TIMESTAMP(6),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO sys_user_role (tenant_id, user_id, role_id)
VALUES (
    (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT'),
    (SELECT id FROM sys_user
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND normalized_username = 'hr_6298bc833379'),
    (SELECT id FROM sys_role
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND role_code = 'ADMIN')
)
ON CONFLICT (tenant_id, user_id, role_id) DO NOTHING;
```

- [ ] **Step 5: 运行测试并确认 GREEN**

重复 Step 2 命令。Expected: 2 个契约测试全部通过，过程不连接数据库。

- [ ] **Step 6: 检查迁移内容并提交**

Run:

```bash
rg -n "hr_6298bc833379|人资部门|ADMIN|ON DUPLICATE|ON CONFLICT|\$2a\$12\$" \
  src/main/resources/db/migration/V8__seed_default_admin.sql \
  src/main/resources/db/kingbase/V8__seed_default_admin.sql
git diff --check
git add src/main/resources/db/migration/V8__seed_default_admin.sql \
  src/main/resources/db/kingbase/V8__seed_default_admin.sql \
  src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java
git commit --only \
  src/main/resources/db/migration/V8__seed_default_admin.sql \
  src/main/resources/db/kingbase/V8__seed_default_admin.sql \
  src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java \
  -m "feat(service): seed default administrator"
```

Expected: 两套脚本的账号、显示名、角色和 BCrypt 哈希一致；提交不包含当前工作区其他预存修改。

---

### Task 2: 验证真实 MySQL 迁移结果

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的两套 V8 和 `DefaultAdminMigrationContractTest.DEFAULT_PASSWORD_HASH`。
- Produces: 隔离 MySQL 8.4.3 中 Flyway V1–V8 的默认管理员、启用状态和唯一 ADMIN 关联证据。

- [ ] **Step 1: 扩展 MySQL 迁移集成断言**

在现有 `migrationEnforcesActiveOnlyUniquenessAndAllowsHistory()` 的 `Flyway.migrate()` 和 `JdbcTemplate` 初始化之后、插入 `test_admin` 之前加入：

```java
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
assertThat(jdbc.queryForObject("""
    SELECT is_enabled + 0
    FROM sys_user
    WHERE tenant_id=1 AND normalized_username='hr_6298bc833379'
    """, Integer.class)).isEqualTo(1);
assertThat(jdbc.queryForObject("""
    SELECT COUNT(*)
    FROM sys_user_role ur
    JOIN sys_user u ON u.id=ur.user_id AND u.tenant_id=ur.tenant_id
    JOIN sys_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id
    WHERE u.normalized_username='hr_6298bc833379' AND r.role_code='ADMIN'
    """, Integer.class)).isEqualTo(1);
```

- [ ] **Step 2: 先执行无数据库编译与契约回归**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=DefaultAdminMigrationContractTest,KingbaseMigrationContractTest test
```

Expected: 5 个静态契约测试通过，不连接数据库。

- [ ] **Step 3: 检查本机是否已有 MySQL 测试镜像**

Run:

```bash
docker image inspect mysql:8.4.3
```

Expected: 若退出码为 0，镜像已经存在，可继续 Step 4；若不存在，禁止拉取，跳过 Step 4 并在交付中明确“真实 MySQL V8 未运行”。不得改为连接用户的 `local-mysql`。

- [ ] **Step 4: 在已有镜像时运行隔离 MySQL 测试**

仅当 Step 3 成功时运行：

```bash
TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_CHECKS_DISABLE=true \
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=MySqlMigrationIntegrationTest test
```

Expected: Testcontainers 创建临时容器，测试 1 个、失败 0 个；迁移后共有 15 张业务表且默认管理员/ADMIN 关联断言通过。测试结束自动销毁临时容器，不操作现有 `local-mysql` 数据卷。

- [ ] **Step 5: 提交集成测试**

Run:

```bash
git diff --check
git add src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java
git commit --only \
  src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java \
  -m "test(service): verify seeded administrator migration"
```

---

### Task 3: 同步默认数据与部署说明

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `config/application-prod.yml`
- Modify: `../AGENTS.md`
- Modify: `../CODE_GUIDE.md`

**Interfaces:**
- Consumes: Task 1/2 已实现的 V8 默认管理员及当前工作区预存的生产配置示例重命名。
- Produces: 与实际 Flyway V8 一致的默认数据、安全边界和部署说明。

- [ ] **Step 1: 更新服务 README**

在默认数据/生产 KingbaseES 说明中明确：

```markdown
Flyway V8 会为默认租户创建一个启用的 `ADMIN` 登录：账号 `hr_6298bc833379`、显示名称“人资部门”。密码只以 BCrypt cost 12 哈希存在于迁移中，明文由部署负责人单独保管。
```

把“Flyway 只建立 V7 最终结构和初始角色”改为“Flyway 建立 V8 最终结构、初始角色和默认管理员”，删除“生产首次启动必须提供管理员引导变量”的要求。保留当前 `config/application-prod.yml` 路径修改。

- [ ] **Step 2: 收敛生产变量示例**

在已重命名的 `config/application-prod.yml` 中保留数据库和三项安全密钥变量，删除以下已经不再是标准首次启动必需项的三行：

```dotenv
PA_BOOTSTRAP_ADMIN_USERNAME=
PA_BOOTSTRAP_ADMIN_PASSWORD=
PA_BOOTSTRAP_ADMIN_DISPLAY_NAME=初始管理员
```

不改变该文件中其余值，不加入默认管理员明文密码。

- [ ] **Step 3: 更新服务与根知识库**

同步以下事实：

- MySQL 和 KingbaseES 当前 Flyway 版本均到 V8；
- V8 固定初始化一个 BCrypt 哈希管理员和唯一 `ADMIN` 关联；
- 同名账号迁移时会被确定性重置并解除锁定；
- 新标准库因已有 V8 用户而不会触发 `AdminBootstrap`，但兼容代码暂时保留；
- 仓库和日志不得出现默认管理员明文密码；
- `db/kingbase/V8__seed_default_admin.sql` 属于 Kingbase 静态契约与部署验收范围。

在 `../CODE_GUIDE.md` 的数据库/测试表中增加两套 V8 和 `DefaultAdminMigrationContractTest`，并把最后核对日期保持为 `2026-07-30`。

- [ ] **Step 4: 检查文档和预存修改边界**

Run:

```bash
rg -n "V7|V8|hr_6298bc833379|默认管理员|bootstrap|application-prod" \
  README.md AGENTS.md config/application-prod.yml ../AGENTS.md ../CODE_GUIDE.md
git diff --check
git status --short
```

Expected: 文档不再把 V7 或 `PA_BOOTSTRAP_ADMIN_*` 描述为生产最终状态；没有明文密码。确认旧计划文件的用户修改仍被保留且未被本任务覆盖。

- [ ] **Step 5: 提交服务仓库文档**

服务仓库提交只包含 README、服务 AGENTS 和生产变量示例的最终内容；根目录文档因父目录不是 Git 仓库单独保留：

```bash
git add README.md AGENTS.md config/application-prod.yml
git commit -m "docs(service): document seeded administrator"
```

提交前用 `git diff --cached --stat` 明确披露并确认当前已暂存的生产配置重命名会随本次文档提交纳入；不得纳入用户修改的旧实施计划。

---

### Task 4: 最终无 Kingbase 连接验证与交付审计

**Files:**
- Verify only: Tasks 1–3 的全部服务文件
- Verify only: `../AGENTS.md`
- Verify only: `../CODE_GUIDE.md`

**Interfaces:**
- Consumes: 双 V8、迁移契约、可选隔离 MySQL 验证和同步文档。
- Produces: 最新测试/打包证据、服务分支提交状态和明确的 Kingbase 未验证边界。

- [ ] **Step 1: 运行完整无数据库回归**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=CorsConfigurationTest,ApiContractSerializationTest,ProductionProfileConfigurationTest,DatabaseDriverAvailabilityTest,DefaultAdminMigrationContractTest,KingbaseMigrationContractTest,MapperSqlPortabilityTest,AdminBootstrapTest,LoginFailureRecorderTest,AuthServiceSessionPersistenceTest,PersonQueryServiceTest,PersonControllerTest,ImportItemServiceTest,ImportHistoryServiceTest,ArchiveExportServiceTest,BatchArchiveExportServiceTest test
```

Expected: 所列测试全部通过，日志没有数据库连接尝试。

- [ ] **Step 2: 打包可执行 JAR**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -DskipTests package
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 检查 JAR 内的两套 V8**

Run:

```bash
jar tf target/personnel-archive-service-0.1.0-SNAPSHOT.jar | \
  rg "db/migration/V8__seed_default_admin.sql|db/kingbase/V8__seed_default_admin.sql"
```

Expected: 两个 V8 资源都存在。

- [ ] **Step 4: 执行最终源码与提交审计**

Run:

```bash
rg -n "\$2a\$12\$|hr_6298bc833379|人资部门" \
  src/main/resources/db/migration/V8__seed_default_admin.sql \
  src/main/resources/db/kingbase/V8__seed_default_admin.sql \
  src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java
git diff --check
git status --short
git log --oneline -6
```

Expected: 两套脚本和契约只有同一 BCrypt 哈希；服务源码提交完整。若旧计划仍有用户未提交修改，保留并在交付中单独列出，不得声称整个工作区干净。

- [ ] **Step 5: 记录验证边界**

交付说明必须区分：

- 离线 BCrypt 对应关系已验证为 `matches=true`，不展示明文；
- 无数据库测试和 JAR 打包结果；
- MySQL Testcontainers 是否因本机已有镜像而实际执行；
- 未连接 KingbaseES，因此 Kingbase V8 实际落库和登录仍需部署验收；
- 用户预存的旧计划修改是否仍未提交。
