# JDBC Timezone Fix Implementation Plan

> Historical scope note: business-date `DATE`/`LocalDate` statements were superseded by the 2026-07-29 source-value fidelity work and Flyway V7. The `DATETIME(6)` timestamp mapping rules in this plan remain current.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the eight-hour archive timestamp offset by interpreting existing MySQL `DATETIME(6)` values as `Asia/Shanghai` wall-clock time.

**Architecture:** Keep the database values and UTC API serialization unchanged. Load the production JDBC URL options in a real-MySQL regression test, prove the current UTC option maps a fixed China wall-clock value to the wrong `Instant`, then replace the legacy option with Connector/J 9.7's `connectionTimeZone=Asia/Shanghai` in both default and local profiles.

**Tech Stack:** Java 21, Spring Boot 4.0.7, MySQL Connector/J 9.7.0, JUnit 5, Spring JDBC, Testcontainers MySQL 8.4.3, Maven Wrapper.

## Global Constraints

- Existing database rows are correct China-local `DATETIME(6)` values and must not be migrated or rewritten.
- `spring.jackson.time-zone` remains `UTC`; API timestamps remain ISO-8601 instants ending in `Z`.
- The Electron renderer keeps native `Intl.DateTimeFormat` conversion and receives no fixed eight-hour adjustment.
- Business `DATE` / `LocalDate` fields are outside this change.
- Use `connectionTimeZone=Asia/Shanghai`; do not add `forceConnectionTimeZoneToSession=true`.
- Testcontainers must run against Docker; a skipped MySQL test is not completion evidence.
- Preserve all existing local credentials and change only the JDBC URL in `config/application-local.yml`.

---

### Task 1: Lock and fix the JDBC `DATETIME` interpretation

**Files:**
- Create: `src/test/java/com/company/personnelarchive/configuration/MySqlTimestampMappingIntegrationTest.java`
- Modify: `src/main/resources/application.yml:5`
- Modify: `config/application-local.yml:6`

**Interfaces:**
- Consumes: `spring.datasource.url` from each service profile and Testcontainers' temporary JDBC authority/database.
- Produces: Both service profiles use `connectionTimeZone=Asia/Shanghai`; a real-MySQL test proves `2026-07-24 11:06:01.887644` maps to `2026-07-24T03:06:01.887644Z`.

- [ ] **Step 1: Write the failing real-MySQL regression test**

Create `src/test/java/com/company/personnelarchive/configuration/MySqlTimestampMappingIntegrationTest.java`:

```java
/* JDBC 时区回归测试：生产连接参数必须把中国本地 DATETIME 映射为正确时间点。 */
package com.company.personnelarchive.configuration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MySqlTimestampMappingIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("personnel_archive")
        .withUsername("test_user")
        .withPassword("test_password");

    @ParameterizedTest(name = "{0} maps China wall-clock DATETIME to the correct instant")
    @ValueSource(strings = {
        "src/main/resources/application.yml",
        "config/application-local.yml"
    })
    void mapsChinaWallClockDatetimeToCorrectInstant(String configPath) throws IOException {
        // Given
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            testJdbcUrl(configPath), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS pa_timestamp_probe");
        jdbc.execute("CREATE TABLE pa_timestamp_probe (captured_at DATETIME(6) NOT NULL)");
        jdbc.update("INSERT INTO pa_timestamp_probe (captured_at) VALUES ('2026-07-24 11:06:01.887644')");

        // When
        Instant actual = jdbc.queryForObject("SELECT captured_at FROM pa_timestamp_probe",
            (resultSet, rowNumber) -> resultSet.getTimestamp("captured_at").toInstant());

        // Then
        assertThat(actual).isEqualTo(Instant.parse("2026-07-24T03:06:01.887644Z"));
    }

    private static String testJdbcUrl(String configPath) throws IOException {
        String configuredUrl = configuredDatasourceUrl(configPath);
        int queryStart = configuredUrl.indexOf('?');
        if (queryStart < 0) {
            throw new IllegalStateException("Configured JDBC URL has no query parameters: " + configPath);
        }
        String separator = MYSQL.getJdbcUrl().contains("?") ? "&" : "?";
        return MYSQL.getJdbcUrl() + separator + configuredUrl.substring(queryStart + 1);
    }

    private static String configuredDatasourceUrl(String configPath) throws IOException {
        Object rawValue = new YamlPropertySourceLoader()
            .load("timezone-regression", new FileSystemResource(configPath))
            .stream()
            .map(source -> source.getProperty("spring.datasource.url"))
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing spring.datasource.url: " + configPath));
        return new MockEnvironment().resolveRequiredPlaceholders(rawValue.toString());
    }
}
```

- [ ] **Step 2: Run the focused test and verify the expected RED result**

Run:

```bash
./mvnw -Dtest=MySqlTimestampMappingIntegrationTest test
```

Expected: both parameterized cases fail because the actual value is `2026-07-24T11:06:01.887644Z`, proving the current `serverTimezone=UTC` option creates the eight-hour error.

- [ ] **Step 3: Make the minimum configuration change**

In `src/main/resources/application.yml`, replace only the legacy timezone option in the default URL:

```yaml
url: ${PA_DB_URL:jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai}
```

In `config/application-local.yml`, preserve every existing value and replace only `serverTimezone=UTC`:

```yaml
url: "jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
```

- [ ] **Step 4: Re-run the focused test and verify GREEN**

Run:

```bash
./mvnw -Dtest=MySqlTimestampMappingIntegrationTest test
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit the behavioral fix**

```bash
git add src/test/java/com/company/personnelarchive/configuration/MySqlTimestampMappingIntegrationTest.java \
  src/main/resources/application.yml config/application-local.yml
git commit -m "fix: align JDBC timestamps with China time"
```

---

### Task 2: Document the timestamp contract

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `openapi/personnel-archive-api.yaml:5`
- Modify: `../AGENTS.md`
- Modify: `../CODE_GUIDE.md`

**Interfaces:**
- Consumes: The fixed JDBC rule from Task 1.
- Produces: Deployment, API, and engineering documentation consistently state that MySQL `DATETIME` is China-local, JDBC interprets it using `Asia/Shanghai`, and API instants are serialized in UTC.

- [ ] **Step 1: Update service deployment documentation**

After the environment-variable list in `README.md`, add:

```markdown
自定义 `PA_DB_URL` 必须包含 `connectionTimeZone=Asia/Shanghai`。MySQL `DATETIME` 保存中国本地时间，服务将其映射为准确的 UTC `Instant` 后再通过 API 输出；不要在客户端固定增减八小时。
```

- [ ] **Step 2: Update service and cross-project invariants**

Add this database rule to `AGENTS.md`:

```markdown
- MySQL `DATETIME(6)` audit and snapshot timestamps use China wall-clock time. Every service JDBC URL must set `connectionTimeZone=Asia/Shanghai`; Java reads API timestamps as `Instant`, and Jackson serializes those instants in UTC. Do not compensate in a client.
```

Replace the generic timestamp sentence in `../AGENTS.md` with:

```markdown
- Use `yyyy-MM-dd` for API business dates. MySQL `DATETIME(6)` timestamps are China wall-clock values interpreted by JDBC with `connectionTimeZone=Asia/Shanghai`, then serialized as UTC ISO-8601 instants; clients perform only native local-time rendering.
```

- [ ] **Step 3: Update the OpenAPI and code guide descriptions**

Change the OpenAPI info description to:

```yaml
description: 业务日期使用 yyyy-MM-dd；时间戳是从 Asia/Shanghai 数据库时间映射得到的 UTC ISO-8601 时间点。V1 tenant_id 固定为 1。Excel 业务字段不设必填项，响应中的对应字符串可以为空。
```

In `../CODE_GUIDE.md`, expand the `application.yml` responsibility to mention the JDBC `Asia/Shanghai` contract and add this invariant under section 7:

```markdown
- MySQL `DATETIME(6)` 保存中国本地墙上时间，JDBC 必须使用 `connectionTimeZone=Asia/Shanghai` 解释后再映射为 UTC `Instant`；前端只做系统本地化显示，禁止固定增减八小时。
```

- [ ] **Step 4: Check documentation consistency**

Run:

```bash
rg -n "connectionTimeZone=Asia/Shanghai|UTC `Instant`|UTC ISO-8601" \
  README.md AGENTS.md openapi/personnel-archive-api.yaml ../AGENTS.md ../CODE_GUIDE.md
git diff --check
```

Expected: every named document contains the same policy, and `git diff --check` emits no output.

- [ ] **Step 5: Commit tracked service documentation**

The repository root documents are workspace-level files outside both child Git repositories, so commit the service-owned files and leave the synchronized root documents in place:

```bash
git add README.md AGENTS.md openapi/personnel-archive-api.yaml
git commit -m "docs: define China timestamp contract"
```

---

### Task 3: Run complete verification and restart the local service

**Files:**
- Verify: all service production and test sources
- Verify: local MySQL container and running desktop application

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: A verified service build, a non-skipped real-MySQL regression report, and a local archive list displaying the database's China wall-clock time without an extra eight hours.

- [ ] **Step 1: Run the authoritative backend verification**

```bash
./mvnw verify
```

Expected: `BUILD SUCCESS` with no failed tests.

- [ ] **Step 2: Prove the MySQL regression was not skipped**

```bash
rg -n '<testsuite.*tests="2".*errors="0".*skipped="0".*failures="0"' \
  target/surefire-reports/TEST-com.company.personnelarchive.configuration.MySqlTimestampMappingIntegrationTest.xml
```

Expected: one matching `<testsuite ...>` line.

- [ ] **Step 3: Restart the service with the local profile**

Stop the existing Spring Boot process cleanly, then run:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Expected: Flyway reports the schema up to date and the API listens on port `18080` without a datasource timezone error.

- [ ] **Step 4: Verify the observable desktop result**

Open the active archive list in the already running desktop client. Compare one row's MySQL `updated_at` wall-clock value with its “更新日期” cell.

Expected: a stored value such as `2026-07-24 11:06:01` displays as `2026/07/24 11:06`, not `19:06`; birth dates and other date-only fields remain unchanged.

- [ ] **Step 5: Verify repository state and commit history**

```bash
git status --short
git log -4 --oneline
```

Expected: the service repository is clean, with separate design, implementation-plan, configuration/test, and documentation commits. Workspace-level `../AGENTS.md` and `../CODE_GUIDE.md` remain synchronized outside the child Git repository.
