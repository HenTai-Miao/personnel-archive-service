# Self-Contained Prod Security and Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the fixed prod JAR contain its three stable cryptographic values and allow a clean UOS server to register and start the service with only that JAR plus `启动prod环境.sh`.

**Architecture:** The prod profile overrides the common development-facing placeholders with three strong literals. The launcher becomes a root-only bootstrapper that validates Java 21 and fixed paths, creates the service account/log directory, renders the canonical systemd unit itself, and starts or restarts that unit without an external environment file.

**Tech Stack:** Java 21, Spring Boot 4, Logback, Bash, systemd, JUnit 5, AssertJ.

## Global Constraints

- Keep the fixed MySQL URL, account, password, port `18080`, Flyway V1–V14 and daily plaintext log policy unchanged.
- Never print the database password or the three generated cryptographic values.
- Generate the three prod values once; never regenerate them after production data has been written.
- The final service must not require `/etc/personnel-archive-service/personnel-archive-service.env`.
- Do not commit or push `application-prod.yml` because it contains fixed production secrets.
- Do not connect the build machine to the offline production database.

---

### Task 1: Fixed prod security values

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java`
- Modify: `src/main/resources/application-prod.yml`

**Interfaces:**
- Consumes: Spring profile property merging from `application.yml` and `application-prod.yml`.
- Produces: valid literal values for `personnel-archive.security.token-pepper`, `identity-hmac-key`, and `identity-encryption-key`.

- [ ] **Step 1: Write the failing test**

Add a prod configuration test that resolves all three properties without environment variables, asserts the first two contain at least 32 characters, Base64-decodes the encryption value to exactly 32 bytes, and asserts the prod YAML does not contain the three matching `${PA_...}` expressions. This catches deleting a literal, restoring an environment-only placeholder, or using an invalid AES key.

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
export JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -q -Dtest=ProductionProfileConfigurationTest test
```

Expected: FAIL because the current merged prod values resolve to empty strings when the environment is absent.

- [ ] **Step 3: Generate and install the values without displaying them**

Generate two independent 32-byte-or-longer random values and one independent 32-byte AES value. Use `apply_patch` to add literal prod overrides, but never send any value to terminal output, test output, documentation or logs.

- [ ] **Step 4: Run the focused test to verify it passes**

Run the command from Step 2. Expected: all `ProductionProfileConfigurationTest` tests pass.

### Task 2: Two-file first startup

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java`
- Modify: `deployment/uos/start-prod.sh`
- Modify: `deployment/uos/install.sh`
- Modify: `deployment/uos/service/personnel-archive-service.service.template`
- Modify: `deployment/uos/docs/01-安装前检查.txt`
- Modify: `deployment/uos/docs/03-离线安装与验证.txt`
- Modify: `deployment/uos/docs/04-日常运维与日志排查.txt`
- Modify: `deployment/uos/docs/05-故障恢复.txt`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `../AGENTS.md`
- Modify: `../CODE_GUIDE.md`

**Interfaces:**
- Consumes: fixed JAR path `/data/personnel-archive-service/personnel-archive-service.jar` and a root shell on a running systemd host.
- Produces: `render_service_unit OUTPUT JAVA_BIN`, a canonical service unit with no `EnvironmentFile`, and an idempotent first-start flow.

- [ ] **Step 1: Change the UOS tests first**

Require the launcher to create/check the service account and log directory, detect Java 21, render the unit when it is absent, reload systemd, and start/restart the service. Require both launcher-rendered and packaged units to contain the fixed prod profile and JVM timezone and to omit `EnvironmentFile` and all `PA_*` names. Add a behavior test that sources the guarded script, calls `render_service_unit` into a temporary file, and checks the resulting unit.

- [ ] **Step 2: Run the UOS test to verify it fails**

Run:

```bash
export JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -q -Dtest=UosDeploymentContractTest test
```

Expected: FAIL because the current launcher requires an already installed unit and root-only environment file.

- [ ] **Step 3: Implement the minimum bootstrap behavior**

Add focused shell functions for Java detection, account/directory preparation, unit rendering and installation. Preserve bounded diagnostics, symlink rejection, fixed `/data` paths and current daily log lookup. Update the full installer and unit template to the same no-environment-file contract; remove generation and documentation of unused runtime keys.

- [ ] **Step 4: Run focused tests and shell syntax checks**

```bash
export JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -q -Dtest=ProductionProfileConfigurationTest,UosDeploymentContractTest test
bash -n deployment/uos/install.sh deployment/uos/start-prod.sh deployment/uos/build-bundle.sh
```

Expected: all focused tests and Bash syntax checks pass.

### Task 3: Full verification and desktop delivery

**Files:**
- Rebuild: `target/personnel-archive-service-0.1.0-SNAPSHOT.jar`
- Replace generated desktop artifacts for date `20260813` after moving the preceding version into a clearly marked recoverable old-version directory.

**Interfaces:**
- Consumes: Java 21 verified source and deployment payload.
- Produces: verified directory bundle, `.tar.gz` transfer bundle, checksums and standalone `启动prod环境.sh`.

- [ ] **Step 1: Run the complete build**

```bash
export JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw verify
```

Expected: zero failures and zero errors; report Docker-dependent skips accurately.

- [ ] **Step 2: Rebuild desktop artifacts**

Use `deployment/uos/build-bundle.sh` with the verified JAR and date `20260813`, then copy the verified `start-prod.sh` to the desktop as `启动prod环境.sh` and regenerate its checksum.

- [ ] **Step 3: Inspect the final JAR without printing secrets**

Check only booleans and lengths: prod contains the fixed database properties, three valid security values and `log.%d{yyyy-MM-dd}.log`; it contains no matching `${PA_...}` placeholders, old compressed log policy or `application-dev.yml`.

- [ ] **Step 4: Verify every checksum**

Run the inner `SHA256SUMS`, outer archive checksum and launcher checksum checks. Confirm the bundle launcher equals the desktop launcher and no macOS AppleDouble metadata is present.
