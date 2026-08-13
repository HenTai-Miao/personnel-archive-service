# Production Operational Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add production file logging beside the executable JAR, request correlation, safe exception diagnostics, and non-sensitive checkpoints for every implemented business flow.

**Architecture:** Spring Boot's production logging properties write and rotate the file log, while a highest-precedence servlet filter creates one server-owned request ID and emits the generic request completion event. Small common utilities provide safe elapsed-time calculation, after-commit logging, and exception summaries without messages; feature services emit fixed event names with only internal IDs, counts, result codes, byte counts, and durations.

**Tech Stack:** Java 21, Spring Boot 4.0.7, Spring MVC/Security, SLF4J + Logback, MyBatis, Flyway MySQL, JUnit 5, Mockito, AssertJ.

## Global Constraints

- Production remains MySQL-only at `127.0.0.1:3306/personnel_archive` by default and uses immutable Flyway V1–V14.
- Production HTTP remains on port `18080`; `PA_SERVER_PORT` stays authoritative when set.
- The default production log is `./log/personnel-archive-service.log`; the UOS service later supplies an absolute path beside `/opt/personnel-archive-service/personnel-archive-service.jar`.
- Rotate at 20 MB, retain 30 periods, compress archives, cap history at 1 GB, and never delete current logs at application startup.
- Never log passwords, database secrets, token values, usernames, identity-card or phone values, names, addresses, search terms, deletion reasons, workbook filenames/paths/hashes/cells/bytes, photo bytes, request bodies, query strings, cookies, or authorization headers.
- Do not enable MyBatis, JDBC, Hikari, request-body, multipart, or SQL-parameter DEBUG logging.
- The server generates every request ID; it must not trust a client-supplied ID.
- Unknown exceptions log exception/cause class names and bounded code locations, never `Throwable.getMessage()`.
- Do not connect to the user's local MySQL or the unreachable production computer while implementing or testing.
- Preserve the existing user-owned modifications to `ExcelArchiveReader.java` and `ExcelNumericDatePrecisionTest.java`; commits use exact pathspecs and must not absorb or reset them.
- Update `README.md`, service `AGENTS.md`, root `CODE_GUIDE.md`, and root `AGENTS.md` when the implementation becomes current behavior.

---

## File Map

Create:

- `src/main/java/com/company/personnelarchive/common/RequestCorrelation.java` — request ID constants and MDC access.
- `src/main/java/com/company/personnelarchive/common/RequestCorrelationFilter.java` — request ID lifecycle, response header, and generic completion event.
- `src/main/java/com/company/personnelarchive/common/OperationalLog.java` — elapsed milliseconds and after-commit emission.
- `src/main/java/com/company/personnelarchive/common/SafeExceptionSummary.java` — message-free bounded exception diagnostics.
- `src/main/java/com/company/personnelarchive/configuration/SafeJdbcTarget.java` — extracts only MySQL host, port, and schema.
- `src/main/java/com/company/personnelarchive/configuration/OperationalStartupLogger.java` — emits the ready event.
- `src/test/java/com/company/personnelarchive/support/LogCapture.java` — scoped Logback capture for redaction assertions.
- `src/test/java/com/company/personnelarchive/common/RequestCorrelationFilterTest.java`.
- `src/test/java/com/company/personnelarchive/common/SafeExceptionSummaryTest.java`.
- `src/test/java/com/company/personnelarchive/common/GlobalExceptionHandlerLoggingTest.java`.
- `src/test/java/com/company/personnelarchive/audit/AuditRequestCorrelationTest.java`.
- `src/test/java/com/company/personnelarchive/configuration/OperationalStartupLoggerTest.java`.
- `src/test/java/com/company/personnelarchive/auth/AuthOperationalLoggingTest.java`.
- `src/test/java/com/company/personnelarchive/archiveimport/ImportOperationalLoggingTest.java`.
- `src/test/java/com/company/personnelarchive/person/PersonOperationalLoggingTest.java`.
- `src/test/java/com/company/personnelarchive/archiveexport/ExportOperationalLoggingTest.java`.

Modify:

- `src/main/resources/application-prod.yml` — file path, correlation pattern, and rolling policy.
- `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java` — production logging contract and environment overrides.
- `src/main/java/com/company/personnelarchive/common/ApiError.java` — safe request-ID error factory.
- `src/main/java/com/company/personnelarchive/common/GlobalExceptionHandler.java` — fixed failure events and shared request ID.
- `src/main/java/com/company/personnelarchive/configuration/SecurityConfiguration.java` — expose `X-Request-Id` and use it in security errors.
- `src/test/java/com/company/personnelarchive/configuration/ApiContractSerializationTest.java`.
- `src/test/java/com/company/personnelarchive/configuration/CorsConfigurationTest.java`.
- `src/main/java/com/company/personnelarchive/auth/BearerTokenFilter.java` — attach authenticated internal user ID to the request.
- `src/main/java/com/company/personnelarchive/audit/AuditService.java` — correlate audit rows with the HTTP request when present.
- `src/main/java/com/company/personnelarchive/auth/AuthService.java`.
- `src/main/java/com/company/personnelarchive/archiveimport/ImportBatchService.java`.
- `src/main/java/com/company/personnelarchive/archiveimport/ImportItemService.java`.
- `src/main/java/com/company/personnelarchive/archiveimport/ImportHistoryService.java`.
- `src/main/java/com/company/personnelarchive/person/PersonQueryService.java`.
- `src/main/java/com/company/personnelarchive/person/PersonDeletionService.java`.
- `src/main/java/com/company/personnelarchive/archiveexport/ArchiveExportService.java`.
- `src/main/java/com/company/personnelarchive/archiveexport/BatchArchiveExportService.java`.
- `README.md`, `AGENTS.md`, `../CODE_GUIDE.md`, `../AGENTS.md`.

## Fixed Event Vocabulary

| Event | Required safe fields |
|---|---|
| `service_ready` | version, javaVersion, profile, port, logFile, dbHost, dbPort, dbSchema, securityKeysConfigured, durationMs |
| `http_request_completed` | method, route template or `UNMATCHED`, status, userId or `anonymous`, outcome, durationMs |
| `request_business_rejected` | code |
| `request_validation_rejected` | category |
| `request_failed` | safe exception summary |
| `auth_login_started/completed/failed` | userId when known, code on failure, durationMs |
| `auth_refresh_started/completed/failed` | userId when known, code on failure, durationMs |
| `auth_logout_started/completed/failed` | userId, code on failure, durationMs |
| `import_batch_started/completed` | operatorId, batchId when allocated, fileCount, imported/pending/failed/skipped/overwritten counts, durationMs |
| `import_item_started/parsed/completed/failed` | batchId, itemOrder, itemId when allocated, personId when allocated, fileSize, section counts, result/code, durationMs |
| `import_decision_started/completed/failed` | itemId, operatorId, decision enum only, result/code, durationMs |
| `import_history_completed` | operatorId, page, size, returnedCount, total, durationMs |
| `person_list_completed` | operatorId, page, size, returnedCount, total, durationMs |
| `person_detail_completed` | operatorId, personId, durationMs |
| `family_search_completed` | page, size, returnedCount, total, durationMs |
| `sensitive_value_completed` | operatorId, personId, field enum, durationMs |
| `photo_completed` | operatorId, personId, byteCount, durationMs |
| `person_deletion_started/completed/failed` | operatorId, personId, code on failure, durationMs |
| `archive_export_started/completed/failed` | operatorId when single, personId, byteCount/result/code, durationMs |
| `batch_export_started/completed/failed` | operatorId, itemCount, byteCount/result/code, durationMs |

---

### Task 1: Production file logging and rotation

**Files:**

- Modify: `src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java`
- Modify: `src/main/resources/application-prod.yml`

**Interfaces:**

- Consumes: Spring Boot production profile loading already exercised by `ProductionProfileConfigurationTest.environment(...)`.
- Produces: `logging.file.name`, `logging.pattern.correlation`, and `logging.logback.rollingpolicy.*` properties consumed by Logback and the later UOS unit.

- [ ] **Step 1: Write the failing configuration tests**

Add tests that resolve the common and production YAML together:

```java
@Test
void writesAndRotatesProductionLogsBesideTheWorkingDirectory() throws IOException {
    String[] files = {
        "src/main/resources/application.yml",
        "src/main/resources/application-prod.yml"
    };
    assertThat(resolvedProperty("logging.file.name", Map.of(), files))
        .isEqualTo("./log/personnel-archive-service.log");
    assertThat(resolvedProperty("logging.pattern.correlation", Map.of(), files))
        .contains("requestId");
    assertThat(resolvedProperty("logging.logback.rollingpolicy.max-file-size", Map.of(), files))
        .isEqualTo("20MB");
    assertThat(resolvedProperty("logging.logback.rollingpolicy.max-history", Map.of(), files))
        .isEqualTo("30");
    assertThat(resolvedProperty("logging.logback.rollingpolicy.total-size-cap", Map.of(), files))
        .isEqualTo("1GB");
    assertThat(resolvedProperty("logging.logback.rollingpolicy.clean-history-on-start", Map.of(), files))
        .isEqualTo("false");
}

@Test
void allowsOperationsToOverrideProductionLogDestinations() throws IOException {
    Map<String, String> overrides = Map.of(
        "PA_LOG_FILE", "/opt/personnel-archive-service/log/personnel-archive-service.log",
        "PA_LOG_ARCHIVE_PATTERN", "/opt/personnel-archive-service/log/personnel-archive-service.%d{yyyy-MM-dd}.%i.log.gz"
    );
    assertThat(resolvedProperty("logging.file.name", overrides,
        "src/main/resources/application.yml", "src/main/resources/application-prod.yml"))
        .isEqualTo("/opt/personnel-archive-service/log/personnel-archive-service.log");
    assertThat(resolvedProperty("logging.logback.rollingpolicy.file-name-pattern", overrides,
        "src/main/resources/application.yml", "src/main/resources/application-prod.yml"))
        .startsWith("/opt/personnel-archive-service/log/");
}
```

- [ ] **Step 2: Run the test and confirm it fails on missing properties**

Run:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw -Dtest=ProductionProfileConfigurationTest test
```

Expected: the new tests fail because `application-prod.yml` has no file or rolling configuration.

- [ ] **Step 3: Add the minimal production logging configuration**

Append this exact block to `application-prod.yml`:

```yaml
logging:
  file:
    name: ${PA_LOG_FILE:./log/personnel-archive-service.log}
  pattern:
    correlation: "[requestId=%X{requestId:-system}] "
  logback:
    rollingpolicy:
      file-name-pattern: ${PA_LOG_ARCHIVE_PATTERN:./log/personnel-archive-service.%d{yyyy-MM-dd}.%i.log.gz}
      max-file-size: ${PA_LOG_MAX_FILE_SIZE:20MB}
      max-history: ${PA_LOG_MAX_HISTORY:30}
      total-size-cap: ${PA_LOG_TOTAL_SIZE_CAP:1GB}
      clean-history-on-start: false
```

- [ ] **Step 4: Run the focused configuration tests**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit only this task's files**

```bash
git add -- \
  src/main/resources/application-prod.yml \
  src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java
git commit --only -m "feat(service): configure production file logging" -- \
  src/main/resources/application-prod.yml \
  src/test/java/com/company/personnelarchive/configuration/ProductionProfileConfigurationTest.java
```

---

### Task 2: Request correlation and generic HTTP completion logs

**Files:**

- Create: `src/main/java/com/company/personnelarchive/common/RequestCorrelation.java`
- Create: `src/main/java/com/company/personnelarchive/common/RequestCorrelationFilter.java`
- Create: `src/main/java/com/company/personnelarchive/common/OperationalLog.java`
- Create: `src/test/java/com/company/personnelarchive/support/LogCapture.java`
- Create: `src/test/java/com/company/personnelarchive/common/RequestCorrelationFilterTest.java`
- Modify: `src/main/java/com/company/personnelarchive/auth/BearerTokenFilter.java`
- Modify: `src/main/java/com/company/personnelarchive/configuration/SecurityConfiguration.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/CorsConfigurationTest.java`

**Interfaces:**

- Produces: `RequestCorrelation.RESPONSE_HEADER`, `REQUEST_ID_ATTRIBUTE`, `USER_ID_ATTRIBUTE`, `currentId()`, and `currentIdOrNew()`.
- Produces: `OperationalLog.started()`, `elapsedMillis(long)`, and `afterCommit(Runnable)` for all feature services.
- Consumes: `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` after MVC dispatch; never consumes `request.getQueryString()` or request headers.

- [ ] **Step 1: Add the scoped log-capture test helper**

Implement `LogCapture` as an `AutoCloseable` that attaches a `ListAppender<ILoggingEvent>` directly to one Logback logger, saves/restores its level, detaches on close, and exposes `List<String> messages()` using `ILoggingEvent.getFormattedMessage()`. It must never print captured messages itself.

Core interface:

```java
public final class LogCapture implements AutoCloseable {
    public static LogCapture attach(Class<?> type);
    public List<String> messages();
    public String joined();
    @Override public void close();
}
```

- [ ] **Step 2: Write failing request-correlation tests**

Cover all of these in `RequestCorrelationFilterTest`:

```java
@Test
void createsServerOwnedIdExposesItAndRemovesMdcAfterTheRequest() throws Exception;

@Test
void logsOnlyTheMatchedRouteAndNeverTheQueryOrAuthorizationHeader() throws Exception;

@Test
void cleansMdcWhenTheFilterChainThrows() throws Exception;
```

The second test must send sentinel values in a query string and Authorization header, set the request attribute `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` to `/api/v1/persons/{personId}`, then assert the log contains the template and does not contain either sentinel. Inside the mock chain, assert `MDC.get("requestId")` equals the response `X-Request-Id`; after filtering, assert MDC is empty.

- [ ] **Step 3: Run the new test and verify the classes are missing**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw -Dtest=RequestCorrelationFilterTest test
```

Expected: test compilation fails because the correlation classes do not exist.

- [ ] **Step 4: Implement the correlation holder and operational timing helper**

`RequestCorrelation` must be non-instantiable and expose only generated server state:

```java
public final class RequestCorrelation {
    public static final String MDC_KEY = "requestId";
    public static final String RESPONSE_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = RequestCorrelation.class.getName() + ".requestId";
    public static final String USER_ID_ATTRIBUTE = RequestCorrelation.class.getName() + ".userId";

    public static Optional<String> currentId() {
        return Optional.ofNullable(MDC.get(MDC_KEY)).filter(value -> !value.isBlank());
    }

    public static String currentIdOrNew() {
        return currentId().orElseGet(() -> UUID.randomUUID().toString());
    }
}
```

`OperationalLog` must use monotonic time and delay transactional success until commit:

```java
public final class OperationalLog {
    public static long started() { return System.nanoTime(); }
    public static long elapsedMillis(long started) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
    }
}
```

- [ ] **Step 5: Implement the highest-precedence filter**

`RequestCorrelationFilter` must:

- generate `UUID.randomUUID().toString()` without reading any incoming ID header;
- set the request attribute and response header before calling the chain;
- put the ID in MDC for the entire chain;
- log `http_request_completed` in `finally` with method, matched route or `UNMATCHED`, status, request user-ID attribute or `anonymous`, `COMPLETED`/`THROWN`, and duration;
- remove the MDC entry in the outermost `finally`.

Use `@Component` and `@Order(Ordered.HIGHEST_PRECEDENCE)`. Convert the route attribute with `String.valueOf(...)` only after confirming it exists; never fall back to the raw URI.

- [ ] **Step 6: Attach authenticated user ID and expose the response header**

In `BearerTokenFilter`, after a token authenticates, add:

```java
request.setAttribute(RequestCorrelation.USER_ID_ATTRIBUTE, principal.userId());
```

In `SecurityConfiguration.corsConfigurationSource()`, expose both headers:

```java
configuration.setExposedHeaders(List.of("Content-Disposition", RequestCorrelation.RESPONSE_HEADER));
```

Extend `CorsConfigurationTest` with an actual cross-origin GET (not only a preflight request) and assert `Access-Control-Expose-Headers` includes `X-Request-Id`; CORS preflight responses do not need to emit the expose-header list.

- [ ] **Step 7: Run focused tests**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=RequestCorrelationFilterTest,CorsConfigurationTest test
```

Expected: PASS; sentinel query and authorization values are absent from captured logs.

- [ ] **Step 8: Commit only request-correlation files**

```bash
git add -- \
  src/main/java/com/company/personnelarchive/common/RequestCorrelation.java \
  src/main/java/com/company/personnelarchive/common/RequestCorrelationFilter.java \
  src/main/java/com/company/personnelarchive/common/OperationalLog.java \
  src/main/java/com/company/personnelarchive/auth/BearerTokenFilter.java \
  src/main/java/com/company/personnelarchive/configuration/SecurityConfiguration.java \
  src/test/java/com/company/personnelarchive/support/LogCapture.java \
  src/test/java/com/company/personnelarchive/common/RequestCorrelationFilterTest.java \
  src/test/java/com/company/personnelarchive/configuration/CorsConfigurationTest.java
git commit --only -m "feat(service): correlate and log HTTP requests" -- \
  src/main/java/com/company/personnelarchive/common/RequestCorrelation.java \
  src/main/java/com/company/personnelarchive/common/RequestCorrelationFilter.java \
  src/main/java/com/company/personnelarchive/common/OperationalLog.java \
  src/main/java/com/company/personnelarchive/auth/BearerTokenFilter.java \
  src/main/java/com/company/personnelarchive/configuration/SecurityConfiguration.java \
  src/test/java/com/company/personnelarchive/support/LogCapture.java \
  src/test/java/com/company/personnelarchive/common/RequestCorrelationFilterTest.java \
  src/test/java/com/company/personnelarchive/configuration/CorsConfigurationTest.java
```

---

### Task 3: Safe exception summaries and request-ID error responses

**Files:**

- Create: `src/main/java/com/company/personnelarchive/common/SafeExceptionSummary.java`
- Create: `src/test/java/com/company/personnelarchive/common/SafeExceptionSummaryTest.java`
- Create: `src/test/java/com/company/personnelarchive/common/GlobalExceptionHandlerLoggingTest.java`
- Modify: `src/main/java/com/company/personnelarchive/common/ApiError.java`
- Modify: `src/main/java/com/company/personnelarchive/common/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/company/personnelarchive/configuration/SecurityConfiguration.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/ApiContractSerializationTest.java`

**Interfaces:**

- Produces: `SafeExceptionSummary.describe(Throwable)` returning one message-free line.
- Produces: `ApiError.withRequestId(ErrorCode, String, String)` with `details={"requestId": ...}`.
- Consumes: `RequestCorrelation.currentIdOrNew()` from Task 2.

- [ ] **Step 1: Write message-redaction and error-envelope tests**

`SafeExceptionSummaryTest` must construct nested exceptions whose messages contain unique password, identity-card, workbook-path, and SQL sentinels. Assert the summary contains top-level and cause class names plus a test method location, and contains none of the sentinels.

`GlobalExceptionHandlerLoggingTest` must set `MDC.put("requestId", fixedUuid)`, call `handleUnexpected(...)`, and assert:

- HTTP 500;
- `response.getBody().details().get("requestId")` is the same UUID;
- captured ERROR output includes `event=request_failed` and exception type;
- captured output excludes the exception message sentinel.

Add an ordinary business-error case asserting `event=request_business_rejected code=NOT_FOUND` and absence of its custom message.

- [ ] **Step 2: Run the focused tests and confirm failure**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=SafeExceptionSummaryTest,GlobalExceptionHandlerLoggingTest,ApiContractSerializationTest test
```

Expected: compilation or assertions fail because the summary/factory/log events do not exist.

- [ ] **Step 3: Implement the bounded exception summary**

`SafeExceptionSummary.describe` must traverse at most five unique causes using identity equality and include at most twelve stack frames from the top-level throwable. Render only:

```text
exceptionType=<class> causeTypes=<class,class> stack=<class#method:line|...>
```

Do not call or interpolate `getMessage()`, `toString()`, localized messages, suppressed exception text, file names, or exception objects.

- [ ] **Step 4: Add the request-ID API error factory**

Keep `ApiError.of(...)` unchanged so existing three-field business errors remain compatible. Add:

```java
public static ApiError withRequestId(ErrorCode code, String message, String requestId) {
    return new ApiError(code.name(), message, Instant.now().toString(), Map.of("requestId", requestId));
}
```

Add a serialization assertion that this factory has exactly four JSON properties and the details value equals the supplied UUID.

- [ ] **Step 5: Replace exception-handler logging with fixed safe events**

Use fixed events in every handler:

- business: WARN `event=request_business_rejected code={}`;
- validation: WARN `event=request_validation_rejected category=bean_validation`;
- malformed body/multipart: WARN category `malformed_request`;
- upload too large: WARN category `upload_too_large`;
- forbidden: WARN category `forbidden`;
- unexpected: ERROR `event=request_failed {}` with only `SafeExceptionSummary.describe(exception)`.

The unexpected response uses `ApiError.withRequestId` and the current MDC request ID. Security entry-point and access-denied responses in `SecurityConfiguration.write(...)` also use `ApiError.withRequestId` so filter-chain rejections can be correlated.

- [ ] **Step 6: Run the focused tests**

Run the Step 2 command. Expected: PASS and no secret sentinel in captured output.

- [ ] **Step 7: Commit only safe-error files**

```bash
git add -- \
  src/main/java/com/company/personnelarchive/common/SafeExceptionSummary.java \
  src/main/java/com/company/personnelarchive/common/ApiError.java \
  src/main/java/com/company/personnelarchive/common/GlobalExceptionHandler.java \
  src/main/java/com/company/personnelarchive/configuration/SecurityConfiguration.java \
  src/test/java/com/company/personnelarchive/common/SafeExceptionSummaryTest.java \
  src/test/java/com/company/personnelarchive/common/GlobalExceptionHandlerLoggingTest.java \
  src/test/java/com/company/personnelarchive/configuration/ApiContractSerializationTest.java
git commit --only -m "feat(service): add safe request failure diagnostics" -- \
  src/main/java/com/company/personnelarchive/common/SafeExceptionSummary.java \
  src/main/java/com/company/personnelarchive/common/ApiError.java \
  src/main/java/com/company/personnelarchive/common/GlobalExceptionHandler.java \
  src/main/java/com/company/personnelarchive/configuration/SecurityConfiguration.java \
  src/test/java/com/company/personnelarchive/common/SafeExceptionSummaryTest.java \
  src/test/java/com/company/personnelarchive/common/GlobalExceptionHandlerLoggingTest.java \
  src/test/java/com/company/personnelarchive/configuration/ApiContractSerializationTest.java
```

---

### Task 4: Audit correlation and startup diagnostics

**Files:**

- Create: `src/main/java/com/company/personnelarchive/configuration/SafeJdbcTarget.java`
- Create: `src/main/java/com/company/personnelarchive/configuration/OperationalStartupLogger.java`
- Create: `src/test/java/com/company/personnelarchive/audit/AuditRequestCorrelationTest.java`
- Create: `src/test/java/com/company/personnelarchive/configuration/OperationalStartupLoggerTest.java`
- Modify: `src/main/java/com/company/personnelarchive/audit/AuditService.java`

**Interfaces:**

- Produces: package-private `SafeJdbcTarget.parse(String)` returning host, port, schema or `unknown/0/unknown`.
- Produces: `OperationalStartupLogger.onReady(ApplicationReadyEvent)` and package-private `logReady(Duration)` for direct tests.
- Consumes: `RequestCorrelation.currentIdOrNew()` so audit-table request IDs align with HTTP logs.

- [ ] **Step 1: Write failing audit and startup tests**

`AuditRequestCorrelationTest` sets a fixed MDC request ID, calls each audit method with mocks, captures the final mapper requestId argument, and asserts it matches. Clear MDC and assert a fallback value matches the UUID shape.

`OperationalStartupLoggerTest` uses `MockEnvironment` with a JDBC URL containing secret query sentinels, prod profile, port, and log path. Capture output and assert the ready event contains only:

```text
dbHost=127.0.0.1 dbPort=3306 dbSchema=personnel_archive
```

It must not contain the query string, password sentinel, security-key values, or full JDBC URL. Add invalid host/schema/newline cases that map to `unknown` rather than being logged.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=AuditRequestCorrelationTest,OperationalStartupLoggerTest test
```

- [ ] **Step 3: Correlate audit writes**

Replace each `UUID.randomUUID().toString()` argument in `AuditService` with one private method:

```java
private static String requestId() {
    return RequestCorrelation.currentIdOrNew();
}
```

Do not change audit actions, target IDs, success/failure semantics, transactions, or database schema.

- [ ] **Step 4: Implement strict JDBC target parsing**

Accept only `jdbc:mysql://host[:port]/schema` with optional query/semicolon suffix. Permit bracketed IPv6, default port to 3306, validate port 1–65535, and require host/schema tokens to match bounded `[A-Za-z0-9_.-]+`. Return unknown fields on any mismatch. Never retain the original URL in the record.

- [ ] **Step 5: Implement the ready logger**

Inject `Environment` and `ArchiveProperties`, listen for `ApplicationReadyEvent`, and emit one `service_ready` INFO record. The logger reads the implementation version with fallback `development`, `java.version`, a newline-stripped active profile token, parsed numeric port, normalized absolute log path, the safe JDBC target, one aggregate `securityKeysConfigured` boolean, and the event duration.

Do not log environment maps, configuration values, usernames, key lengths, hashes, or database URLs. Flyway's existing INFO lifecycle remains enabled and unchanged.

- [ ] **Step 6: Run focused tests**

Run Step 2. Expected: PASS.

- [ ] **Step 7: Commit startup and audit correlation**

```bash
git add -- \
  src/main/java/com/company/personnelarchive/audit/AuditService.java \
  src/main/java/com/company/personnelarchive/configuration/SafeJdbcTarget.java \
  src/main/java/com/company/personnelarchive/configuration/OperationalStartupLogger.java \
  src/test/java/com/company/personnelarchive/audit/AuditRequestCorrelationTest.java \
  src/test/java/com/company/personnelarchive/configuration/OperationalStartupLoggerTest.java
git commit --only -m "feat(service): correlate audits and startup logs" -- \
  src/main/java/com/company/personnelarchive/audit/AuditService.java \
  src/main/java/com/company/personnelarchive/configuration/SafeJdbcTarget.java \
  src/main/java/com/company/personnelarchive/configuration/OperationalStartupLogger.java \
  src/test/java/com/company/personnelarchive/audit/AuditRequestCorrelationTest.java \
  src/test/java/com/company/personnelarchive/configuration/OperationalStartupLoggerTest.java
```

---

### Task 5: Authentication lifecycle logging without credentials

**Files:**

- Create: `src/test/java/com/company/personnelarchive/auth/AuthOperationalLoggingTest.java`
- Modify: `src/main/java/com/company/personnelarchive/auth/AuthService.java`

**Interfaces:**

- Consumes: `OperationalLog.started()`, `elapsedMillis(...)`, and `afterCommit(...)`.
- Produces: fixed auth events from the vocabulary table; no DTO or controller changes.

- [ ] **Step 1: Write failing redaction tests**

Build mocked success, invalid-credential, locked, invalid-refresh, successful-refresh, and logout scenarios. Use unique username, password, access-token, and refresh-token sentinels. Assert the correct event and fixed error code appears and every sentinel is absent from `LogCapture.joined()`.

For successful transactional operations, direct unit construction has no active synchronization, so `OperationalLog.afterCommit(...)` emits immediately and is testable.

- [ ] **Step 2: Run the focused auth tests and confirm missing events**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=AuthOperationalLoggingTest,AuthServiceSessionPersistenceTest,LoginFailureRecorderTest test
```

- [ ] **Step 3: Add login, refresh, and logout checkpoints**

Add one SLF4J logger to `AuthService`. Each public method records `started` before inspecting input, emits a start event without input values, emits a fixed failure event before each `BusinessException`, and schedules success with `OperationalLog.afterCommit`. Only internal numeric user ID, fixed `ErrorCode.name()`, and duration may be arguments.

For an unknown username use `userId=unknown`; never substitute the username. Do not log the returned `TokenResponse`, token expiry values, password encoder result, token hashes, or session hashes.

- [ ] **Step 4: Run focused auth tests**

Run Step 2. Expected: PASS with all sentinel redaction assertions.

- [ ] **Step 5: Commit auth logging only**

```bash
git add -- \
  src/main/java/com/company/personnelarchive/auth/AuthService.java \
  src/test/java/com/company/personnelarchive/auth/AuthOperationalLoggingTest.java
git commit --only -m "feat(service): log safe authentication checkpoints" -- \
  src/main/java/com/company/personnelarchive/auth/AuthService.java \
  src/test/java/com/company/personnelarchive/auth/AuthOperationalLoggingTest.java
```

---

### Task 6: Import and history checkpoints

**Files:**

- Create: `src/test/java/com/company/personnelarchive/archiveimport/ImportOperationalLoggingTest.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ImportBatchService.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ImportItemService.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ImportHistoryService.java`

**Interfaces:**

- Consumes: `OperationalLog`, `SafeExceptionSummary`, and existing import DTO/status enums.
- Produces: batch/item/decision/history events; persistence and duplicate-decision contracts remain unchanged.

- [ ] **Step 1: Write failing import-log and redaction tests**

Cover:

- a successful imported item logs batch/item/person IDs, file size, section counts, result, and no filename/content;
- a duplicate logs `PENDING_DECISION` without name, identity suffix, or employee number;
- `SKIP` and `OVERWRITE` log only the decision enum and internal IDs;
- `recordFailure` logs only the fixed code, not the safe client message because validation messages can contain sheet/cell text;
- `ImportBatchService` catches a `RuntimeException` whose message contains password, workbook path, and cell sentinels, logs `SafeExceptionSummary`, records `INTERNAL_ERROR`, and leaks none of them;
- history logs page/count metadata but not operator name, filename, or persisted message.

- [ ] **Step 2: Run focused import tests and verify missing events**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=ImportOperationalLoggingTest,ImportItemServiceTest,ImportHistoryServiceTest test
```

- [ ] **Step 3: Add batch-level events and safe unknown-error diagnostics**

In `ImportBatchService`:

- emit `import_batch_started` after validating only the list count;
- emit one fixed per-item rejection/failure event from catches without filename or exception message;
- use `SafeExceptionSummary.describe(exception)` only for the unknown runtime catch;
- after `refreshBatch`, count result statuses and emit `import_batch_completed` with the fixed counters and duration;
- wrap `decide` so data-integrity and business failures emit fixed decision failure codes without logging the decision request unless it is exactly `OVERWRITE` or `SKIP`.

- [ ] **Step 4: Add item transaction checkpoints**

In `ImportItemService`, add events at these exact boundaries:

- before XLSX parsing: `import_item_started` with batchId, itemOrder, fileSize;
- after parsing: `import_item_parsed` with counts of main education, work, reward, and family lists only;
- after insert/pending/failure/decision writes: schedule `import_item_completed` or `import_decision_completed` with `OperationalLog.afterCommit`;
- on rejected decisions: emit `import_decision_failed` with the fixed code before rethrowing.

Never pass `filename`, `safeFilename`, `fileHash`, `ArchiveDocument`, `PreparedArchive`, duplicate summary, identity HMAC, employee number, result message, or workbook bytes to a logger.

- [ ] **Step 5: Add history metadata event**

`ImportHistoryService.history` measures the call and emits `import_history_completed` after audit success with operatorId, page, size, returnedCount, total, and duration. It never logs the mapped content.

- [ ] **Step 6: Run focused import tests**

Run Step 2. Expected: PASS and no redaction sentinel appears.

- [ ] **Step 7: Commit import logging only**

```bash
git add -- \
  src/main/java/com/company/personnelarchive/archiveimport/ImportBatchService.java \
  src/main/java/com/company/personnelarchive/archiveimport/ImportItemService.java \
  src/main/java/com/company/personnelarchive/archiveimport/ImportHistoryService.java \
  src/test/java/com/company/personnelarchive/archiveimport/ImportOperationalLoggingTest.java
git commit --only -m "feat(service): log safe import checkpoints" -- \
  src/main/java/com/company/personnelarchive/archiveimport/ImportBatchService.java \
  src/main/java/com/company/personnelarchive/archiveimport/ImportItemService.java \
  src/main/java/com/company/personnelarchive/archiveimport/ImportHistoryService.java \
  src/test/java/com/company/personnelarchive/archiveimport/ImportOperationalLoggingTest.java
```

---

### Task 7: Query, sensitive-view, photo, and deletion checkpoints

**Files:**

- Create: `src/test/java/com/company/personnelarchive/person/PersonOperationalLoggingTest.java`
- Modify: `src/main/java/com/company/personnelarchive/person/PersonQueryService.java`
- Modify: `src/main/java/com/company/personnelarchive/person/PersonDeletionService.java`

**Interfaces:**

- Consumes: `OperationalLog` and existing service results.
- Produces: person/history/search/sensitive/photo/deletion events; response DTOs and authorization are unchanged.

- [ ] **Step 1: Write failing service-log redaction tests**

Use mapper rows with unique name, phone, identity, address, photo filename, family-search term, and deletion-reason sentinels. Exercise list, detail, family search, sensitive identity/phone, photo success/missing, deletion success/validation/missing. Assert the event vocabulary fields appear, but all sentinels are absent.

- [ ] **Step 2: Run focused person tests and verify missing events**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=PersonOperationalLoggingTest,PersonQueryServiceTest,PersonDeletionServiceTest test
```

- [ ] **Step 3: Add query and sensitive-access events**

Measure every public `PersonQueryService` method. Emit only:

- list: operator/page/size/returned/total/duration;
- detail: operator/person/duration;
- family search: page/size/returned/total/duration;
- sensitive value: operator/person/fixed field enum/duration;
- photo: operator/person/byte count/duration.

On missing photo or active record, rely on the global fixed business rejection plus generic HTTP event; do not log the exception message or queried name. No logger call receives a DTO, row, filter value, decrypted string, photo filename, or photo bytes.

- [ ] **Step 4: Add transaction-aware deletion events**

`PersonDeletionService.delete` emits a start event with operator/person IDs, fixed failure codes for invalid reason and missing active row, and schedules `person_deletion_completed` only after transaction commit. The normalized reason variable must never be a logger argument.

- [ ] **Step 5: Run focused person tests**

Run Step 2. Expected: PASS with redaction assertions.

- [ ] **Step 6: Commit person logging only**

```bash
git add -- \
  src/main/java/com/company/personnelarchive/person/PersonQueryService.java \
  src/main/java/com/company/personnelarchive/person/PersonDeletionService.java \
  src/test/java/com/company/personnelarchive/person/PersonOperationalLoggingTest.java
git commit --only -m "feat(service): log safe archive access checkpoints" -- \
  src/main/java/com/company/personnelarchive/person/PersonQueryService.java \
  src/main/java/com/company/personnelarchive/person/PersonDeletionService.java \
  src/test/java/com/company/personnelarchive/person/PersonOperationalLoggingTest.java
```

---

### Task 8: Single and batch export checkpoints

**Files:**

- Create: `src/test/java/com/company/personnelarchive/archiveexport/ExportOperationalLoggingTest.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveexport/ArchiveExportService.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveexport/BatchArchiveExportService.java`

**Interfaces:**

- Consumes: `OperationalLog`, `SafeExceptionSummary`, existing generated archive/bundle records.
- Produces: single/batch export events; workbook contents and audit timing remain unchanged.

- [ ] **Step 1: Write failing export redaction tests**

Test single success, missing person, template failure with a secret exception-message sentinel, batch success, and batch partial failure. Assert internal IDs/counts/byte counts/fixed codes appear; names, generated filenames, identity/phone values, workbook bytes, and exception messages do not.

- [ ] **Step 2: Run focused export tests**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw \
  -Dtest=ExportOperationalLoggingTest,ArchiveExportServiceTest,BatchArchiveExportServiceTest test
```

Expected before implementation: missing event assertions fail.

- [ ] **Step 3: Add single-export events**

`generate(...)` emits start and completed events around `generateForBatch`, with operatorId, personId, byte count, and duration. `generateForBatch(...)` emits a fixed failure code for business errors; before converting `IOException`/`RuntimeException` to `INTERNAL_ERROR`, emit one ERROR event containing only `SafeExceptionSummary.describe(exception)`. Never log `GeneratedArchive.filename()`.

- [ ] **Step 4: Add batch-export events**

`BatchArchiveExportService.generate` starts timing before `parseIds`, logs only a bounded requested count (`0` for null, otherwise at most `101`), emits completed only after the full ZIP and every audit row succeed, and emits fixed failures for invalid selection, missing person, size limit, or internal I/O. Fields are operatorId, parsed item count when available, aggregate byte count on success, code, and duration. Do not log the external string IDs before validation or ZIP entry names.

- [ ] **Step 5: Run focused export tests**

Run Step 2. Expected: PASS and all sentinels absent.

- [ ] **Step 6: Commit export logging only**

```bash
git add -- \
  src/main/java/com/company/personnelarchive/archiveexport/ArchiveExportService.java \
  src/main/java/com/company/personnelarchive/archiveexport/BatchArchiveExportService.java \
  src/test/java/com/company/personnelarchive/archiveexport/ExportOperationalLoggingTest.java
git commit --only -m "feat(service): log safe export checkpoints" -- \
  src/main/java/com/company/personnelarchive/archiveexport/ArchiveExportService.java \
  src/main/java/com/company/personnelarchive/archiveexport/BatchArchiveExportService.java \
  src/test/java/com/company/personnelarchive/archiveexport/ExportOperationalLoggingTest.java
```

---

### Task 9: Logging documentation and full verification

**Files:**

- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `../CODE_GUIDE.md`
- Modify: `../AGENTS.md`

**Interfaces:**

- Consumes: all implemented logging behavior.
- Produces: synchronized operator and source-map documentation; no runtime code.

- [ ] **Step 1: Update current-state documentation**

Document exact production defaults and commands:

```text
PA_LOG_FILE=./log/personnel-archive-service.log
PA_LOG_ARCHIVE_PATTERN=./log/personnel-archive-service.%d{yyyy-MM-dd}.%i.log.gz
20 MB / 30 periods / 1 GB
X-Request-Id
tail -F /opt/personnel-archive-service/log/personnel-archive-service.log
journalctl -u personnel-archive-service -f
```

State that file logs are diagnostic, database audit rows remain authoritative, and the forbidden sensitive fields are never logged. Add source-map rows for the new common/configuration classes and tests. Do not yet claim the UOS bundle exists; that becomes current only after the second plan.

- [ ] **Step 2: Run formatting and sensitive-token static checks**

```bash
git diff --check
rg -n "log\.(trace|debug)|logging\.level\..*DEBUG|show-sql|authorization|request\.getQueryString|request\.getHeader" \
  src/main src/test README.md AGENTS.md
```

Inspect every match. The request filter tests may intentionally create sentinel headers, but production log arguments must contain none of the forbidden sources.

- [ ] **Step 3: Run the complete Java 21 verification**

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle ./mvnw verify
```

Expected: all unit tests pass; MySQL Testcontainers run if Docker is reachable and are explicitly reported as skipped otherwise. Do not point tests at any existing database.

- [ ] **Step 4: Inspect generated reports and JAR**

```bash
find target/surefire-reports -type f -name '*.txt' -print -exec tail -n 4 {} \;
jar tf target/personnel-archive-service-0.1.0-SNAPSHOT.jar | rg \
  'application-prod.yml|db/migration/V(1|14)__|personnel-archive-template-v1.xlsx'
```

Expected: no failing report; the executable JAR contains prod logging configuration, MySQL V1 and V14 endpoints, and the production workbook template.

- [ ] **Step 5: Confirm user-owned Excel changes remain present**

```bash
git status --short
git diff -- src/main/java/com/company/personnelarchive/archiveimport/ExcelArchiveReader.java
git diff --cached -- src/test/java/com/company/personnelarchive/archiveimport/ExcelNumericDatePrecisionTest.java
```

Expected: the existing source modification and staged test remain unless the user independently committed them; no reset or checkout has occurred.

- [ ] **Step 6: Commit tracked documentation with exact paths**

```bash
git add -- README.md AGENTS.md
git commit --only -m "docs(service): document operational logging" -- README.md AGENTS.md
```

The root files are outside the service Git repository; update them with `apply_patch`, verify them, and report that they are local companion documentation rather than part of this child-repository commit.

- [ ] **Step 7: Record the logging-plan checkpoint**

Run:

```bash
git status --short --branch
git log --oneline --decorate -10
```

Record the Java version, test counts, Docker/Testcontainers result, JAR path, and exact remaining user-owned changes before beginning the UOS bundle plan.
