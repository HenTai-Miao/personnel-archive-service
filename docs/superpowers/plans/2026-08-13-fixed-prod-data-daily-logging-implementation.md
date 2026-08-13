# Fixed Prod Database, `/data` Deployment, and Daily Logging Implementation Plan

> **Superseded for prod security/startup:** the user subsequently required all three security values to
> be fixed in `application-prod.yml` and first startup to work with only the fixed-path JAR plus launcher.
> Follow `2026-08-13-self-contained-prod-security-and-launcher-implementation.md`; the target-generated
> key and `EnvironmentFile` steps below are retained only as prior-plan history.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Subagents are not used because the active project instructions forbid delegation unless the user explicitly requests it.

**Goal:** Produce a Java 21 UOS offline production bundle whose `prod` profile contains the user's fixed local MySQL connection, installs under `/data/personnel-archive-service`, writes one directly readable unlimited `log.YYYY-MM-DD.log` file per day without compression, records every approved operational checkpoint, and includes a standalone prod launcher on the desktop.

**Architecture:** Spring Boot keeps the fixed `prod` datasource in `application-prod.yml`; only the three application cryptographic keys remain target-generated environment values. A profile-aware `logback-spring.xml` uses `TimeBasedRollingPolicy` without size/history/total limits. systemd owns the process and fixes the `/data` working directory; the installer and standalone launcher never run the JAR directly.

**Tech Stack:** Java 21, Spring Boot 4.0.7, Logback, Flyway 11.14.1, MySQL Connector/J, Maven Wrapper, Bash, systemd, JUnit 5, Mockito, AssertJ, Testcontainers MySQL 8.4.

## Global Constraints

- The fixed production datasource is `jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai`, username `personnel_archive`, and the exact password supplied in `/Users/zhongerbing/Desktop/新建 文本文档 (2).txt`.
- Never print the supplied password in commands, test failures, logs, documentation, chat, build metadata, or final response.
- The password is intentionally present in the local production YAML and generated JAR at the user's explicit direction, but implementation changes containing it are not committed or pushed to Git.
- Keep pre-existing `${PA_TOKEN_PEPPER}`, `${PA_IDENTITY_HMAC_KEY}`, and `${PA_IDENTITY_ENCRYPTION_KEY}` because they are separate per-install cryptographic keys and no fixed values were supplied.
- Install the service only under `/data/personnel-archive-service`; do not create, move, overwrite, or remove anything in `/opt`.
- Write the active application log directly as `log.YYYY-MM-DD.log` for each natural day; do not configure compression, `%i`, maximum file size, maximum history, total size cap, or automatic cleanup.
- Keep console output for systemd journal and file output beside the JAR.
- Operational logs may contain only fixed events, internal IDs, bounded counts, versions, byte counts, durations, fixed error codes, exception class names, and bounded code locations.
- Preserve the user's modified `ExcelArchiveReader.java`, staged `ExcelNumericDatePrecisionTest.java`, and untracked `.codex/` exactly.
- Never connect tests or build steps to the target offline MySQL; use existing isolated Testcontainers only.

---

### Task 1: Fix the prod datasource and daily Logback policy

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java`
- Create: `src/test/java/com/company/personnelarchive/configuration/ProductionLogbackConfigurationTest.java`
- Modify: `src/main/resources/application-prod.yml`
- Create: `src/main/resources/logback-spring.xml`

**Interfaces:**
- Consumes: Spring Boot's `logging.pattern.correlation` and the existing MDC key `requestId`.
- Produces: fixed prod datasource properties and a profile-aware Logback configuration with `CONSOLE` and `DAILY_FILE` appenders.

- [ ] **Step 1: Write failing prod configuration tests**

Change the production configuration assertions so they verify:

```java
assertThat(resolvedProperty("server.port", Map.of(), files)).isEqualTo("18080");
assertThat(resolvedProperty("spring.datasource.url", Map.of(), files))
    .isEqualTo("jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai");
assertThat(resolvedProperty("spring.datasource.username", Map.of(), files))
    .isEqualTo("personnel_archive");
assertThat(prodText).doesNotContain("${PA_DB_", "${PA_SERVER_PORT", "${PA_LOG_",
    "max-file-size", "max-history", "total-size-cap", "%i");
assertThat(prodPasswordLength(prodText)).isGreaterThan(0);
```

`prodPasswordLength` extracts only the length of the datasource password scalar and never places its value in an assertion message.

Add XML contract tests that parse `logback-spring.xml` and assert:

```java
assertThat(xml).contains("TimeBasedRollingPolicy", "./log/log.%d{yyyy-MM-dd}.log",
    "DAILY_FILE", "CONSOLE");
assertThat(xml).doesNotContain("<file>", ".gz");
assertThat(xml).doesNotContain("SizeAndTimeBasedRollingPolicy", "maxFileSize",
    "maxHistory", "totalSizeCap", "%i");
```

- [ ] **Step 2: Run the two tests and verify RED**

Run:

```bash
./mvnw -Dtest=ProductionProfileConfigurationTest,ProductionLogbackConfigurationTest test
```

Expected: failure because the current YAML still contains database/log placeholders and size/history caps, and `logback-spring.xml` does not exist.

- [ ] **Step 3: Implement the fixed YAML and daily policy**

Set the prod port, JDBC URL, username, logging filename and correlation pattern as literal YAML values. Insert the exact attachment password using a non-printing local extraction step. The password line must not be included in a tool result.

Create `logback-spring.xml` with Spring Boot defaults, `CONSOLE` in all profiles, and `DAILY_FILE` only in `prod`. Its rolling policy is exactly `ch.qos.logback.core.rolling.TimeBasedRollingPolicy` with:

```xml
<fileNamePattern>./log/log.%d{yyyy-MM-dd}.log</fileNamePattern>
```

Do not declare `maxHistory`, `totalSizeCap`, `maxFileSize`, or `cleanHistoryOnStart`.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the command from Step 2. Expected: both classes pass and test output does not contain the datasource password.

- [ ] **Step 5: Run a redacted configuration check**

Use a check that prints property names and password length only. Verify `application-prod.yml` contains no `${PA_DB_`, `${PA_SERVER_PORT`, `${PA_LOG_`, `%i`, `max-file-size`, `max-history`, or `total-size-cap`.

No Git commit is created because this task places the user-supplied password in a tracked source file.

### Task 2: Add explicit database, Flyway, and service lifecycle events

**Files:**
- Create: `src/test/java/com/company/personnelarchive/configuration/OperationalFlywayMigrationStrategyTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/OperationalStartupLoggerTest.java`
- Create: `src/main/java/com/company/personnelarchive/configuration/OperationalFlywayMigrationStrategy.java`
- Create: `src/main/java/com/company/personnelarchive/configuration/ServiceLifecycleLogger.java`
- Modify: `src/main/java/com/company/personnelarchive/PersonnelArchiveApplication.java`
- Modify: `src/main/java/com/company/personnelarchive/configuration/OperationalStartupLogger.java`

**Interfaces:**
- Consumes: `org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy`, Flyway `info()` and `migrate()`, `SafeJdbcTarget`, `SafeExceptionSummary`, and `OperationalLog`.
- Produces: a single Spring bean that executes Flyway exactly once and logs safe database/migration lifecycle fields.

- [ ] **Step 1: Write failing lifecycle tests**

Use a mocked Flyway and `LogCapture` to require these fixed event names:

```text
database_connection_started
database_connection_completed
database_connection_failed
flyway_migration_started
flyway_migration_completed
flyway_migration_failed
service_starting
service_ready
service_start_failed
security_configuration_checked
```

Success assertions require `currentVersion`, `finalVersion`, `migrationCount`, `result=SUCCESS`, and `durationMs`. Failure assertions require only fixed `phase`, `exceptionType`, safe stack locations and duration; a sentinel exception message must not appear.

Update the startup test to require separate fields:

```text
tokenPepperConfigured=true
identityHmacKeyConfigured=true
identityEncryptionKeyConfigured=true
```

- [ ] **Step 2: Run lifecycle tests and verify RED**

Run:

```bash
./mvnw -Dtest=OperationalFlywayMigrationStrategyTest,OperationalStartupLoggerTest test
```

Expected: missing class/events and the old aggregate `securityKeysConfigured` assertion failure.

- [ ] **Step 3: Implement the Flyway strategy**

`OperationalFlywayMigrationStrategy` implements Spring Boot 4's `FlywayMigrationStrategy` and is the only custom migration strategy bean. It safely parses host/port/schema from the configured URL, calls `flyway.info().current()` to prove connectivity and obtain the current version, then calls `flyway.migrate()`.

The success log reads only these public `MigrateResult` fields:

```java
result.initialSchemaVersion
result.targetSchemaVersion
result.migrationsExecuted
result.success
```

The failure branch rethrows the original exception after logging `SafeExceptionSummary.describe(exception)`; it never calls `Throwable.getMessage()`.

- [ ] **Step 4: Implement service lifecycle and per-key checks**

Register `ServiceLifecycleLogger` on the `SpringApplication` instance before `run`. Log `service_starting` after the environment is prepared, `service_start_failed` when the application fails, and preserve the existing `ApplicationReadyEvent` `service_ready` record. Change the startup configuration record from one aggregate boolean to the three individual configured booleans.

- [ ] **Step 5: Run lifecycle tests and verify GREEN**

Run the command from Step 2. Expected: pass with no sentinel secret or exception message in captured logs.

### Task 3: Close the missing business start/success/failure log loops

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/archiveimport/ImportOperationalLoggingTest.java`
- Modify: `src/test/java/com/company/personnelarchive/person/PersonOperationalLoggingTest.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ImportBatchService.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ImportHistoryService.java`
- Modify: `src/main/java/com/company/personnelarchive/person/PersonQueryService.java`

**Interfaces:**
- Consumes: current `OperationalLog.started/elapsedMillis`, `SafeExceptionSummary`, `BusinessException.code`, and fixed internal IDs.
- Produces: start/completed/failed event triplets for every previously completion-only operation.

- [ ] **Step 1: Extend the logging tests first**

Require these event triplets in success and failure cases:

```text
import_batch_started/completed/failed
import_history_started/completed/failed
person_list_started/completed/failed
family_search_started/completed/failed
person_detail_started/completed/failed
sensitive_value_started/completed/failed
photo_started/completed/failed
```

Known `BusinessException` paths log `WARN` with `code=<stable enum>`; unknown runtime paths log `ERROR` with `exceptionType` and safe locations. Add sentinels for name, search term, identity card, phone, filename, deletion reason and exception message, then assert none appears.

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./mvnw -Dtest=ImportOperationalLoggingTest,PersonOperationalLoggingTest test
```

Expected: failures for the missing start/failed events.

- [ ] **Step 3: Add minimal safe event branches**

At each public operation boundary, capture the monotonic start time, log the fixed start event, preserve the current completion event, and catch/rethrow known and unknown runtime exceptions only to add the corresponding safe failure event. Do not change return DTOs, transaction annotations, mapper calls, authorization, audit writes, or exception codes.

- [ ] **Step 4: Run focused logging tests and verify GREEN**

Run the command from Step 2. Expected: pass with complete event triplets and no sensitive sentinels.

- [ ] **Step 5: Run all operational logging tests**

Run:

```bash
./mvnw -Dtest='*OperationalLoggingTest,OperationalStartupLoggerTest,OperationalFlywayMigrationStrategyTest,GlobalExceptionHandlerLoggingTest,RequestCorrelationFilterTest' test
```

Expected: all pass.

### Task 4: Move UOS deployment to `/data` and remove database password prompting

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java`
- Modify: `deployment/uos/install.sh`
- Modify: `deployment/uos/service/personnel-archive-service.service.template`

**Interfaces:**
- Consumes: the JAR with fixed datasource credentials and existing three-key local generation.
- Produces: a root-only, offline installer that installs only under `/data` and never asks for a database password.

- [ ] **Step 1: Write failing UOS contract assertions**

Require:

```text
INSTALL_DIR="/data/personnel-archive-service"
WorkingDirectory=/data/personnel-archive-service
ExecStart=@JAVA_BIN@ -jar /data/personnel-archive-service/personnel-archive-service.jar
ReadWritePaths=/data/personnel-archive-service/log
ConditionPathIsReadWrite=/data/personnel-archive-service/log
```

Require the installer to check `/data` and reject symlinks. Assert the installer and service template do not contain `/opt/personnel-archive-service`, `PA_DB_PASSWORD`, `PA_DB_URL`, `PA_DB_USERNAME`, `read_secret`, `--reconfigure`, or the old log limit environment variables. Preserve assertions for three locally generated security keys and their root-only environment file.

- [ ] **Step 2: Run the UOS test and verify RED**

Run:

```bash
./mvnw -Dtest=UosDeploymentContractTest test
```

Expected: failures for old `/opt` paths, password prompt/reconfigure code, and old log limit variables.

- [ ] **Step 3: Simplify the installer**

Change the installation constant to `/data/personnel-archive-service`. Remove database password input, quoting, rewrite, backup and `--reconfigure` branches. The protected environment file contains only the three generated application keys; normal reinstall preserves it. Set `SPRING_PROFILES_ACTIVE=prod` directly in the service unit.

Check `/data` itself before creating the service directory, resolve Java 21 without modifying its installation, and retain checksum, account, permission, daemon reload, enable/start and bounded readiness behavior.

- [ ] **Step 4: Modify the systemd template**

Change every application path to `/data/personnel-archive-service`, keep `EnvironmentFile` for the three keys, add `Environment=SPRING_PROFILES_ACTIVE=prod`, and keep journal output plus the existing hardening settings.

- [ ] **Step 5: Run shell syntax and focused tests**

Run:

```bash
bash -n deployment/uos/install.sh
./mvnw -Dtest=UosDeploymentContractTest test
```

Expected: pass.

### Task 5: Add the standalone prod launcher and bundle it

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java`
- Create: `deployment/uos/start-prod.sh`
- Modify: `deployment/uos/build-bundle.sh`

**Interfaces:**
- Consumes: installed systemd unit `personnel-archive-service.service` and `/data` layout.
- Produces: one idempotent launcher usable from any current directory.

- [ ] **Step 1: Write failing launcher/package tests**

Assert `start-prod.sh` is executable, passes `bash -n`, is copied by the allowlist builder, appears in `SHA256SUMS`, and is present in the generated tar. Contract assertions require checks for root, systemd, `/data` JAR/log/config, unit path, Java version, `df -h /data`, log `du -sh`, `systemctl enable --now`, service active, port 18080, a 60-second bound, and safe failure diagnostics.

Assert it never contains `source`, `cat` of the environment file, `set -x`, `java -jar`, `/opt/personnel-archive-service`, database credentials, or destructive cleanup commands.

- [ ] **Step 2: Run the UOS contract and verify RED**

Run `./mvnw -Dtest=UosDeploymentContractTest test`. Expected: missing launcher and manifest entries.

- [ ] **Step 3: Implement `start-prod.sh`**

Implement nine bounded steps from the approved design. If the service is active, do not restart it. Otherwise call `systemctl enable --now personnel-archive-service.service`. Poll `systemctl is-active --quiet` and `ss -ltn` at most 30 times with two-second intervals. On success print the file log and journal commands and exit 0; on failure print status, the last 100 journal lines and the last 100 application log lines without ever reading the environment file.

- [ ] **Step 4: Add the launcher to the builder allowlist and manifest**

Install it as mode `0755`, add `./start-prod.sh` to the exact inner manifest array, and retain collision-safe output names and AppleDouble suppression.

- [ ] **Step 5: Run syntax and focused bundle tests**

Run:

```bash
bash -n deployment/uos/start-prod.sh
bash -n deployment/uos/build-bundle.sh
./mvnw -Dtest=UosDeploymentContractTest test
```

Expected: pass.

### Task 6: Synchronize deployment and code documentation

**Files:**
- Modify: `deployment/uos/docs/01-安装前检查.txt`
- Modify: `deployment/uos/docs/02-数据库授权.txt`
- Modify: `deployment/uos/docs/03-离线安装与验证.txt`
- Modify: `deployment/uos/docs/04-日常运维与日志排查.txt`
- Modify: `deployment/uos/docs/05-故障恢复.txt`
- Modify: `AGENTS.md`
- Modify: `../AGENTS.md`
- Modify: `../CODE_GUIDE.md`
- Modify: `docs/superpowers/specs/2026-08-13-data-directory-daily-logging-and-prod-launcher-design.md`

**Interfaces:**
- Consumes: the implemented file paths, fixed datasource behavior, event names, daily rotation and launcher commands.
- Produces: accurate operator instructions without printing the fixed password.

- [ ] **Step 1: Update all UOS operator documents**

Replace application `/opt` paths with `/data`; explain that MySQL/JDK may remain under `/opt`. Remove installation-time database password entry and `--reconfigure`. State that the prod JAR already contains the fixed application database credential supplied by the owner, without reproducing it. Document `start-prod.sh`, daily gzip filenames, unlimited retention, manual `df/du` checks, every business log category and the fact that logs do not themselves restore the database.

- [ ] **Step 2: Synchronize project knowledge files**

Update current status/configuration/deployment rows to say: fixed local MySQL prod datasource, `/data` service directory, daily gzip archives with no automatic deletion, explicit DB/Flyway and operation event triplets, and bundled launcher. Do not change unrelated Excel/client/database model descriptions.

- [ ] **Step 3: Run contradiction scans**

Scan active source, scripts and operational docs. Old `/opt/personnel-archive-service`, 20MB/30-day/1GB limits, `%i`, database-password prompts and `--reconfigure` must not remain outside historical design/plan documents that describe superseded behavior.

### Task 7: Full verification and new desktop release artifacts

**Files:**
- Verify: all modified source, tests, scripts and docs
- Produce: a new collision-safe desktop directory, `.tar.gz`, `.tar.gz.sha256`
- Produce: `/Users/zhongerbing/Desktop/启动prod环境.sh`
- Produce: `/Users/zhongerbing/Desktop/启动prod环境.sh.sha256`

**Interfaces:**
- Consumes: freshly verified Maven executable JAR and the updated allowlisted UOS payload.
- Produces: final single-file and full-bundle offline deliverables.

- [ ] **Step 1: Run focused Excel regression**

Run:

```bash
./mvnw -Dtest=ExcelNumericDatePrecisionTest test
```

Expected: pass without modifying the user's two Excel files.

- [ ] **Step 2: Run full Java 21 verification**

Run `./mvnw verify`. Expected: all unit tests pass; Docker-backed MySQL tests run when Docker is reachable and any skip is reported honestly.

- [ ] **Step 3: Inspect the executable JAR safely**

Confirm the manifest, `application-prod.yml`, `logback-spring.xml`, template and Flyway V1–V14 entries exist. Confirm the prod YAML in the JAR has a nonblank fixed password without displaying it. The builder must still remove only `application-dev.yml` from its temporary production copy.

- [ ] **Step 4: Build a new collision-safe desktop bundle**

Run:

```bash
./deployment/uos/build-bundle.sh target/personnel-archive-service-0.1.0-SNAPSHOT.jar /Users/zhongerbing/Desktop 20260813
```

Do not overwrite or delete earlier desktop artifacts. Capture the newly printed output paths.

- [ ] **Step 5: Create the standalone desktop launcher and checksum**

Copy the verified `deployment/uos/start-prod.sh` to `/Users/zhongerbing/Desktop/启动prod环境.sh`, mode `0755`, then produce `/Users/zhongerbing/Desktop/启动prod环境.sh.sha256`. Verify the checksum from the desktop directory.

- [ ] **Step 6: Verify all manifests and archive metadata**

Check the inner `SHA256SUMS`, outer `.tar.gz.sha256`, standalone launcher checksum and tar listing. Reject `._`, `__MACOSX`, missing `start-prod.sh`, old `/opt` paths, old log limits, `%i`, database environment variables or password prompts.

- [ ] **Step 7: Run a non-printing credential equality check**

Compare the exact password bytes in the source YAML, Maven JAR, sanitized bundle JAR and original attachment in memory. Print only `credentialMatch=true` or `credentialMatch=false`; never print either value, length, hash or encoded form.

- [ ] **Step 8: Confirm preserved user changes and final Git state**

Verify `ExcelArchiveReader.java`, `ExcelNumericDatePrecisionTest.java` and `.codex/` retain their original status. Do not commit, push, merge, reset, clean or checkout implementation changes. Report that the final local source/JAR contains the fixed production credential by explicit user instruction and must not be uploaded publicly.
