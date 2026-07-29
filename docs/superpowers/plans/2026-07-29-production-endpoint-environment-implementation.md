# Production Endpoint Environment Implementation Plan

> **Superseded port assumption:** This plan records the first production-only port change. The later `2026-07-29-unified-backend-port-implementation.md` is authoritative and moves base, local, and production traffic to `18080`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit `prod` mode whose Java service listens on port `18080` and whose packaged Electron client calls `http://10.141.29.96:18080/api/v1`, while leaving local development on `127.0.0.1:8080`.

**Architecture:** Spring profile `prod` owns only the production port default, while environment variable `PA_SERVER_PORT` remains authoritative. Vite mode `prod` owns the production API base/origin, and the Windows packaging path must invoke that mode explicitly so loopback cannot leak into a distributable client.

**Tech Stack:** Java 21, Spring Boot YAML configuration, JUnit 5, Electron, Vite/electron-vite, TypeScript, Vitest, pnpm.

## Global Constraints

- Production service host is exactly `10.141.29.96`.
- Production service default port is exactly `18080` and remains overrideable with `PA_SERVER_PORT`.
- Production API base URL is exactly `http://10.141.29.96:18080/api/v1`.
- Production API origin is exactly `http://10.141.29.96:18080`.
- Local development remains on `127.0.0.1:8080`.
- Never store the supplied server password in source, environment files, tests, build output, or logs.
- Do not log in to or deploy onto the server as part of this implementation.

---

### Task 1: Backend production port profile

**Files:**
- Create: `src/main/resources/application-prod.yml`
- Create: `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: Spring profile name `prod` and optional environment variable `PA_SERVER_PORT`.
- Produces: resolved `server.port` value `18080` for `prod`, `8080` for base/local configuration, and an environment override when supplied.

- [ ] **Step 1: Write the failing configuration test**

```java
/* 生产配置回归测试：prod 使用明确的五位端口，同时保留环境覆盖和本地默认值。 */
package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionProfileConfigurationTest {
    @Test
    void keepsLocalDefaultAndUsesFiveDigitProductionPort() throws IOException {
        assertThat(resolvedServerPort(null, "src/main/resources/application.yml"))
            .isEqualTo("8080");
        assertThat(resolvedServerPort(null,
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("18080");
    }

    @Test
    void allowsOperationsToOverrideProductionPort() throws IOException {
        assertThat(resolvedServerPort("28080",
            "src/main/resources/application.yml",
            "src/main/resources/application-prod.yml"))
            .isEqualTo("28080");
    }

    private static String resolvedServerPort(String override, String... configPaths)
        throws IOException {
        MockEnvironment environment = new MockEnvironment();
        if (override != null) environment.setProperty("PA_SERVER_PORT", override);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String configPath : configPaths) {
            List<PropertySource<?>> sources = loader.load(
                configPath,
                new FileSystemResource(configPath));
            for (PropertySource<?> source : sources) {
                environment.getPropertySources().addFirst(source);
            }
        }
        return environment.getRequiredProperty("server.port");
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw -Dtest=ProductionProfileConfigurationTest test
```

Expected: FAIL because `src/main/resources/application-prod.yml` does not exist.

- [ ] **Step 3: Add the minimal production profile**

```yaml
server:
  port: ${PA_SERVER_PORT:18080}
```

- [ ] **Step 4: Document the backend profile**

Add to `CURRENT SERVICE STATUS` in `AGENTS.md` that profile `prod` defaults to port `18080`, while `PA_SERVER_PORT` can override it and the local default remains `8080`.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: 2 tests pass.

- [ ] **Step 6: Commit the backend deliverable**

```bash
git add AGENTS.md src/main/resources/application-prod.yml \
  src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java
git commit -m "feat(service): add production port profile"
```

---

### Task 2: Frontend prod mode and Windows packaging contract

**Files:**
- Create: `.env.prod`
- Create: `tests/unit/production-environment.test.ts`
- Modify: `package.json`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: Vite mode name `prod` through `electron-vite build --mode prod`.
- Produces: `VITE_API_BASE_URL=http://10.141.29.96:18080/api/v1`, `VITE_API_ORIGIN=http://10.141.29.96:18080`, script `build:prod`, and Windows packaging that always calls `build:prod`.

- [ ] **Step 1: Write the failing production-mode contract test**

```typescript
/** 生产环境回归测试：Windows 安装包必须使用服务器地址，不能带入本机回环地址。 */
import { readFile } from "node:fs/promises"
import { resolve } from "node:path"
import { loadEnv } from "vite"
import { describe, expect, it } from "vitest"
import { z } from "zod"

const packageSchema = z.object({
  scripts: z.record(z.string(), z.string()),
})

describe("production API environment", () => {
  it("builds Windows packages with the explicit prod endpoint", async () => {
    const projectRoot = resolve(import.meta.dirname, "../..")
    const environment = loadEnv("prod", projectRoot, "")
    const packageJson = packageSchema.parse(
      JSON.parse(await readFile(resolve(projectRoot, "package.json"), "utf8")),
    )

    expect(environment["VITE_API_BASE_URL"]).toBe(
      "http://10.141.29.96:18080/api/v1",
    )
    expect(environment["VITE_API_ORIGIN"]).toBe("http://10.141.29.96:18080")
    expect(packageJson.scripts["build:prod"]).toBe(
      "pnpm typecheck && electron-vite build --mode prod",
    )
    expect(packageJson.scripts["build:win"]).toBe(
      "pnpm build:prod && electron-builder --win nsis",
    )
  })
})
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
pnpm exec vitest run tests/unit/production-environment.test.ts
```

Expected: FAIL because mode `prod` falls back to `.env.local` and `build:prod` is absent.

- [ ] **Step 3: Add the prod environment file**

```dotenv
VITE_API_BASE_URL=http://10.141.29.96:18080/api/v1
VITE_API_ORIGIN=http://10.141.29.96:18080
```

- [ ] **Step 4: Add explicit production scripts**

Set these `package.json` scripts without changing `dev`, `build`, or the local environment:

```json
"build:prod": "pnpm typecheck && electron-vite build --mode prod",
"build:win": "pnpm build:prod && electron-builder --win nsis"
```

- [ ] **Step 5: Document the frontend production mode**

Add to `CURRENT CLIENT STATUS` and `TEST AND BUILD BASELINE` in `AGENTS.md` that `.env.prod` is loaded only through `--mode prod`, `build:win` uses that mode, and local development remains on `.env.local`.

- [ ] **Step 6: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: 1 test passes.

- [ ] **Step 7: Build the production renderer and inspect the emitted endpoint**

Run:

```bash
pnpm build:prod
rg -n "10\.141\.29\.96:18080" out/renderer/index.html out/renderer/assets -g '*.js'
```

Expected: the search finds the production CSP origin and API base in the generated output. The focused contract test independently proves that Vite mode `prod` selected those values instead of the local environment.

- [ ] **Step 8: Commit the frontend deliverable**

```bash
git add .env.prod AGENTS.md package.json tests/unit/production-environment.test.ts
git commit -m "feat(desktop): add production API environment"
```

---

### Task 3: Cross-project documentation and full verification

**Files:**
- Modify: `../AGENTS.md`
- Modify: `../CODE_GUIDE.md`

**Interfaces:**
- Consumes: backend profile `prod`, frontend Vite mode `prod`, port `18080`, and host `10.141.29.96` from Tasks 1 and 2.
- Produces: synchronized cross-project documentation and fresh verification evidence.

- [ ] **Step 1: Update the cross-project status**

In root `AGENTS.md`, record that the service `prod` profile defaults to `18080` and the canonical client `.env.prod`/Windows packaging target `http://10.141.29.96:18080/api/v1`; explicitly retain the local launcher contract at `127.0.0.1:8080`.

- [ ] **Step 2: Update the source map**

In root `CODE_GUIDE.md`:

- Add `.env.prod` to the frontend build/configuration map.
- Record `build:prod` and the fact that `build:win` uses Vite mode `prod`.
- Add `application-prod.yml` to the backend configuration map with default port `18080` and `PA_SERVER_PORT` override.
- Keep the root local-launcher description unchanged except for an explicit `8080` local endpoint note.

- [ ] **Step 3: Run full frontend verification**

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm build:prod
```

Expected: every command exits zero; existing design-preview CSS warnings may remain warnings but no lint errors are accepted.

- [ ] **Step 4: Run full backend verification**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw verify
```

Expected: BUILD SUCCESS with Docker-backed MySQL suites executed when Docker is available.

- [ ] **Step 5: Verify repository state and secrets boundary**

```bash
git -C personnel-archive-desktop-b diff --check
git -C personnel-archive-service diff --check
rg -n "10\.141\.29\.96|18080" \
  personnel-archive-desktop-b/.env.prod \
  personnel-archive-service/src/main/resources/application-prod.yml \
  AGENTS.md CODE_GUIDE.md
```

Expected: no whitespace errors; only the approved host/port appear. Do not search for, print, or persist the supplied password.

The root directory is not a Git repository, so its synchronized `AGENTS.md` and `CODE_GUIDE.md` edits cannot be committed with either child repository. Report that limitation in the handoff; do not initialize a new repository.
