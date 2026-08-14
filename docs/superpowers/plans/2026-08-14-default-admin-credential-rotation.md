# Default Administrator Credential Rotation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve immutable Flyway V8 and add a tested MySQL V15 migration that deterministically rotates the fixed default administrator to `hr_dept`, revokes active sessions, and leaves only the `ADMIN` role without storing plaintext credentials.

**Architecture:** Keep the committed V8 byte-for-byte unchanged and implement the credential change as one forward-only SQL migration. Use the existing unique and non-null database constraints to reject zero-user and dual-user corruption, add focused real-MySQL migration coverage, then update the UOS bundle contract and documentation from V1–V14 to V1–V15.

**Tech Stack:** Java 21, Spring Boot 4, Flyway MySQL, MySQL 8.4.3, JUnit 5, AssertJ, Spring JDBC, Testcontainers, Bash, Maven Wrapper.

## Global Constraints

- Never write the administrator plaintext password to SQL, Java, YAML, Markdown, logs, test output, commit messages, or shell output.
- Preserve `src/main/resources/db/migration/V8__seed_default_admin.sql` exactly as committed; V15 is the only credential-rotation migration.
- Preserve the existing `sys_user.id`; never delete or recreate the default administrator.
- Support exactly one matching user in either the original V8 username state or the target `hr_dept` state; reject a database containing both.
- The final account is enabled, unlocked, has zero failed logins, uses the verified BCrypt cost 12 hash, and has only one `ADMIN` role association.
- Revoke only sessions whose `revoked_at` is null; preserve prior revoke timestamps and reasons.
- Do not change the desktop client, REST API, OpenAPI contract, business archive data, `AdminBootstrap`, or production configuration.
- Never stage or push the existing local `src/main/resources/application-prod.yml` modification, `.codex/`, generated JARs, or unrelated files.
- Real migration evidence must use Testcontainers MySQL, never the user's `local-mysql` database.
- Minimum final verification is `./mvnw verify`; report whether Testcontainers actually ran.
- Push only the backend repository's current tracked branch, `origin/chore/prod-mysql-only`.

---

## File Structure

- `src/main/resources/db/migration/V8__seed_default_admin.sql`: immutable historical seed; restore its committed content.
- `src/main/resources/db/migration/V15__rotate_default_admin_credentials.sql`: new forward-only account, session, and role migration.
- `src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java`: static V8 immutability and V15 security contract.
- `src/test/java/com/company/personnelarchive/configuration/DefaultAdminCredentialRotationMigrationIntegrationTest.java`: isolated real-MySQL V14→V15 success and conflict scenarios.
- `src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java`: full V1→V15 final-state assertion.
- `src/test/java/com/company/personnelarchive/configuration/CanonicalValueMigrationIntegrationTest.java`: keep its V12 fixture on the historical V8 username.
- `src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java`: require V15 in fake/packaged JARs and V1–V15 build metadata.
- `deployment/uos/build-bundle.sh`: validate the V15 SQL entry and emit `FlywayMigrations=V1-V15`.
- `deployment/uos/docs/02-数据库授权.txt`: update required migration range to V1–V15.
- `deployment/uos/docs/03-离线安装与验证.txt`: validate successful version 15.
- `AGENTS.md` and `README.md`: record the immutable V8 plus V15 credential-rotation behavior.
- `../AGENTS.md` and `../CODE_GUIDE.md`: synchronize cross-project source-of-truth documentation locally; these files are outside the backend Git worktree.

---

### Task 1: Lock the V15 Contract with Failing Tests

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java`
- Create: `src/test/java/com/company/personnelarchive/configuration/DefaultAdminCredentialRotationMigrationIntegrationTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/CanonicalValueMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: committed V8 SQL, Flyway target version `14`, MySQL `sys_user`, `sys_user_session`, `sys_user_role`, and `sys_role` tables.
- Produces: package-visible `INITIAL_PASSWORD_HASH` and `ROTATED_PASSWORD_HASH` constants plus red tests defining V15's exact final state and failure behavior.

- [ ] **Step 1: Rewrite the static migration contract around immutable V8 and missing V15**

Use two explicit paths and two hash constants. The V8 test must expect the original account and original hash; the V15 test must expect both source/target identifiers, target hash, session revocation, and non-ADMIN role removal:

```java
class DefaultAdminMigrationContractTest {
    static final String INITIAL_PASSWORD_HASH =
        "$2a$12$ZeUKR0IcwyMU1Wye4d6T9Oc/NG3ZRF3a69JSHnnXiPrHuLqxI/nBi";
    static final String ROTATED_PASSWORD_HASH =
        "$2a$12$5VEWfSi2yfCd/o4my8fb.ObFiWLe6b4EzIs5ZYPCXM7oTN6dF8VH.";
    private static final Path V8 = Path.of(
        "src/main/resources/db/migration/V8__seed_default_admin.sql");
    private static final Path V15 = Path.of(
        "src/main/resources/db/migration/V15__rotate_default_admin_credentials.sql");

    @Test
    void keepsThePublishedV8DefaultAdministratorContract() throws IOException {
        String sql = Files.readString(V8);
        assertThat(sql)
            .contains("'hr_6298bc833379'", "'人资部门'", "'" + INITIAL_PASSWORD_HASH + "'")
            .contains("ON DUPLICATE KEY UPDATE", "failed_login_count = 0",
                "locked_until = NULL", "role_code = 'ADMIN'")
            .doesNotContain("'hr_dept'");
        assertBcryptCost12(INITIAL_PASSWORD_HASH);
    }

    @Test
    void rotatesTheDefaultAdministratorInV15WithoutPlaintextCredentials() throws IOException {
        String sql = Files.readString(V15);
        assertThat(sql)
            .contains("normalized_username IN ('hr_6298bc833379', 'hr_dept')")
            .contains("username = 'hr_dept'", "normalized_username = 'hr_dept'")
            .contains("'" + ROTATED_PASSWORD_HASH + "'", "display_name = '人资部门'")
            .contains("is_enabled = 1", "failed_login_count = 0", "locked_until = NULL")
            .contains("UPDATE sys_user_session", "revoked_at IS NULL")
            .contains("DEFAULT_ADMIN_CREDENTIAL_ROTATION")
            .contains("role_code = 'ADMIN'", "role_code <> 'ADMIN'")
            .doesNotContain("DELETE FROM sys_user\n");
        assertBcryptCost12(ROTATED_PASSWORD_HASH);
    }

    private static void assertBcryptCost12(String hash) {
        assertThat(hash).startsWith("$2a$12$").hasSize(60);
    }
}
```

- [ ] **Step 2: Add a focused real-MySQL migration test class**

Create a Testcontainers class with a clean V14 database before every test. Add helpers `flyway(String target)`, `defaultAdminId(String normalizedUsername)`, `addViewerRole(long userId)`, and `addSession(long userId, String tokenSeed, boolean revoked)`.

The standard-state test must:

```java
@Test
void rotatesTheV8AdministratorInPlaceAndRevokesOnlyActiveSessions() {
    long originalId = defaultAdminId("hr_6298bc833379");
    jdbc.update("""
        UPDATE sys_user
        SET password_hash='outdated-test-hash',is_enabled=0,failed_login_count=4,
            locked_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY)
        WHERE id=?
        """, originalId);
    addViewerRole(originalId);
    addSession(originalId, "active-session", false);
    addSession(originalId, "already-revoked-session", true);

    flyway(null).migrate();

    assertFinalAdministrator(originalId);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM sys_user
        WHERE tenant_id=1 AND normalized_username='hr_6298bc833379'
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForMap("""
        SELECT revoked_at,revoke_reason,revoked_by FROM sys_user_session
        WHERE refresh_token_hash=UNHEX(SHA2('active-session',256))
        """))
        .containsEntry("revoke_reason", "DEFAULT_ADMIN_CREDENTIAL_ROTATION")
        .containsEntry("revoked_by", originalId);
    assertThat(jdbc.queryForMap("""
        SELECT revoke_reason FROM sys_user_session
        WHERE refresh_token_hash=UNHEX(SHA2('already-revoked-session',256))
        """))
        .containsEntry("revoke_reason", "PREVIOUS_TEST_REVOCATION");
}
```

The manual-rename test must update the V14 user to `hr_dept`, dirty its hash/lock/roles, insert an active session, run V15, and call the same `assertFinalAdministrator(originalId)`.

The conflict test must insert a second `hr_dept` user at V14, capture both IDs, assert `flyway(null).migrate()` throws `FlywayException`, then assert both IDs and normalized usernames remain distinct and neither user was deleted. A separate missing-user test must remove the V8 user's role rows and user row, assert V15 throws `FlywayException`, and verify that no replacement user was silently created.

Use these fixture helpers so every test is isolated and every token remains synthetic:

```java
@BeforeEach
void resetDatabaseAtV14() {
    Flyway latest = flyway(null);
    latest.clean();
    flyway("14").migrate();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
}

private long defaultAdminId(String normalizedUsername) {
    return jdbc.queryForObject(
        "SELECT id FROM sys_user WHERE tenant_id=1 AND normalized_username=?",
        Long.class, normalizedUsername);
}

private void addViewerRole(long userId) {
    jdbc.update("""
        INSERT INTO sys_user_role (tenant_id,user_id,role_id)
        SELECT 1,?,id FROM sys_role WHERE tenant_id=1 AND role_code='VIEWER'
        """, userId);
}

private void addSession(long userId, String tokenSeed, boolean revoked) {
    int revokedFlag = revoked ? 1 : 0;
    jdbc.update("""
        INSERT INTO sys_user_session
          (tenant_id,user_id,refresh_token_hash,issued_at,access_expires_at,
           refresh_expires_at,revoked_at,revoked_by,revoke_reason)
        VALUES
          (1,?,UNHEX(SHA2(?,256)),CURRENT_TIMESTAMP(6),
           DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 HOUR),
           DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1 DAY),
           CASE WHEN ?=1 THEN CURRENT_TIMESTAMP(6) ELSE NULL END,
           CASE WHEN ?=1 THEN ? ELSE NULL END,
           CASE WHEN ?=1 THEN 'PREVIOUS_TEST_REVOCATION' ELSE NULL END)
        """, userId, tokenSeed, revokedFlag, revokedFlag, userId, revokedFlag);
}

private Flyway flyway(String target) {
    var configuration = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .cleanDisabled(false);
    if (target != null) configuration.target(target);
    return configuration.load();
}
```

Use this exact failure shape for the two corruption tests; do not assert the database exception message because it can echo a username:

```java
@Test
void rejectsASecondTargetAccountWithoutMergingUsers() {
    long originalId = defaultAdminId("hr_6298bc833379");
    jdbc.update("""
        INSERT INTO sys_user
          (tenant_id,username,normalized_username,password_hash,display_name)
        VALUES (1,'hr_dept','hr_dept','conflict-test-hash','冲突测试账号')
        """);
    long conflictingId = defaultAdminId("hr_dept");

    assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(FlywayException.class);

    assertThat(jdbc.queryForObject("SELECT normalized_username FROM sys_user WHERE id=?",
        String.class, originalId)).isEqualTo("hr_6298bc833379");
    assertThat(jdbc.queryForObject("SELECT normalized_username FROM sys_user WHERE id=?",
        String.class, conflictingId)).isEqualTo("hr_dept");
}

@Test
void rejectsAStandardDatabaseWhoseDefaultAdministratorIsMissing() {
    long originalId = defaultAdminId("hr_6298bc833379");
    jdbc.update("DELETE FROM sys_user_role WHERE tenant_id=1 AND user_id=?", originalId);
    jdbc.update("DELETE FROM sys_user WHERE tenant_id=1 AND id=?", originalId);

    assertThatThrownBy(() -> flyway(null).migrate()).isInstanceOf(FlywayException.class);

    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM sys_user
        WHERE tenant_id=1 AND normalized_username IN ('hr_6298bc833379','hr_dept')
        """, Integer.class)).isZero();
}
```

`assertFinalAdministrator(long expectedId)` must verify:

```java
assertThat(jdbc.queryForMap("""
    SELECT id,username,normalized_username,password_hash,display_name,
           is_enabled + 0 AS is_enabled,failed_login_count,locked_until
    FROM sys_user WHERE tenant_id=1 AND normalized_username='hr_dept'
    """))
    .containsEntry("id", expectedId)
    .containsEntry("username", "hr_dept")
    .containsEntry("normalized_username", "hr_dept")
    .containsEntry("password_hash", DefaultAdminMigrationContractTest.ROTATED_PASSWORD_HASH)
    .containsEntry("display_name", "人资部门")
    .containsEntry("is_enabled", 1)
    .containsEntry("failed_login_count", 0)
    .containsEntry("locked_until", null);
assertThat(jdbc.queryForList("""
    SELECT r.role_code FROM sys_user_role ur
    JOIN sys_role r ON r.id=ur.role_id AND r.tenant_id=ur.tenant_id
    WHERE ur.tenant_id=1 AND ur.user_id=? ORDER BY r.role_code
    """, String.class, expectedId)).containsExactly("ADMIN");
```

- [ ] **Step 3: Correct staged-version and final-version expectations in existing integration tests**

In `CanonicalValueMigrationIntegrationTest.defaultAdminId()`, query the V8 account because each test starts at V12:

```java
return jdbc.queryForObject(
    "SELECT id FROM sys_user WHERE normalized_username='hr_6298bc833379'", Long.class);
```

In `MySqlMigrationIntegrationTest`, the first administrator assertion runs while Flyway is intentionally stopped at V9. Restore that assertion to the original V8 username and `INITIAL_PASSWORD_HASH`, include `id` in the query, and save it as `long initialDefaultAdminId`. After the existing call that migrates to the latest version, add a second assertion that the same ID now has username `hr_dept`, `ROTATED_PASSWORD_HASH`, enabled/unlocked state, and one `ADMIN` role. Then assert:

```java
assertThat(Flyway.configure()
    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
    .load().info().current().getVersion().toString()).isEqualTo("15");
```

- [ ] **Step 4: Run the focused tests and verify the expected red state**

Run:

```bash
./mvnw -Dtest=DefaultAdminMigrationContractTest,DefaultAdminCredentialRotationMigrationIntegrationTest,MySqlMigrationIntegrationTest,CanonicalValueMigrationIntegrationTest test
```

Expected: FAIL because V8 is currently modified, V15 does not exist, and real MySQL cannot migrate to version 15. If Docker is unavailable, the static contract must still fail on the missing V15 path; note the integration-test skip separately.

---

### Task 2: Restore V8 and Implement the Minimal V15 Migration

**Files:**
- Modify: `src/main/resources/db/migration/V8__seed_default_admin.sql`
- Create: `src/main/resources/db/migration/V15__rotate_default_admin_credentials.sql`
- Test: `src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java`
- Test: `src/test/java/com/company/personnelarchive/configuration/DefaultAdminCredentialRotationMigrationIntegrationTest.java`
- Test: `src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java`
- Test: `src/test/java/com/company/personnelarchive/configuration/CanonicalValueMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: V8-created default tenant/user/roles and the tests from Task 1.
- Produces: immutable V8 plus `V15__rotate_default_admin_credentials.sql`, with no application-code or API changes.

- [ ] **Step 1: Restore V8 exactly to its committed bytes**

Apply the reverse of the current username/hash diff so `git diff -- src/main/resources/db/migration/V8__seed_default_admin.sql` becomes empty. Do not use a destructive checkout because the working tree contains user changes.

- [ ] **Step 2: Add the minimal V15 SQL migration**

Create:

```sql
-- Rotate the fixed administrator without retaining its plaintext password or changing its user id.
UPDATE sys_user
SET username = 'hr_dept',
    normalized_username = 'hr_dept',
    password_hash = '$2a$12$5VEWfSi2yfCd/o4my8fb.ObFiWLe6b4EzIs5ZYPCXM7oTN6dF8VH.',
    display_name = '人资部门',
    is_enabled = 1,
    failed_login_count = 0,
    locked_until = NULL,
    password_changed_at = CURRENT_TIMESTAMP(6),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
  AND normalized_username IN ('hr_6298bc833379', 'hr_dept');

UPDATE sys_user_session user_session
JOIN sys_user target
  ON target.id = user_session.user_id
 AND target.tenant_id = user_session.tenant_id
SET user_session.revoked_at = CURRENT_TIMESTAMP(6),
    user_session.revoked_by = target.id,
    user_session.revoke_reason = 'DEFAULT_ADMIN_CREDENTIAL_ROTATION'
WHERE target.tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
  AND target.normalized_username = 'hr_dept'
  AND user_session.revoked_at IS NULL;

INSERT INTO sys_user_role (tenant_id, user_id, role_id)
VALUES (
    (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT'),
    (SELECT id FROM sys_user
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND normalized_username = 'hr_dept'),
    (SELECT id FROM sys_role
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND role_code = 'ADMIN')
)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);

DELETE user_role
FROM sys_user_role user_role
JOIN sys_user target
  ON target.id = user_role.user_id
 AND target.tenant_id = user_role.tenant_id
JOIN sys_role assigned_role
  ON assigned_role.id = user_role.role_id
 AND assigned_role.tenant_id = user_role.tenant_id
WHERE target.tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
  AND target.normalized_username = 'hr_dept'
  AND assigned_role.role_code <> 'ADMIN';
```

The first statement must remain a single update so a dual-account unique-key conflict is statement-atomic. The scalar subqueries in the role insert must remain `VALUES` expressions so an absent target user or absent ADMIN role fails on non-null columns instead of silently inserting zero rows.

- [ ] **Step 3: Run the focused migration tests and verify green**

Run:

```bash
./mvnw -Dtest=DefaultAdminMigrationContractTest,DefaultAdminCredentialRotationMigrationIntegrationTest,MySqlMigrationIntegrationTest,CanonicalValueMigrationIntegrationTest test
```

Expected: all selected tests pass. The Testcontainers output must show the MySQL 8.4.3 container started and the new integration tests executed; if Docker is unavailable, do not treat skipped integration tests as evidence.

- [ ] **Step 4: Commit the migration and tests with explicit paths**

```bash
git add -- \
  src/main/resources/db/migration/V8__seed_default_admin.sql \
  src/main/resources/db/migration/V15__rotate_default_admin_credentials.sql \
  src/test/java/com/company/personnelarchive/configuration/DefaultAdminMigrationContractTest.java \
  src/test/java/com/company/personnelarchive/configuration/DefaultAdminCredentialRotationMigrationIntegrationTest.java \
  src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java \
  src/test/java/com/company/personnelarchive/configuration/CanonicalValueMigrationIntegrationTest.java
git diff --cached --check
git commit -m "feat(service): rotate default administrator credentials"
```

`V8__seed_default_admin.sql` should not appear in the staged diff after restoration; if it does, stop and compare it with `git show HEAD^:src/main/resources/db/migration/V8__seed_default_admin.sql` before committing.

---

### Task 3: Update the UOS V1–V15 Deployment Contract

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java`
- Modify: `deployment/uos/build-bundle.sh`
- Modify: `deployment/uos/docs/02-数据库授权.txt`
- Modify: `deployment/uos/docs/03-离线安装与验证.txt`

**Interfaces:**
- Consumes: packaged V15 SQL path `BOOT-INF/classes/db/migration/V15__rotate_default_admin_credentials.sql`.
- Produces: builder validation and offline documentation that identify V15 as the final Flyway version.

- [ ] **Step 1: Make the deployment contract expect V15**

In `UosDeploymentContractTest`:

- require the builder source to contain the V15 JAR entry and `FlywayMigrations=V1-V15`;
- add the V15 SQL entry to `writeFakeExecutableJar`;
- assert the sanitized bundle JAR contains V15;
- read `BUILD-INFO.txt` and assert it contains `FlywayMigrations=V1-V15`.

The core assertions are:

```java
assertThat(text).contains(
    "BOOT-INF/classes/db/migration/V15__rotate_default_admin_credentials.sql",
    "FlywayMigrations=V1-V15");

assertThat(jar.getEntry(
    "BOOT-INF/classes/db/migration/V15__rotate_default_admin_credentials.sql")).isNotNull();

assertThat(Files.readString(directory.resolve("BUILD-INFO.txt"), StandardCharsets.UTF_8))
    .contains("FlywayMigrations=V1-V15");
```

- [ ] **Step 2: Run the deployment contract and verify red**

Run:

```bash
./mvnw -Dtest=UosDeploymentContractTest test
```

Expected: FAIL because the builder still validates V14 only and emits V1–V14 metadata.

- [ ] **Step 3: Update the builder and operator documents**

In `build-bundle.sh`, retain the V1 and V14 checks and add:

```bash
"BOOT-INF/classes/db/migration/V15__rotate_default_admin_credentials.sql"
```

Change only the build metadata line to:

```bash
printf 'FlywayMigrations=V1-V15\n'
```

In `02-数据库授权.txt`, replace both V1—V14 references with V1—V15. In `03-离线安装与验证.txt`, change the heading, query, success statement, and initial-migration range from 14 to 15. Add one sentence that version 15 rotates the fixed administrator credentials and revokes old sessions without displaying any credential value.

- [ ] **Step 4: Run the deployment contract and Bash syntax verification**

Run:

```bash
./mvnw -Dtest=UosDeploymentContractTest test
/bin/bash -n deployment/uos/build-bundle.sh
```

Expected: both commands exit 0; the fake-JAR packaging test verifies V15 survives sanitization and `BUILD-INFO.txt` reports V1–V15.

- [ ] **Step 5: Commit the deployment contract with explicit paths**

```bash
git add -- \
  src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java \
  deployment/uos/build-bundle.sh \
  deployment/uos/docs/02-数据库授权.txt \
  deployment/uos/docs/03-离线安装与验证.txt
git diff --cached --check
git commit -m "chore(deployment): require Flyway V15"
```

---

### Task 4: Synchronize Repository and Cross-Project Documentation

**Files:**
- Modify: `AGENTS.md`
- Modify: `README.md`
- Modify: `../AGENTS.md`
- Modify: `../CODE_GUIDE.md`

**Interfaces:**
- Consumes: implemented and verified V15 behavior from Tasks 1–3.
- Produces: accurate current-status, module-map, invariant, deployment, and verification documentation with no plaintext password.

- [ ] **Step 1: Update backend-owned documentation**

Make these exact semantic updates:

- migration range `V1–V14` → `V1–V15`;
- describe V8 as the immutable historical initial administrator seed;
- describe V15 as reusing the user ID, accepting the old or already-renamed target account, setting `hr_dept` and the verified cost-12 hash, enabling/unlocking/resetting it, revoking active sessions, and leaving only `ADMIN`;
- update default-admin test coverage to include V15 and real MySQL success/conflict paths;
- update UOS JAR checks from `V1/V14` to `V1/V14/V15`.

Do not rewrite historical design or implementation plan documents that intentionally describe their earlier point in time.

- [ ] **Step 2: Update the root project knowledge base and code guide locally**

In `../AGENTS.md`, set `Updated` to `2026-08-14`, set the state and migration status to V1–V15, add V15 behavior after V14, change target-host verification from V14 to V15, and keep the plaintext prohibition.

In `../CODE_GUIDE.md`:

- change operational Flyway, configuration tests, invariants, UOS and roadmap references to V1–V15/version 15;
- add a module-table row for `db/migration/V15__rotate_default_admin_credentials.sql`;
- update the V8 row to call it immutable historical state;
- add the focused migration integration test to the configuration-test description;
- describe session revocation and sole-ADMIN normalization.

These parent files are not inside any Git repository. Modify them for local consistency, but do not initialize a new repository or attempt to add them to the backend commit.

- [ ] **Step 3: Verify documentation consistency and secret boundaries**

Run repository-safe searches:

```bash
rg -n "V1–V14|V1-V14|version 14|V1/V14" \
  AGENTS.md README.md deployment/uos src/test/java/com/company/personnelarchive/configuration
rg -n "V1–V14|V1-V14|version 14|V1/V14" ../AGENTS.md ../CODE_GUIDE.md
```

Expected: no current-status or active deployment statements remain on V14; hits are allowed only in historical dated plan/spec files not included in these search paths. Review the complete diff manually to confirm no plaintext credential or production configuration entered documentation.

- [ ] **Step 4: Commit backend documentation only**

```bash
git add -- AGENTS.md README.md
git diff --cached --check
git commit -m "docs(service): document administrator rotation"
```

Confirm `../AGENTS.md` and `../CODE_GUIDE.md` remain local, outside the backend Git status by design.

---

### Task 5: Full Verification, Scope Audit, Commit Audit, and Push

**Files:**
- Verify: every file committed in Tasks 1–4
- Exclude: `src/main/resources/application-prod.yml`
- Exclude: `.codex/`

**Interfaces:**
- Consumes: all implementation commits and the earlier design/plan commits.
- Produces: fresh Maven, migration, packaging, Git-scope, and remote-push evidence.

- [ ] **Step 1: Run the full required verification**

Run:

```bash
./mvnw verify
```

Expected: `BUILD SUCCESS`, no test failures, and Testcontainers logs show MySQL migration tests actually started. Inspect Surefire/Failsafe reports for the focused migration class and confirm tests were not skipped.

- [ ] **Step 2: Verify the executable JAR contains immutable V8 and V15**

Run:

```bash
jar tf target/personnel-archive-service-0.1.0-SNAPSHOT.jar | \
  rg "BOOT-INF/classes/db/migration/(V8__seed_default_admin|V15__rotate_default_admin_credentials)\.sql"
```

Expected: exactly the V8 and V15 SQL entries are present. Do not print `application-prod.yml` or any configuration value from the JAR.

- [ ] **Step 3: Audit the working tree and commits**

Run:

```bash
git status -sb
git diff -- src/main/resources/application-prod.yml
git diff --cached --name-status
git log --oneline --decorate -6
git diff --stat 77defea..HEAD
git diff --name-status 77defea..HEAD
```

Expected:

- `application-prod.yml` is still modified only in the working tree and absent from every new commit;
- `.codex/` remains untracked and absent from every new commit;
- no staged changes remain;
- implementation commits contain only migration, tests, UOS contract/docs, backend docs, design, and plan;
- the restored V8 has no content diff from commit `77defea`.

- [ ] **Step 4: Verify remote state immediately before pushing**

Run:

```bash
git fetch origin chore/prod-mysql-only
git status -sb
git log --oneline --left-right --cherry-pick origin/chore/prod-mysql-only...HEAD
```

Expected: the local branch is only ahead by this task's commits and is not behind. If it is behind or diverged, stop and reconcile without force-pushing.

- [ ] **Step 5: Push the tracked branch and verify the remote commit**

Run:

```bash
git push origin chore/prod-mysql-only
git ls-remote --heads origin chore/prod-mysql-only
git rev-parse HEAD
```

Expected: push succeeds and the remote branch hash equals local `HEAD`. Do not create a pull request because the user requested commit and push only.

- [ ] **Step 6: Deliver the final evidence**

Report:

- V15 behavior and why V8 stayed immutable;
- files and commits pushed;
- exact `./mvnw verify` outcome and whether real MySQL tests ran;
- remote branch and final commit hash;
- confirmation that `application-prod.yml`, `.codex/`, plaintext credentials, desktop changes, and parent unversioned docs were not pushed;
- local paths to the design, implementation plan, migration, and updated cross-project documentation.
