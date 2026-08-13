# UOS Offline Production Bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a secret-free, checksum-verified UOS offline deployment directory and tarball that installs the verified Java 21 service under systemd and keeps all application/install logs beside the JAR.

**Architecture:** Versioned source templates under `deployment/uos` define one root-run installer, one hardened systemd unit, one manual MySQL handoff script, and operator documentation. A non-root build script copies the verified executable JAR and static payload into a new Desktop directory, writes compatible SHA-256 manifests, creates a tarball without overwriting existing output, and never creates a production secret.

**Tech Stack:** Bash, GNU coreutils on UOS (`sha256sum`, `install`, `getent`, `useradd`, `systemctl`), OpenSSL, systemd, JDK 21, MySQL 8, Maven/Spring Boot executable JAR.

## Global Constraints

- The target computer is offline and unreachable; local implementation must never probe or connect to it.
- The target already has JDK 21 and MySQL 8; the bundle does not download or install either product.
- Production database defaults remain `127.0.0.1:3306/personnel_archive` with account `personnel_archive@127.0.0.1` and `connectionTimeZone=Asia/Shanghai`.
- The exposed database password from the attachment is revoked on the target and must never appear in source, tests, logs, bundles, checksums, terminal output, or documentation.
- The installer never accepts or handles the MySQL root password and never runs privileged SQL automatically.
- The application database password is entered with terminal echo disabled; token pepper, identity HMAC key, and 32-byte AES key are generated only on the target with OpenSSL.
- `/etc/personnel-archive-service/personnel-archive-service.env` is root-owned mode 0600; it is never printed, sourced into an interactive shell, copied into the bundle, or included in diagnostics.
- The JAR is `/opt/personnel-archive-service/personnel-archive-service.jar`; both application and installation logs are under `/opt/personnel-archive-service/log/`.
- Run the service as the non-login system account/group `personnel-archive`, never root.
- Reinstallation preserves the existing environment file and logs by default. `--reconfigure` replaces only the database password; it must preserve the token pepper, identity HMAC key, and identity AES key because those keys are bound to existing sessions/indexes/ciphertext.
- The bundle does not rotate HMAC/AES keys. Such a rotation requires a separately designed transactional data migration and is outside this deployment workflow.
- Never automatically roll back an old JAR after Flyway may have advanced the schema.
- Do not delete the database, environment file, logs, or an existing Desktop bundle.
- Preserve and verify the user-owned Excel numeric-date changes already present in the working tree.
- Build only after the operational-logging plan passes Java 21 `./mvnw verify`.
- Update service/root current-state documents only after the bundle and checksum verification succeed.

---

## File Map

Create:

- `deployment/uos/install.sh` — root-only idempotent first-install/upgrade/reconfigure entrypoint.
- `deployment/uos/build-bundle.sh` — non-root, network-free bundle assembler.
- `deployment/uos/service/personnel-archive-service.service.template` — systemd template with `@JAVA_BIN@` substitution.
- `deployment/uos/mysql/01-轮换应用密码并授权.sql` — manual MySQL 8 statements without credentials.
- `deployment/uos/docs/01-安装前检查.txt`.
- `deployment/uos/docs/02-数据库授权.txt`.
- `deployment/uos/docs/03-离线安装与验证.txt`.
- `deployment/uos/docs/04-日常运维与日志排查.txt`.
- `deployment/uos/docs/05-故障恢复.txt`.
- `src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java` — shell syntax, layout, permission, redaction, and unit contract.

Modify after successful build:

- `README.md` — exact bundle build/install and operator commands.
- `AGENTS.md` — current supported UOS workflow and test boundary.
- `../CODE_GUIDE.md` — deployment source map, secret flow, logging path, and failure flow.
- `../AGENTS.md` — current implementation status and production boundary.

Generated only on Desktop, never committed:

```text
/Users/zhongerbing/Desktop/人员档案数据服务-统信UOS离线部署-20260813[-N]/
/Users/zhongerbing/Desktop/人员档案数据服务-统信UOS离线部署-20260813[-N].tar.gz
/Users/zhongerbing/Desktop/人员档案数据服务-统信UOS离线部署-20260813[-N].tar.gz.sha256
```

## Installer CLI Contract

```text
sudo ./install.sh                 first install or JAR upgrade; preserve existing secrets
sudo ./install.sh --reconfigure   prompt for a new application DB password; preserve all three existing keys
sudo ./install.sh --no-start      install/upgrade but do not enable/start/restart the service
sudo ./install.sh --reconfigure --no-start
```

Unknown options fail before any state change. `--reconfigure` always writes a root-only dated backup of the old environment file before atomically replacing only its database-password assignment; all other lines, especially the three keys, remain byte-for-byte unchanged. `--no-start` still installs the unit and runs `systemctl daemon-reload`, but does not enable or start it.

---

### Task 1: Lock the bundle and systemd contracts with tests

**Files:**

- Create: `src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java`

**Interfaces:**

- Produces: executable contract for the exact paths listed in the File Map.
- Consumes: no production database, root access, or systemd process; reads files and runs `/bin/bash -n` only.

- [ ] **Step 1: Write the failing layout test**

Assert every source payload path exists and both scripts are regular executable files. Read all text as UTF-8. Assert the installer and builder start with `#!/usr/bin/env bash` and contain `set -Eeuo pipefail`, while neither contains `set -x` or `set -o xtrace`.

- [ ] **Step 2: Write the failing offline/redaction test**

Scan installer, builder, unit, SQL, and docs. Assert they do not contain executable calls to:

```text
apt apt-get yum dnf curl wget nc ncat ssh scp rsync
```

Allow documentation to say that the installer never calls network package managers, but forbid command-line forms such as `curl ` or `wget `. Assert no assignment provides a nonempty literal for `PA_DB_PASSWORD`, `PA_TOKEN_PEPPER`, `PA_IDENTITY_HMAC_KEY`, or `PA_IDENTITY_ENCRYPTION_KEY`.

- [ ] **Step 3: Write the failing installer security contract**

Assert `install.sh` contains all of these behaviors:

```text
EUID root check
sha256sum --check SHA256SUMS
Java major version equals 21
getent group / useradd --system
read -r -s for PA_DB_PASSWORD
openssl rand for all three keys on first install only
reconfiguration preserves the three existing key assignments byte-for-byte
reject carriage return/newline before EnvironmentFile encoding
umask 077
mktemp inside /etc/personnel-archive-service
chmod 0600 and chown root:root
atomic mv of the environment file
explicit --reconfigure and --no-start parsing
systemctl daemon-reload
bounded service/port readiness loop
no output of secret variable values or environment-file contents
```

- [ ] **Step 4: Write the failing systemd contract**

Assert the unit template contains exactly the fixed account, working directory, environment file, JAR path, journal output, restart limits, and hardening keys:

```ini
User=personnel-archive
Group=personnel-archive
WorkingDirectory=/opt/personnel-archive-service
EnvironmentFile=/etc/personnel-archive-service/personnel-archive-service.env
ExecStart=@JAVA_BIN@ -jar /opt/personnel-archive-service/personnel-archive-service.jar
Restart=on-failure
SuccessExitStatus=143
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/personnel-archive-service/log
StandardOutput=journal
StandardError=journal
```

- [ ] **Step 5: Write the failing MySQL and documentation contract**

Assert the SQL contains `ALTER USER ... IDENTIFIED BY RANDOM PASSWORD`, `PASSWORD EXPIRE NEVER`, database-scoped `GRANT ALL PRIVILEGES`, and `SHOW GRANTS`, but contains no `CREATE USER`, plaintext `IDENTIFIED BY '...'`, `FLUSH PRIVILEGES`, or root password. Assert documentation tells the operator to use interactive `mysql -u root -p`, run SQL before installation, and never paste the new password into chat or a normal text file.

- [ ] **Step 6: Add a Bash syntax test**

Run this from JUnit with `ProcessBuilder`, capture output only on failure, and assert exit 0 for both scripts:

```bash
/bin/bash -n deployment/uos/install.sh
/bin/bash -n deployment/uos/build-bundle.sh
```

Also require `install.sh` to use a `main()` function guarded by `[[ "${BASH_SOURCE[0]}" == "$0" ]]`, so tests can source its pure encoding/rewrite functions without executing root checks.

- [ ] **Step 7: Add behavior tests for password encoding and key preservation**

Create a temporary fixture EnvironmentFile with dummy password and three distinct dummy keys. Launch `/bin/bash -c` through `ProcessBuilder`, pass the replacement test password in the child environment (not the command line), source `install.sh`, and call `rewrite_environment_password "$INPUT" "$OUTPUT" "$TEST_PASSWORD"`. Include safe test characters containing spaces, `#`, `$`, `=`, double quote, and backslash. Assert:

- the new password line is correctly double-quoted and escaped for systemd;
- the three key lines and every unrelated line remain byte-for-byte unchanged;
- exactly one password assignment exists;
- CR/LF input returns nonzero;
- captured stdout/stderr contains none of the password/key sentinels.

- [ ] **Step 8: Run the new contract test and confirm failure**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=UosDeploymentContractTest test
```

Expected: FAIL because deployment source files do not exist.

Do not commit the test alone; keep the red phase until Tasks 2–4 supply the complete payload.

---

### Task 2: Create the hardened systemd service template

**Files:**

- Create: `deployment/uos/service/personnel-archive-service.service.template`

**Interfaces:**

- Consumes: one installer-substituted absolute Java 21 path at `@JAVA_BIN@`.
- Produces: `personnel-archive-service.service` installed under `/etc/systemd/system`.

- [ ] **Step 1: Add the exact unit template**

Use this structure:

```ini
[Unit]
Description=人员档案数据服务
After=network.target mysql.service mysqld.service
Wants=network.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=personnel-archive
Group=personnel-archive
WorkingDirectory=/opt/personnel-archive-service
EnvironmentFile=/etc/personnel-archive-service/personnel-archive-service.env
Environment=TZ=Asia/Shanghai
ExecStart=@JAVA_BIN@ -jar /opt/personnel-archive-service/personnel-archive-service.jar
Restart=on-failure
RestartSec=10
SuccessExitStatus=143
TimeoutStopSec=30
UMask=0027
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/personnel-archive-service/log
StandardOutput=journal
StandardError=journal
SyslogIdentifier=personnel-archive-service

[Install]
WantedBy=multi-user.target
```

Do not add shell indirection, `bash -c`, dynamic command substitution, root execution, environment values, or writable code directories.

- [ ] **Step 2: Run the systemd portion of the contract test**

Run the Task 1 Maven command. Expected: it still fails for missing installer/docs, but unit assertions pass.

---

### Task 3: Create the root-run offline installer

**Files:**

- Create: `deployment/uos/install.sh`

**Interfaces:**

- Consumes: bundle-root `SHA256SUMS`, `personnel-archive-service.jar`, and service template.
- Produces: fixed `/opt`, `/etc`, and `/etc/systemd/system` paths plus service state.
- Supports: CLI contract defined above.
- Produces sourceable pure functions `systemd_quote(value)` and `rewrite_environment_password(existingFile, outputFile, replacementPassword)`; `main()` runs only when the script is executed directly.

- [ ] **Step 1: Implement option parsing and read-only preflight**

Before persistent installation changes:

- accept only `--reconfigure` and `--no-start` in any order;
- require `EUID == 0`;
- require `systemctl`, `sha256sum`, `install`, `getent`, `useradd`, `openssl`, `readlink`, `awk`, `sed`, `ss`, `tr`, `df`, `cp`, `mv`, `chmod`, `chown`, `tee`, `sleep`, and `mktemp`;
- create one root-only temporary installation log with `mktemp` under `/tmp`, redirect through `tee -a`, and track that exact path for cleanup if persistent directory creation never succeeds;
- resolve the bundle directory from `${BASH_SOURCE[0]}`;
- run `(cd "$BUNDLE_ROOT" && sha256sum --check SHA256SUMS)`;
- resolve Java using `readlink -f "$(command -v java)"` and parse the quoted version; accept only major 21;
- reject a resolved Java path containing anything outside `/A-Za-z0-9._+-`, so unit-template substitution cannot become a systemd or `sed` injection;
- verify at least 2 GiB free under `/opt` using `df -Pk` before copying the JAR.

All failures use a `fail()` function with fixed messages and exit nonzero. Do not include secret values or arbitrary command output in `fail()` arguments. The temporary log is the only preflight filesystem artifact and is deleted by the exact-path cleanup trap on failure.

- [ ] **Step 2: Create fixed identities/directories and start install logging**

Create the system group/user only when absent. Select `/usr/sbin/nologin` or `/sbin/nologin` only if present. Create:

```text
/opt/personnel-archive-service             root:root 0755
/opt/personnel-archive-service/log         personnel-archive:personnel-archive 0750
/etc/personnel-archive-service             root:root 0700
```

After creating the persistent log directory, atomically move the already-open temporary log to `install-YYYYMMDD-HHMMSS.log`, set root:root 0640, and clear the temporary-log cleanup variable. Bash `tee` continues writing through its open descriptor after the rename. Set `umask 077` before any temporary/config file.

After redirection, emit exactly numbered, timestamped, fixed-label progress messages without command tracing:

```text
[步骤 1/10] 检查离线运行环境
[步骤 2/10] 校验部署文件
[步骤 3/10] 验证 Java 21
[步骤 4/10] 创建服务账号和目录
[步骤 5/10] 准备受保护的运行配置
[步骤 6/10] 安装应用 JAR
[步骤 7/10] 安装 systemd 服务
[步骤 8/10] 重新加载服务配置
[步骤 9/10] 启动人员档案数据服务
[步骤 10/10] 检查端口和服务状态
```

Each message gets a fixed `成功` or `失败` result; never append a shell command, arbitrary exception message, or secret value.

- [ ] **Step 3: Implement safe EnvironmentFile encoding**

The shell cannot contain NUL; explicitly reject CR or LF. Implement a double-quoted systemd value encoder that escapes backslash and double quote without evaluating `$`, backticks, `#`, spaces, `=`, or command substitutions:

```bash
systemd_quote() {
  local value=$1
  [[ "$value" != *$'\r'* && "$value" != *$'\n'* ]] || fail "配置值包含不允许的换行符"
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  printf '"%s"' "$value"
}
```

Do not use `eval`, `source`, `envsubst`, shell-generated assignment execution, or `echo` for secret lines.

- [ ] **Step 4: Implement first-install and database-password reconfiguration flow**

If the environment file exists and `--reconfigure` is absent, preserve it byte-for-byte and log only that preservation occurred.

On first install:

1. prompt `请输入已轮换的 personnel_archive 数据库密码：` using `IFS= read -r -s`;
2. reject empty/CR/LF values without printing the value;
3. generate token pepper and HMAC key with 48 random bytes each;
4. generate the encryption key with exactly 32 random bytes and standard Base64;
5. create a temporary file inside `/etc/personnel-archive-service` with `mktemp`;
6. write fixed variables through `printf` and `systemd_quote`:

```text
SPRING_PROFILES_ACTIVE=prod
PA_SERVER_PORT=18080
PA_DB_URL=jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai
PA_DB_USERNAME=personnel_archive
PA_DB_PASSWORD=<interactive value>
PA_TOKEN_PEPPER=<48 random bytes encoded>
PA_IDENTITY_HMAC_KEY=<48 random bytes encoded>
PA_IDENTITY_ENCRYPTION_KEY=<32 random bytes standard Base64>
PA_LOG_FILE=/opt/personnel-archive-service/log/personnel-archive-service.log
PA_LOG_ARCHIVE_PATTERN=/opt/personnel-archive-service/log/personnel-archive-service.%d{yyyy-MM-dd}.%i.log.gz
```

7. mode/chown the temporary file root:root 0600;
8. atomically `mv` the prepared file into place;
9. unset the four in-memory secret variables immediately after the move.

On `--reconfigure`:

1. require an existing root-owned environment file or fail without creating a new identity-key set;
2. prompt only for the replacement database password using the same no-echo checks;
3. create a dated root-only 0600 backup without printing it;
4. read the existing file line-by-line without `source`, `eval`, or subprocess arguments;
5. copy every line byte-for-byte except the single `PA_DB_PASSWORD=` assignment, which is replaced using `systemd_quote`;
6. require exactly one replaced password assignment and require the three key assignments to remain present;
7. mode/chown the temporary file root:root 0600 and atomically move it into place;
8. unset the password variable.

Never regenerate, parse, decode, log, or rewrite the token pepper, identity HMAC key, or identity encryption key during reconfiguration.

- [ ] **Step 5: Install the JAR and rendered unit without unsafe overwrite**

Install the incoming JAR as a root-owned 0644 `.new` file. If a current JAR exists, copy it atomically to `.previous`; never delete the current or previous JAR before the new copy succeeds. Rename `.new` to the stable path only after size and SHA-256 match the bundle JAR.

Escape `&` and the sed delimiter in the Java path, render `@JAVA_BIN@` to a temporary unit file, verify the placeholder is gone and the JAR path is present, then install it root:root 0644 at `/etc/systemd/system/personnel-archive-service.service`.

- [ ] **Step 6: Implement bounded enable/start verification**

Always run `systemctl daemon-reload`. With `--no-start`, print the exact later commands and exit success without enabling. Otherwise:

```bash
systemctl enable personnel-archive-service.service
systemctl restart personnel-archive-service.service
```

Poll at most 30 times with two-second intervals. Success requires both `systemctl is-active --quiet` and a listening TCP socket ending in `:18080` from `ss -ltn`. If the unit becomes failed or the deadline expires, exit nonzero and print only these diagnostic commands:

```text
systemctl status personnel-archive-service --no-pager
journalctl -u personnel-archive-service -n 200 --no-pager
tail -n 200 /opt/personnel-archive-service/log/personnel-archive-service.log
```

Do not automatically restore `.previous`, run Flyway repair, edit MySQL, or print the environment file.

- [ ] **Step 7: Add safe cleanup traps and executable mode**

Track only explicit temporary file paths. The EXIT trap may remove those exact regular files with `rm -f --`; it must not recursively delete directories or expand globs. Apply executable mode to the source script.

- [ ] **Step 8: Run Bash syntax and partial contract tests**

```bash
chmod +x deployment/uos/install.sh
/bin/bash -n deployment/uos/install.sh
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=UosDeploymentContractTest test
```

Expected: installer/unit assertions pass; docs/builder assertions still fail until Task 4.

---

### Task 4: Add MySQL handoff and offline operator documents

**Files:**

- Create: `deployment/uos/mysql/01-轮换应用密码并授权.sql`
- Create: `deployment/uos/docs/01-安装前检查.txt`
- Create: `deployment/uos/docs/02-数据库授权.txt`
- Create: `deployment/uos/docs/03-离线安装与验证.txt`
- Create: `deployment/uos/docs/04-日常运维与日志排查.txt`
- Create: `deployment/uos/docs/05-故障恢复.txt`

**Interfaces:**

- Consumes: target MySQL administrator and root operator interactively.
- Produces: exact no-network runbook; no scripts consume the database password from disk.

- [ ] **Step 1: Add the credential-free MySQL SQL file**

Use comments explaining that MySQL prints the generated password once and that it must be entered directly into `install.sh`. The executable statements are exactly:

```sql
ALTER USER 'personnel_archive'@'127.0.0.1'
    IDENTIFIED BY RANDOM PASSWORD
    PASSWORD EXPIRE NEVER;

GRANT ALL PRIVILEGES ON `personnel_archive`.*
    TO 'personnel_archive'@'127.0.0.1';

SHOW GRANTS FOR 'personnel_archive'@'127.0.0.1';
```

Do not add a password literal, root credential, `FLUSH PRIVILEGES`, database deletion, or migration manipulation.

- [ ] **Step 2: Write the installation preflight document**

`01-安装前检查.txt` lists commands for UOS/systemd, architecture, Java 21, MySQL active state, loopback port 3306, disk, package checksum, and a database backup. It explicitly says the package is platform-neutral Java bytecode and no internet is required.

- [ ] **Step 3: Write the database handoff document**

`02-数据库授权.txt` uses interactive `mysql -u root -p`, sources the bundled SQL from inside MySQL or copies its three statements, explains the generated-password result, and tells the operator not to save/paste the new value. It explains that missing GRANT causes Flyway startup failure and that the service must use `127.0.0.1`, not `localhost`, because the MySQL account host is exact.

- [ ] **Step 4: Write installation and target acceptance commands**

`03-离线安装与验证.txt` includes:

```bash
sha256sum -c SHA256SUMS
sudo ./install.sh
systemctl is-enabled personnel-archive-service
systemctl is-active personnel-archive-service
ss -ltn | grep ':18080'
sudo mysql -u personnel_archive -p -h 127.0.0.1 -D personnel_archive \
  -e "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
```

It expects Flyway version `14` with success `1`. It separates local package verification from target acceptance and never claims success before these commands pass.

- [ ] **Step 5: Write daily operations and request-ID diagnosis**

`04-日常运维与日志排查.txt` includes start/stop/restart/status, journal follow, file tail, disk usage, gzip history reading, request ID lookup, and rotation defaults. Example:

```bash
sudo journalctl -u personnel-archive-service -f
sudo tail -F /opt/personnel-archive-service/log/personnel-archive-service.log
sudo grep -F 'requestId=<客户端看到的编号>' /opt/personnel-archive-service/log/personnel-archive-service.log
```

The angle-bracket text is an operator instruction, not a credential. Warn never to paste environment-file contents into support messages.

- [ ] **Step 6: Write non-destructive failure recovery**

`05-故障恢复.txt` maps Java/version, checksum, directory permission, database connection, GRANT, Flyway, disk, port, and restart-limit failures to commands. It forbids automatic Flyway repair, migration deletion, environment-file printing, log deletion, database deletion, and blind JAR rollback. Explain `.previous` can be restored only after schema compatibility is confirmed and a backup exists.

- [ ] **Step 7: Run the contract test**

Run the Task 1 Maven command. Expected: only the missing builder assertions remain.

---

### Task 5: Build a collision-safe Desktop bundle

**Files:**

- Create: `deployment/uos/build-bundle.sh`
- Complete: `src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java`

**Interfaces:**

- CLI: `./deployment/uos/build-bundle.sh <executable-jar> <output-parent> [YYYYMMDD]`.
- Produces: directory, `.tar.gz`, and `.tar.gz.sha256`; prints their absolute paths.

- [ ] **Step 1: Implement strict build inputs**

Require exactly two or three arguments. Verify the JAR is a readable regular file and contains:

```text
BOOT-INF/classes/application-prod.yml
BOOT-INF/classes/db/migration/V1__initial_schema.sql
BOOT-INF/classes/db/migration/V14__canonicalize_open_ended_range_text.class
BOOT-INF/classes/excel/personnel-archive-template-v1.xlsx
```

Verify the output parent exists and is writable. Validate the optional date against `[0-9]{8}`. The script must not invoke Maven or rebuild an unverified source tree.

- [ ] **Step 2: Make output naming non-overwriting**

Start with `人员档案数据服务-统信UOS离线部署-$DATE`. If the directory, tarball, or checksum already exists, try `-1`, `-2`, and so on up to `-99`; fail after 99 rather than deleting or overwriting anything.

Create staging with `mktemp -d` under the output parent. The cleanup trap may recursively delete only the validated exact staging path created by `mktemp`, after checking it begins with the output-parent deployment staging prefix.

- [ ] **Step 3: Copy only the allowlisted payload**

Create the bundle layout from the design and copy:

- JAR to stable `personnel-archive-service.jar` mode 0644;
- installer mode 0755;
- service template mode 0644;
- SQL and five docs mode 0644.

Generate `BUILD-INFO.txt` containing build timestamp, Java version, current Git HEAD, source state `clean` or `verified-working-tree`, and the JAR SHA-256. Do not include Git diffs, environment variables, usernames, hostnames, source paths, or secret values.

- [ ] **Step 4: Generate compatible inner and outer checksums**

Choose `sha256sum` when available, otherwise `shasum -a 256`. Generate sorted GNU-compatible two-space `SHA256SUMS` entries for every regular payload file except the manifest itself, using paths beginning `./`. Verify every listed hash immediately with the selected local tool.

Atomically move the staged directory to the chosen final directory. Create the tarball through a temporary regular file and rename only after `tar -tzf` lists the expected top directory. Generate `<tar>.sha256` with only the tar basename, so target verification works from the Desktop directory.

- [ ] **Step 5: Add builder contract assertions**

Complete `UosDeploymentContractTest` to assert the builder:

- never uses a fixed temporary directory;
- uses `mktemp -d` and an exact-prefix cleanup guard;
- uses an allowlist rather than copying the whole repository;
- refuses collisions or chooses a numeric suffix;
- produces inner/outer checksums;
- never reads `.env`, the Desktop attachment, or `application-dev.yml`;
- does not invoke Maven, Git clean/reset/checkout, or network tools.

- [ ] **Step 6: Run red-to-green deployment tests**

```bash
chmod +x deployment/uos/build-bundle.sh
/bin/bash -n deployment/uos/build-bundle.sh
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=UosDeploymentContractTest test
```

Expected: PASS.

- [ ] **Step 7: Commit the complete deployment source**

```bash
git add -- \
  deployment/uos/install.sh \
  deployment/uos/build-bundle.sh \
  deployment/uos/service/personnel-archive-service.service.template \
  deployment/uos/mysql/01-轮换应用密码并授权.sql \
  deployment/uos/docs/01-安装前检查.txt \
  deployment/uos/docs/02-数据库授权.txt \
  deployment/uos/docs/03-离线安装与验证.txt \
  deployment/uos/docs/04-日常运维与日志排查.txt \
  deployment/uos/docs/05-故障恢复.txt \
  src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java
git commit --only -m "feat(service): add offline UOS deployment bundle" -- \
  deployment/uos/install.sh \
  deployment/uos/build-bundle.sh \
  deployment/uos/service/personnel-archive-service.service.template \
  deployment/uos/mysql/01-轮换应用密码并授权.sql \
  deployment/uos/docs/01-安装前检查.txt \
  deployment/uos/docs/02-数据库授权.txt \
  deployment/uos/docs/03-离线安装与验证.txt \
  deployment/uos/docs/04-日常运维与日志排查.txt \
  deployment/uos/docs/05-故障恢复.txt \
  src/test/java/com/company/personnelarchive/configuration/UosDeploymentContractTest.java
```

---

### Task 6: Documentation, release verification, and Desktop assembly

**Files:**

- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `../CODE_GUIDE.md`
- Modify: `../AGENTS.md`
- Generate: Desktop artifacts only.

**Interfaces:**

- Consumes: verified logging implementation, verified deployment sources, executable JAR.
- Produces: final user-transferable artifacts and evidence report.

- [ ] **Step 1: Synchronize all current-state documentation**

Add the supported deployment path, exact fixed names, secret flow, systemd service, installed layout, log rotation, build command, target acceptance, and no-network/no-target-connection boundary. Root `CODE_GUIDE.md` gets source-map entries for `common` logging classes and `deployment/uos`. Root `AGENTS.md` current status moves the offline UOS bundle and file logging into implemented state only now.

- [ ] **Step 2: Run static safety checks**

```bash
git diff --check
/bin/bash -n deployment/uos/install.sh
/bin/bash -n deployment/uos/build-bundle.sh
rg -n "set -x|set -o xtrace|curl |wget |apt-get |dnf |PA_DB_PASSWORD=.+|PA_TOKEN_PEPPER=.+|PA_IDENTITY_HMAC_KEY=.+|PA_IDENTITY_ENCRYPTION_KEY=.+" \
  deployment/uos src/main/resources README.md AGENTS.md ../CODE_GUIDE.md ../AGENTS.md
```

Inspect matches: EnvironmentFile variable names and empty/interactive writes are allowed; any embedded value or executable network command is a release blocker.

- [ ] **Step 3: Check that the attachment's exposed secret is absent without printing it**

Read `/Users/zhongerbing/Desktop/新建 文本文档 (2).txt` only inside a local scanner. Extract candidate secret values in memory, search the repository and prospective bundle by fixed-string comparison, and output only `clean` or matching filenames/counts. Never echo candidates, matching lines, environment values, or hashes of the password. If any match exists, stop and remove it with `apply_patch` before continuing.

- [ ] **Step 4: Run complete Java 21 verification**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw verify
```

Record the exact test total, failures, errors, and skips. Docker-backed MySQL tests must run when Docker is available; if skipped, disclose that target MySQL migration remains an offline acceptance step.

- [ ] **Step 5: Build and inspect the executable JAR**

Use the JAR from successful `verify`:

```bash
JAR=target/personnel-archive-service-0.1.0-SNAPSHOT.jar
test -s "$JAR"
jar tf "$JAR" | rg 'BOOT-INF/classes/(application-prod.yml|db/migration/V1__|db/migration/V14__|excel/personnel-archive-template-v1.xlsx)'
shasum -a 256 "$JAR"
```

Do not start the prod JAR locally because that would attempt a real MySQL connection.

- [ ] **Step 6: Build the Desktop directory and tarball**

```bash
./deployment/uos/build-bundle.sh \
  target/personnel-archive-service-0.1.0-SNAPSHOT.jar \
  /Users/zhongerbing/Desktop \
  20260813
```

Capture the paths printed by the script. If a same-name artifact exists, confirm the numeric suffix rather than overwriting it.

- [ ] **Step 7: Independently verify final artifacts**

From the generated directory:

```bash
shasum -a 256 -c SHA256SUMS
tar -tzf ../人员档案数据服务-统信UOS离线部署-20260813[-N].tar.gz
shasum -a 256 -c ../人员档案数据服务-统信UOS离线部署-20260813[-N].tar.gz.sha256
/bin/bash -n install.sh
```

Resolve the real suffix before running; do not paste the bracket notation literally. Confirm no `.env`, database dump, source workbook, attachment, token, key, password file, `target/` tree, or Git metadata exists inside.

- [ ] **Step 8: Re-run the no-output exposed-secret scanner against Desktop artifacts**

Use the same in-memory comparison from Step 3 and report only `clean` or filenames/counts. A match blocks delivery.

- [ ] **Step 9: Commit tracked documentation only**

```bash
git add -- README.md AGENTS.md
git commit --only -m "docs(service): document offline UOS operations" -- README.md AGENTS.md
```

Root docs remain local because the repository root is not Git; report their paths and changed state.

- [ ] **Step 10: Preserve user changes and prepare the handoff report**

```bash
git status --short --branch
git diff -- src/main/java/com/company/personnelarchive/archiveimport/ExcelArchiveReader.java
git diff --cached -- src/test/java/com/company/personnelarchive/archiveimport/ExcelNumericDatePrecisionTest.java
```

The final report includes:

- clickable Desktop directory, tarball, and checksum paths;
- executable JAR SHA-256;
- Java/Maven test totals and Docker skip status;
- local verifications completed;
- target commands still required: rotate/grant, install, `systemctl`, port, Flyway V14, file log, request ID, and safe test-data smoke flow;
- explicit statement that no target database connection was attempted;
- explicit statement that the old exposed database password is not present and must not be reused;
- exact remaining user-owned Excel source/test changes.
