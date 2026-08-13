# Production MySQL-Only Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make both `dev` and `prod` run on MySQL, remove all active KingbaseES runtime/build/migration/test support, synchronize current documentation, and push `chore/prod-mysql-only` to `origin`.

**Architecture:** Keep the existing Spring profile split, with `application.yml` for shared settings, `application-dev.yml` for local MySQL, and `application-prod.yml` for environment-injected production MySQL. Preserve the immutable MySQL Flyway V1–V14 path as the only database schema path and delete the parallel KingbaseES path and its drivers. No API, business workflow, schema version, or client contract changes.

**Tech Stack:** Java 21, Spring Boot 4.0.7, MyBatis 4.0.1, Flyway, MySQL Connector/J, Maven Wrapper, JUnit 5, AssertJ, Testcontainers MySQL.

## Global Constraints

- Work only in `/Users/zhongerbing/Documents/xm/personnel-archive/personnel-archive-service/.worktrees/prod-mysql-only` on `chore/prod-mysql-only`; do not touch the original service checkout's staged Excel changes.
- Keep `application-dev.yml` unchanged.
- Never copy the development database password or development token/identity keys into `application-prod.yml`.
- Production secrets remain `PA_DB_PASSWORD`, `PA_TOKEN_PEPPER`, `PA_IDENTITY_HMAC_KEY`, and `PA_IDENTITY_ENCRYPTION_KEY` environment inputs.
- Every production MySQL JDBC URL must retain `connectionTimeZone=Asia/Shanghai`.
- Do not rewrite MySQL V1–V14 migrations and do not add a new migration; this change only selects the existing MySQL path.
- Do not migrate or claim to migrate KingbaseES data into MySQL.
- Do not change HTTP APIs, OpenAPI, desktop types, import/export semantics, default administrator identity, or authentication behavior.
- Retain dated historical specs/plans as historical records; current source, configuration, README, and knowledge-base documents must describe MySQL-only support.
- The repository root is not a Git repository. Update root `AGENTS.md` and `CODE_GUIDE.md` locally, but never claim those two files are present in the pushed backend commit.
- Run Maven with `JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle` because the shell default is Java 17.

---

## File Structure

**Modify in the backend branch:**

- `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java` — production profile contract.
- `src/main/resources/application-prod.yml` — production MySQL datasource and Flyway selection.
- `src/test/java/com/company/personnelarchive/configuration/DatabaseDriverAvailabilityTest.java` — retained MySQL driver smoke test.
- `src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java` — MySQL V8 seed contract.
- `pom.xml` — MySQL-only runtime/Flyway dependencies.
- `README.md` — production MySQL deployment instructions.
- `AGENTS.md` — current backend status and engineering rules.

**Delete from the backend branch:**

- `src/test/java/com/company/personnelarchive/configuration/KingbaseMigrationContractTest.java`.
- `src/main/java/db/kingbase/V10__backfill_structured_source_values.java`.
- `src/main/java/db/kingbase/V11__backfill_mixed_delimited_date_projections.java`.
- `src/main/java/db/kingbase/V13__canonicalize_active_age_and_date_values.java`.
- `src/main/java/db/kingbase/V14__canonicalize_open_ended_range_text.java`.
- `src/main/resources/db/kingbase/V7__kingbase_initial_schema.sql`.
- `src/main/resources/db/kingbase/V8__seed_default_admin.sql`.
- `src/main/resources/db/kingbase/V9__add_structured_source_value_projections.sql`.
- `src/main/resources/db/kingbase/V12__expand_import_result_message.sql`.

**Modify locally outside the backend Git repository:**

- `/Users/zhongerbing/Documents/xm/personnel-archive/AGENTS.md` — cross-project implementation status, profile, migration, and database invariants.
- `/Users/zhongerbing/Documents/xm/personnel-archive/CODE_GUIDE.md` — architecture diagram, backend module map, test map, invariants, and roadmap.

---

### Task 1: Change the production profile contract to MySQL

**Files:**

- Modify: `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java`
- Modify: `src/main/resources/application-prod.yml`

**Interfaces:**

- Consumes: `PA_DB_URL`, `PA_DB_USERNAME`, `PA_DB_PASSWORD`, shared `server.port`, and the existing MySQL migration location.
- Produces: `prod` profile datasource properties using `com.mysql.cj.jdbc.Driver` and `classpath:db/migration` without a separate Flyway URL.

- [ ] **Step 1: Replace the Kingbase production assertions with the desired MySQL contract**

Replace `usesKingbaseForProduction()` with:

```java
@Test
void usesMysqlForProduction() throws IOException {
    assertThat(resolvedProperty("spring.datasource.url", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .startsWith("jdbc:mysql://127.0.0.1:3306/personnel_archive")
        .contains("connectionTimeZone=Asia/Shanghai");
    assertThat(resolvedProperty("spring.datasource.driver-class-name", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEqualTo("com.mysql.cj.jdbc.Driver");
    assertThat(resolvedProperty("spring.flyway.locations", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEqualTo("classpath:db/migration");
    assertThat(optionalProperty("spring.flyway.url", Map.of(),
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEmpty();
}
```

Replace the production database override map and assertions with:

```java
Map<String, String> overrides = Map.of(
    "PA_DB_URL", "jdbc:mysql://db.example:3306/archive?connectionTimeZone=Asia/Shanghai",
    "PA_DB_USERNAME", "archive_runtime",
    "PA_DB_PASSWORD", "injected-secret");

assertThat(resolvedProperty("spring.datasource.url", overrides,
    "src/main/resources/application.yml",
    "src/main/resources/application-prod.yml"))
    .isEqualTo("jdbc:mysql://db.example:3306/archive?connectionTimeZone=Asia/Shanghai");
assertThat(resolvedProperty("spring.datasource.username", overrides,
    "src/main/resources/application.yml",
    "src/main/resources/application-prod.yml"))
    .isEqualTo("archive_runtime");
assertThat(resolvedProperty("spring.datasource.password", overrides,
    "src/main/resources/application.yml",
    "src/main/resources/application-prod.yml"))
    .isEqualTo("injected-secret");
assertThat(optionalProperty("spring.flyway.url", overrides,
    "src/main/resources/application.yml",
    "src/main/resources/application-prod.yml"))
    .isEmpty();
```

- [ ] **Step 2: Run the production profile test and verify RED**

Run:

```bash
env JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
  ./mvnw -Dtest=ProductionProfileConfigurationTest test
```

Expected: FAIL because the old profile resolves a `jdbc:kingbase8:` URL, `com.kingbase8.Driver`, `classpath:db/kingbase`, and a dedicated PostgreSQL Flyway URL.

- [ ] **Step 3: Replace `application-prod.yml` with the minimal production MySQL configuration**

```yaml
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: "${PA_DB_URL:jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai}"
    username: ${PA_DB_USERNAME:personnel_archive}
    password: ${PA_DB_PASSWORD:}
  flyway:
    locations: classpath:db/migration
```

Do not add production defaults for any security key, do not add `useSSL=false`, and do not retain `PA_FLYWAY_DB_URL`.

- [ ] **Step 4: Run the production profile test and verify GREEN**

Run the same targeted Maven command from Step 2.

Expected: 7 tests pass, 0 failures.

- [ ] **Step 5: Commit the profile change**

```bash
git add src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java \
  src/main/resources/application-prod.yml
git commit -m "refactor(service): run production on MySQL"
```

---

### Task 2: Remove KingbaseES dependencies, migrations, and tests

**Files:**

- Modify: `src/test/java/com/company/personnelarchive/configuration/DatabaseDriverAvailabilityTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java`
- Modify: `pom.xml`
- Delete: `src/test/java/com/company/personnelarchive/configuration/KingbaseMigrationContractTest.java`
- Delete: all eight files under `src/main/java/db/kingbase/` and `src/main/resources/db/kingbase/` listed in the file structure.

**Interfaces:**

- Consumes: MySQL Connector/J, Flyway MySQL, MySQL V1–V14 migration trees.
- Produces: a build with no KingbaseES/PostgreSQL runtime driver and no KingbaseES migration classpath.

- [ ] **Step 1: Establish GREEN before the dependency-removal refactor**

Task 1's failing production-profile contract already drives the behavior change. Before removing now-unused support, run:

```bash
env JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
  ./mvnw -Dtest=ProductionProfileConfigurationTest,DatabaseDriverAvailabilityTest,DefaultAdminMigrationContractTest,KingbaseMigrationContractTest test
```

Expected: all current tests pass. The remaining steps are a green refactor that removes the inactive database path; no source-text or directory-absence unit test is added.

- [ ] **Step 2: Convert the driver smoke test to MySQL**

Replace the test body with:

```java
@Test
void loadsMysqlDriverWithoutConnecting() throws Exception {
    assertThat(new com.mysql.cj.jdbc.Driver()
        .acceptsURL("jdbc:mysql://127.0.0.1:3306/personnel_archive"))
        .isTrue();
}
```

- [ ] **Step 3: Convert the default administrator contract to MySQL-only**

Remove the `KINGBASE` path. Replace the two test methods with:

```java
@Test
void seedsTheBcryptAdminInMysql() throws IOException {
    String mysql = Files.readString(MYSQL);

    assertThat(mysql)
        .contains("'hr_6298bc833379'")
        .contains("'人资部门'")
        .contains("tenant_code = 'DEFAULT'")
        .contains("role_code = 'ADMIN'")
        .contains("'" + DEFAULT_PASSWORD_HASH + "'")
        .contains("failed_login_count = 0")
        .contains("locked_until = NULL")
        .contains("password_changed_at = CURRENT_TIMESTAMP(6)")
        .contains("updated_at = CURRENT_TIMESTAMP(6)");
    assertThat(DEFAULT_PASSWORD_HASH)
        .startsWith("$2a$12$")
        .hasSize(60);
}

@Test
void usesMysqlIdempotentUpserts() throws IOException {
    assertThat(Files.readString(MYSQL))
        .contains("ON DUPLICATE KEY UPDATE")
        .contains("role_id = VALUES(role_id)");
}
```

- [ ] **Step 4: Delete the KingbaseES-only tests and migration trees with `apply_patch`**

Delete exactly the nine files listed under this task. Do not delete or modify any file under `src/main/resources/db/migration/` or `src/main/java/db/migration/`.

- [ ] **Step 5: Remove the three non-MySQL dependencies from `pom.xml`**

Delete these complete dependency blocks:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>cn.com.kingbase</groupId>
    <artifactId>kingbase8</artifactId>
    <version>9.0.1</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 6: Run focused database-contract tests and verify GREEN**

Run:

```bash
env JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
  ./mvnw -Dtest=ProductionProfileConfigurationTest,DatabaseDriverAvailabilityTest,DefaultAdminMigrationContractTest test
```

Expected: 10 tests pass, 0 failures.

- [ ] **Step 7: Verify the resolved dependency tree excludes removed drivers**

Run:

```bash
env JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
  ./mvnw dependency:tree \
  -Dincludes=cn.com.kingbase:kingbase8,org.postgresql:postgresql,org.flywaydb:flyway-database-postgresql
```

Expected: build success with no matching dependency lines. This checks Maven's resolved runtime graph rather than grepping source text.

- [ ] **Step 8: Commit the removal**

```bash
git add pom.xml src/main src/test/java/com/company/personnelarchive/configuration
git commit -m "refactor(service): remove KingbaseES support"
```

---

### Task 3: Synchronize current backend and cross-project documentation

**Files:**

- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify locally: `/Users/zhongerbing/Documents/xm/personnel-archive/AGENTS.md`
- Modify locally: `/Users/zhongerbing/Documents/xm/personnel-archive/CODE_GUIDE.md`

**Interfaces:**

- Consumes: the final MySQL-only profile/dependency/migration behavior from Tasks 1–2.
- Produces: current documentation that no longer advertises KingbaseES as supported; dated historical specs remain untouched.

- [ ] **Step 1: Rewrite the service README database overview and production section**

The opening paragraph must say the service uses MySQL and that both profiles use the MySQL V1–V14 path. Replace `## 生产 KingbaseES` with `## 生产 MySQL` and include these exact operational facts:

```markdown
## 生产 MySQL

`prod` profile 默认监听 `18080`，使用 MySQL Connector/J 和 `classpath:db/migration`。默认数据源指向同机 `127.0.0.1:3306/personnel_archive`；远程或不同端口的生产数据库必须通过 `PA_DB_URL` 覆盖完整 JDBC URL，并保留 `connectionTimeZone=Asia/Shanghai`。

生产首次启动前至少配置 `SPRING_PROFILES_ACTIVE=prod`、`PA_DB_USERNAME`、`PA_DB_PASSWORD` 和三项安全密钥。Flyway 使用同一应用数据源执行 MySQL V1–V14，不再使用独立的 Flyway URL。部署前由 DBA 创建数据库 `personnel_archive`、账号并授予最小权限；Flyway 不创建数据库本身，也不迁移其他数据库产品中的数据。

部署后须在目标 MySQL 验收迁移、默认管理员登录、导入、覆盖、查询、软删除和 XLSX/ZIP 导出。生产凭据和三项安全密钥只能通过部署环境或秘密管理系统注入。
```

Remove `PA_FLYWAY_DB_URL` and every active KingbaseES/PostgreSQL deployment instruction.

- [ ] **Step 2: Update the service knowledge base to MySQL-only**

Make these current-state changes in `AGENTS.md`:

- Current migrations: MySQL V1–V14 only.
- Profiles: both `dev` and `prod` use MySQL; dev has local defaults, prod uses environment-injected credentials.
- Stack: remove KingbaseES JDBC, PostgreSQL JDBC, and Flyway PostgreSQL.
- Database rules: `personnel_archive` on MySQL 8.4; Flyway path is only `classpath:db/migration`.
- Uniqueness, BLOB, timestamps, default administrator, tests, and release verification describe only MySQL implementations.
- Remove requirements for parallel Kingbase migrations, static contract tests, runtime testing, backups, and `EXPLAIN` baselines.

Keep every non-database business/security/import/export invariant unchanged.

- [ ] **Step 3: Update root `AGENTS.md` locally with surgical replacements**

Change current implementation status, configuration, data model, time-zone, and completion statements so they say:

- MySQL Flyway V1–V14 is the only migration path.
- `application-dev.yml` and `application-prod.yml` both use MySQL; production credentials are environment-injected.
- MySQL `utf8mb4`, BLOB, active generated unique keys, and `DATETIME(6)` are the only database-specific implementations.
- Remove KingbaseES baseline, partial-index, BYTEA, PostgreSQL-protocol, and session-time-zone statements.

Do not change product names, API behavior, Excel invariants, or the user's reference workbook path.

- [ ] **Step 4: Update root `CODE_GUIDE.md` locally**

Change the architecture diagram, backend module/test map, database invariants, and roadmap to MySQL-only. In particular:

- `Spring Boot -> MyBatis + Flyway -> MySQL` is the sole persistence route.
- Backend migration map contains only MySQL V1–V14.
- Configuration tests describe production MySQL and MySQL-only dependency/migration guards.
- Photos are MySQL BLOBs; timestamps use MySQL `DATETIME(6)` with `connectionTimeZone=Asia/Shanghai`.
- Remove real KingbaseES deployment, backup, restore, and `EXPLAIN` roadmap work.

- [ ] **Step 5: Verify active source and current docs no longer claim KingbaseES support**

Run in the backend worktree:

```bash
rg -n "Kingbase|KingbaseES|kingbase|PostgreSQL|postgresql|PA_FLYWAY_DB_URL" \
  pom.xml README.md AGENTS.md src/main src/test
```

Expected: no matches.

Run from the repository root:

```bash
rg -n "Kingbase|KingbaseES|kingbase|PostgreSQL|postgresql|PA_FLYWAY_DB_URL" \
  AGENTS.md CODE_GUIDE.md
```

Expected: no matches.

- [ ] **Step 6: Commit tracked documentation**

```bash
git add README.md AGENTS.md
git commit -m "docs(service): document MySQL-only deployment"
```

Do not attempt to stage the two root files; they are outside this Git repository.

---

### Task 4: Verify, review, push, and report

**Files:**

- Verify: all backend changes relative to `origin/dev-yx-20260724`.
- Verify locally: `/Users/zhongerbing/Documents/xm/personnel-archive/AGENTS.md` and `/Users/zhongerbing/Documents/xm/personnel-archive/CODE_GUIDE.md`.

**Interfaces:**

- Consumes: all previous task commits.
- Produces: a tested remote branch `origin/chore/prod-mysql-only` and an evidence-backed handoff.

- [ ] **Step 1: Run whitespace and scope checks**

```bash
git diff --check origin/dev-yx-20260724...HEAD
git status --short --branch
git diff --stat origin/dev-yx-20260724...HEAD
git diff --name-status origin/dev-yx-20260724...HEAD
```

Expected: no whitespace errors; only the design/plan, production configuration, database dependencies/assets/tests, README, and service `AGENTS.md` are changed.

- [ ] **Step 2: Run the full Java 21 verification suite**

```bash
env JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw verify
```

Expected: BUILD SUCCESS, zero failures and zero errors. If Docker remains unavailable, disclose every skipped Testcontainers test and do not claim real MySQL migration execution.

- [ ] **Step 3: Inspect the packaged JAR for removed database libraries**

```bash
jar tf target/personnel-archive-service-0.1.0-SNAPSHOT.jar | \
  rg "BOOT-INF/lib/(kingbase|postgresql|flyway-database-postgresql)"
```

Expected: `rg` exits 1 with no output because none of those libraries are packaged.

- [ ] **Step 4: Review the final branch history and worktree cleanliness**

```bash
git log --oneline --decorate origin/dev-yx-20260724..HEAD
git status --short --branch
```

Expected: design, plan, profile, removal, and documentation commits are present; tracked worktree is clean.

- [ ] **Step 5: Push the completed branch to `origin`**

```bash
git push -u origin chore/prod-mysql-only
```

Expected: remote branch creation succeeds and the local branch tracks `origin/chore/prod-mysql-only`.

- [ ] **Step 6: Confirm the remote ref matches local HEAD**

```bash
git fetch origin
git rev-parse HEAD
git rev-parse origin/chore/prod-mysql-only
```

Expected: the two hashes are identical.

- [ ] **Step 7: Report the handoff**

Report:

- branch name and final commit hash;
- remote push result;
- exact Maven test totals and Docker-related skips;
- production profile default URL/driver/Flyway location and required environment variables;
- deleted KingbaseES dependencies/migration paths/tests;
- confirmation that the original checkout's staged changes were untouched;
- explicit note that root `AGENTS.md` and `CODE_GUIDE.md` were updated locally but cannot be included in the backend Git branch.
