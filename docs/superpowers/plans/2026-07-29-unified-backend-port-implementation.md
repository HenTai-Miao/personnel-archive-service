# Unified Backend Port Implementation Plan

> Historical scope note: the backend was later split into common `application.yml`, MySQL `application-dev.yml`, and Kingbase `application-prod.yml`. Port `18080` now lives only in common configuration and is inherited by both profiles.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every backend environment listen on port `18080` and make every active frontend/local-launcher request target that same port.

**Architecture:** The backend common configuration supplies one port to `dev` and `prod` while retaining `PA_SERVER_PORT`. The desktop keeps different local and production hosts, but both API URLs use port `18080`; the root launcher and E2E interceptors follow the same contract.

**Tech Stack:** Java 21, Spring Boot YAML configuration, JUnit 5, Electron, Vite/electron-vite, TypeScript, Vitest, Playwright, zsh.

## Global Constraints

- Backend common, `dev`, and `prod` effective defaults are all `18080`.
- Local frontend API base is `http://127.0.0.1:18080/api/v1`.
- Production frontend API base is `http://10.141.29.96:18080/api/v1`.
- `PA_SERVER_PORT` remains the deployment override where used.
- Database configuration, database data, and the existing `local-mysql` container must not be changed by automated tests.
- Do not pull Testcontainers database images.
- Never store or print the supplied server password.

---

### Task 1: Unify backend configuration on port 18080

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java`
- Modify: `src/main/resources/application.yml`
- Verify: `src/main/resources/application-dev.yml`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: common configuration, `dev`/`prod` profiles, and optional `PA_SERVER_PORT`.
- Produces: `server.port=18080` for every default configuration and an environment override for environment-backed configurations.

- [ ] **Step 1: Change the test to require port 18080 everywhere**

```java
@Test
void usesFiveDigitPortInEveryEnvironment() throws IOException {
    assertThat(resolvedServerPort(null, "src/main/resources/application.yml"))
        .isEqualTo("18080");
    assertThat(resolvedServerPort(null,
        "src/main/resources/application.yml",
        "src/main/resources/application-dev.yml"))
        .isEqualTo("18080");
    assertThat(resolvedServerPort(null,
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"))
        .isEqualTo("18080");
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=ProductionProfileConfigurationTest test
```

Expected: FAIL because the base and local configurations still resolve `8080`.

- [ ] **Step 3: Change both backend defaults**

Set the shared value in `application.yml`:

```yaml
server:
  port: ${PA_SERVER_PORT:18080}
```

`application-dev.yml` does not repeat `server.port`; it inherits the shared value above.

- [ ] **Step 4: Update the backend status documentation**

Replace the split-port status in `AGENTS.md` with one statement that common, `dev`, and `prod` all resolve to `18080`, while `PA_SERVER_PORT` remains the deployment override.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: 2 tests pass.

- [ ] **Step 6: Commit the backend configuration**

```bash
git add AGENTS.md src/main/resources/application.yml \
  src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java
git commit -m "fix(service): unify backend port on 18080"
```

---

### Task 2: Point local desktop and launcher traffic to port 18080

**Files:**
- Create: `tests/unit/local-api-environment.test.ts`
- Modify: `tests/unit/root-launcher-promotion.test.ts`
- Modify: `.env.example`
- Modify locally but do not commit: `.env.local`
- Modify: `electron.vite.config.ts`
- Modify: `src/renderer/shared/api/config.ts`
- Modify: `tests/e2e/archive-detail.spec.ts`
- Modify: `tests/e2e/b-series-layout.spec.ts`
- Modify: `tests/e2e/list-pagination.spec.ts`
- Modify: `tests/e2e/person-deletion.spec.ts`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify outside Git: `../启动本地测试.command`

**Interfaces:**
- Consumes: local backend endpoint `http://127.0.0.1:18080/api/v1`.
- Produces: matching Vite environment, renderer fallback, E2E interceptors, and launcher readiness probe.

- [ ] **Step 1: Write failing local endpoint tests**

Add a test that temporarily removes Vitest-injected Vite variables, loads Vite mode `development`, and asserts:

```typescript
expect(environment["VITE_API_BASE_URL"]).toBe("http://127.0.0.1:18080/api/v1")
expect(environment["VITE_API_ORIGIN"]).toBe("http://127.0.0.1:18080")
```

Extend `root-launcher-promotion.test.ts` to assert the `--check` output contains:

```typescript
expect(result.stdout).toContain("后端 API：http://127.0.0.1:18080/api/v1\n")
```

- [ ] **Step 2: Run the focused tests and verify RED**

```bash
pnpm exec vitest run tests/unit/local-api-environment.test.ts tests/unit/root-launcher-promotion.test.ts
```

Expected: FAIL because `.env.local` still uses `8080` and the launcher does not report the unified endpoint.

- [ ] **Step 3: Update active local frontend endpoints**

Use these exact local values in `.env.example` and `.env.local`:

```dotenv
VITE_API_BASE_URL=http://127.0.0.1:18080/api/v1
VITE_API_ORIGIN=http://127.0.0.1:18080
```

Change both TypeScript fallbacks to `http://127.0.0.1:18080/api/v1`, and change all four E2E request interceptors from port `8080` to `18080`.

- [ ] **Step 4: Centralize the launcher endpoint**

Define one launcher variable:

```zsh
SERVICE_API_BASE_URL="http://127.0.0.1:18080/api/v1"
```

Print it during `--check` and use `${SERVICE_API_BASE_URL}/persons` for readiness probing.

- [ ] **Step 5: Update client documentation**

Update `README.md` and `AGENTS.md` so local development uses loopback port `18080`; keep `.env.prod` on host `10.141.29.96` and port `18080`.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: 2 test files pass.

- [ ] **Step 7: Build and exercise both desktop modes**

```bash
pnpm build
pnpm build:prod
pnpm test:e2e
```

Expected: both builds exit zero and all 7 Electron E2E tests pass without contacting a real database.

- [ ] **Step 8: Commit the tracked frontend changes**

```bash
git add .env.example AGENTS.md README.md electron.vite.config.ts \
  src/renderer/shared/api/config.ts tests/e2e tests/unit/local-api-environment.test.ts \
  tests/unit/root-launcher-promotion.test.ts
git commit -m "fix(desktop): use backend port 18080 everywhere"
```

`.env.local` and the root launcher remain outside this repository's commit boundary but must stay updated in the shared workspace.

---

### Task 3: Synchronize current documentation, verify, and push

**Files:**
- Modify outside Git: `../AGENTS.md`
- Modify outside Git: `../CODE_GUIDE.md`
- Modify historical current-flow documentation only where it still instructs a live `8080` launch.

**Interfaces:**
- Consumes: the unified `18080` contract from Tasks 1 and 2.
- Produces: synchronized current documentation, verified clean child repositories, and updated remote branches.

- [ ] **Step 1: Update root current-state documentation**

State that local and production backends both use port `18080`; local host is `127.0.0.1`, production host is `10.141.29.96`.

- [ ] **Step 2: Remove obsolete live 8080 instructions**

Update current launcher/build documentation that still instructs a live service or client to use port `8080`. Historical implementation narratives may remain only when clearly marked as superseded.

- [ ] **Step 3: Run fresh frontend verification**

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm build
pnpm build:prod
pnpm test:e2e
```

Expected: all commands exit zero; known design-preview CSS warnings may remain warnings only.

- [ ] **Step 4: Run backend verification without database pulls**

Run `ProductionProfileConfigurationTest`, the 65 non-database tests listed in the previous verification, and:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -DskipTests package
```

Expected: configuration tests, non-database tests, and JAR packaging all pass without creating or pulling a MySQL image.

- [ ] **Step 5: Verify repository state and remote divergence**

For each child repository run `git diff --check`, confirm `git status --short` is empty, fetch the remote, and ensure it has no commits unknown to the local branch.

- [ ] **Step 6: Push both existing branches without force**

```bash
git -C personnel-archive-desktop-b push origin dev-yx-20260729
git -C personnel-archive-service push origin dev-yx-20260724
```

Expected: both pushes succeed and local `HEAD` equals its upstream tracking ref.
