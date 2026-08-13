# SERVICE KNOWLEDGE BASE

Inherits all product, data, Excel, and security rules from `../AGENTS.md`. This directory owns the sole backend and all authoritative business decisions.

## CURRENT SERVICE STATUS

- Implemented APIs cover authentication/session lifecycle, manual batch import, duplicate decisions, persistent paginated import history, active archive list/complete detail with masked identity-card/phone values, ADMIN/ARCHIVE_EDITOR reasoned soft deletion, authenticated no-store single-field reveal and photo bytes, family-member search, single XLSX export, and selected batch ZIP export.
- MySQL migrations run through V14: V1–V8 establish the schema/default administrator, V9 adds nullable date/age projections, immutable V10/V11 backfill projections with frozen V1/V2 parsing, V12 expands import-result messages, V13 validates then canonicalizes all active legacy age/date text and projections, and V14 rewrites active main-education/work open-range `raw_text` as `起点—至今`.
- Import decisions use a bounded in-memory parsed-document store, so a service restart intentionally makes an outstanding decision stale and requires re-upload.
- Every exact nonblank family age and business date is canonicalized before persistence: ages become decimal integer strings, while dates become `yyyy`, `yyyy-MM`, or `yyyy-MM-dd` without adding absent precision. Numeric Excel date cells follow their visible cell-format precision rather than hidden month/day components in the serial value. Repeated-section open ranges such as `2018-06至今` are stored as `2018-06—至今`, while their structured endpoint remains `end_date='至今'` with null numeric projections; raw range text over 128 characters fails before regex/range parsing. The template's exact `DATEDIF(家庭成员年龄计算!D<n>,TODAY(),"y")` formula is evaluated once and canonicalized. Deterministic Chinese/Arabic inputs—including mixed components such as `二〇二四.五.三`—are supported; fuzzy nonblank values such as `三十多岁` fail that file with aggregated business-field/cell errors and never reach an active snapshot. Query and export pass stored canonical strings through unchanged.
- Desktop folder scanning and source-file moves are intentionally client-side and are implemented by the current client; the service remains authoritative for import success and exposes no local-path API.
- `application.yml` contains shared settings and defaults to `dev`; `application-dev.yml` uses the existing local MySQL service, while `application-prod.yml` uses MySQL with environment-injected production credentials. Both profiles scan `classpath:db/migration`, default to HTTP port `18080`, and retain `PA_SERVER_PORT` as the deployment override. No external `config/` directory is used.
- Update this section in the same change whenever an endpoint, migration, persistence rule, or supported workflow changes.

## STACK AND SHAPE

- Java 21, Spring Boot 4.x/Spring MVC, Maven Wrapper.
- Spring Security, Bean Validation, official MyBatis Spring Boot Starter, Flyway MySQL, MySQL Connector/J, Apache POI XSSF, and pinned Hutool Core for Chinese counting-number conversion.
- JUnit 5 and Testcontainers with real MySQL for integration tests; do not substitute H2.
- Modular monolith organized by feature: `auth`, `person`, `family`, `archiveimport`, `archiveexport`, `audit`, and `configuration`.
- Controllers handle HTTP/DTO boundaries only. Application services own transactions and workflows; mappers own SQL.

## DATABASE RULES

- Database name: `personnel_archive`. Both `dev` and `prod` use MySQL 8.4-compatible databases; `dev` targets the pinned local MySQL LTS image, while `prod` receives its connection and credentials from the deployment environment.
- Flyway is the only schema-change mechanism and uses `classpath:db/migration`. V8 creates the fixed enabled `hr_6298bc833379` user with display name “人资部门”, a BCrypt cost 12 hash, and its sole `ADMIN` association; a same-named migration target is deterministically reset and unlocked. V10/`SourceValueParserV1` and V11/`SourceValueParserV2` are immutable historical migration contracts. V13 uses frozen `SourceValueNormalizerV3`, processes only active snapshots, validates all affected rows before updates, refuses ambiguous data without echoing its value, and leaves soft-deleted history unchanged; all validation, canonical-update and combination-text scans use primary-key keyset pages with `LIMIT 500`, and each update page closes its result set before batching writes. V14 rebuilds only active main-education/work open-range `raw_text`; it follows the same bounded closed-result-set keyset pattern and leaves endpoints, projections and soft-deleted rows unchanged. Any change that reinterprets persisted values or canonical output requires another normalizer and migration version. Disable production auto-DDL, never rewrite an applied migration, and never place the default administrator's plaintext password in the repository or logs.
- Use `BIGINT`, `snake_case`, explicit foreign keys/indexes, and MySQL `utf8mb4`.
- Include server-controlled `tenant_id`; version 1 always resolves it to `1` and ignores/rejects client input.
- `pa_person` owns snapshot state: `is_deleted` defaults to `0`; also record `deleted_at`, `deleted_by`, `delete_reason`, and `replaced_by_person_id`. Old children remain unchanged under the old `person_id`.
- Manual archive deletion accepts a trimmed, nonblank reason of at most 10,000 characters, stores it in the database's unbounded text type, and leaves `replaced_by_person_id`, child rows, photos, and import history unchanged.
- Enforce supplied nonblank employee-number and identity-card uniqueness only for active snapshots through nullable generated active-key columns. Blank/absent identifiers and deleted rows do not participate; never use `(value, is_deleted)` uniqueness.
- Encrypt full identity-card data with authenticated encryption; keep encryption/HMAC keys outside the database and repository.
- Store photos as bounded binary BLOB values with MIME, byte count, dimensions, filename, and SHA-256.
- Store an exact main-sheet family age as one canonical decimal string in `age_text` with the same nonnegative integer in `age_value`. Evaluate only the template's exact `DATEDIF(家庭成员年龄计算!D<n>,TODAY(),"y")` formula at import and canonicalize its Excel-equivalent result; never calculate a stored age during query/export. Reject fuzzy nonblank text. Store every business date in its `VARCHAR(64)` column as canonical `yyyy`, `yyyy-MM`, or `yyyy-MM-dd`; nullable `*_year`/`*_month`/`*_day` columns contain the same explicitly supplied precision and never fabricate missing components. Combination-row `raw_text` uses `起点—终点` or `起点—至今`; an open endpoint is stored separately as `至今` with null numeric projections.
- MySQL `DATETIME(6)` audit and snapshot timestamps use China wall-clock time, and every MySQL JDBC URL must set `connectionTimeZone=Asia/Shanghai`. Java exposes API timestamps as UTC `Instant`; do not compensate in a client.
- Never point automated tests at the user's running `local-mysql` container.

## API AND AUTH

- Prefix endpoints with `/api/v1`; expose request/response DTOs, never persistence entities.
- Validate at the boundary and return stable machine error codes plus safe Chinese messages.
- Every normal person/detail/duplicate/family/export query filters `pa_person.is_deleted = 0`. Escape `%`, `_`, and the escape character in family-name contains queries; paginate all matched active rows, with 20 rows as the default page size for personnel and family-member queries.
- Base detail DTOs expose every persisted workbook section while returning only front-four/back-three identity-card and phone masks; short nonblank values are fully masked. The service decrypts an identity card only long enough to produce that mask. A separate authenticated endpoint rechecks the active snapshot and returns one explicitly requested full identity-card or phone value with caching disabled and a value-free view audit. Photo metadata remains in detail; raw JPEG/PNG bytes use their own authenticated, no-store, audited endpoint.
- Use Spring Security with `ADMIN`, `ARCHIVE_EDITOR`, and `VIEWER` permissions.
- Restrict `POST /api/v1/persons/{personId}/deletion` to `ADMIN`/`ARCHIVE_EDITOR`; update only the tenant-scoped active row so repeated or stale attempts return `NOT_FOUND` without another audit event.
- Passwords use an adaptive hash. Access/session credentials expire and are revocable; store refresh tokens only as hashes.
- `AdminBootstrap` remains only as an explicit-variable compatibility path for databases with no users. Standard new MySQL databases already contain the V8 default user, so it must not run there.
- Record login, import, overwrite, skip, edit, delete, view, export, and administration actions without sensitive payloads; overwrite audit stores the old/new `person_id` and operator, while delete audit stores action/person/operator metadata but never the free-text reason.
- Import-history reads are tenant-scoped, authorized for `ADMIN`/`ARCHIVE_EDITOR`, and audited as views. Normalize upload names to a nonblank basename before parsing/persistence, return the actual decision operator for overwrite/skip, and never expose source bytes, stored file hashes, or client-local paths.
- Secrets come from environment/secret injection. Never commit database passwords, token keys, or field-encryption keys.

## EXCEL IMPORT

- Treat upload content as hostile: verify XLSX signature/type, size, template version, sheets, exact normalized labels, section order, values, and images. Historical section merges and row numbers may differ from the clean template.
- Configure Apache POI zip-bomb and memory limits before parsing.
- Use the V1 exact-label layout resolver for scalar fields and section boundaries; do not depend on invalid workbook named ranges or infer a field from fuzzy text.
- Do not require any workbook business field. Preserve blank and partially filled rows, including empty employee number, name, and identity card; preserve identifier, phone, postal, and ordinary text without losing leading zeros. Age/date spelling is intentionally canonicalized and is not a retained raw representation.
- Main-sheet family rows are authoritative and have no business count ceiling. Associate a helper-sheet birth date only through an explicit `家庭成员年龄计算!D<n>` formula reference or one unique normalized exact relationship/name match; otherwise keep the date blank and ignore unmatched helper residue.
- Use `SourceValueNormalizerV3` for age and scalar-date interpretation at the workbook boundary. It delegates deterministic interpretation to frozen V2/V1, accepts supported Chinese/Arabic components and `.`, `/`, or `-` forms, uses Hutool only for counting-number conversion and `java.time` for calendar validation, and emits only canonical precision-preserving text. Repeated-section date ranges use `SourceValueNormalizerV4`, which leaves V3 frozen, rejects raw input over 128 characters before regex/range parsing, and additionally treats exactly `--`, `---`, or `——` as one separator; V2 already accepts `～`. V4 still delegates both endpoints to V3, rejects longer/mixed runs, multiple ranges, fuzzy endpoints, and invalid dates, emits `起点—终点` for closed ranges, and emits `起点—至今` for every accepted open range, including input `2018-06至今`. `SnapshotWriter` must use the same V4 boundary when defensively rebuilding combination-row `raw_text`, while retaining non-date text. Parse the complete tail after the declaration's exact `时间：` label so an invalid suffix cannot hide behind a valid prefix. Evaluate only an exact template `DATEDIF(家庭成员年龄计算!D<n>,TODAY(),"y")` formula once with Excel-compatible blank/date-reference behavior; other formulas remain cached-value-only. Never infer fuzzy ages/dates or manufacture missing month/day components.
- Collect every detectable nonblank age/date problem in the workbook before calling `SnapshotWriter`. Return `IMPORT_FIELD_VALIDATION_FAILED` with bounded, newline-separated safe details naming the Chinese business field, sheet, cell, and correction guidance without the source value. Persist the same full message in `pa_import_item`; the failed file writes no archive rows and does not stop other files in its batch.
- Normalize supplied names with Unicode NFKC/trimmed whitespace for matching. Only a nonblank identity card is normalized, validated, encrypted, HMAC-indexed, and given a display suffix; an absent identity card remains `NULL` in all protected columns.
- Process one workbook per transaction. Batch aggregation happens outside those transactions.
- Match duplicates only when both incoming values are supplied and normalized name plus identity-card HMAC equal one active snapshot, then return `DUPLICATE_REQUIRES_DECISION` without writing archive data. Manual selection and desktop folder scanning call this same endpoint and decision service.
- A supplied identity card with a different normalized name is `IDENTITY_CARD_CONFLICT`; a supplied nonblank employee number owned by another snapshot is `EMPLOYEE_NO_CONFLICT`. Blank identifiers skip their conflict lookups, and the same name with a different identity card is not a duplicate.
- On decision, lock and recheck the active target. `SKIP` changes no archive table. `OVERWRITE` atomically soft-deletes the old root, inserts a complete new root/children/photo snapshot, and links old to new; never update/delete old children.
- If the target changed since precheck, return a stale-decision result. Any failure rolls back both the old-row soft delete and every new insert.
- Enforce the fixed-section capacities `5/16/4` for main education/work/reward rows and reject overflow; family rows are label-bounded and persist with any positive sort order. Never truncate.
- Delete framework upload temp files and application temp files on every success/failure path.

## EXCEL EXPORT

- Keep a cleaned, immutable, versioned template in application resources or configured read-only storage.
- Fill canonical date/age strings and canonicalized ordered-row `raw_text` directly from the database, and insert one validated photo in the template photo area. Never parse, reformat, or compute a family age/date during query or export. When a snapshot has more than six family rows, expand both family sections and shift the declaration/page breaks before writing every row.
- Fixed wording and layout come from the template, not database duplication.
- Stream the resulting `.xlsx`; do not retain it or upload it to object storage.
- Batch export accepts 1–100 distinct string IDs, caps aggregate uncompressed workbook bytes, fails before returning any ZIP when one reconstruction fails, prevents duplicate ZIP entry names, and records successful export audit rows only after the complete package is built.
- Test by reopening the workbook and checking important cell values, types, merged ranges, ordering, and the image. Do not compare whole XLSX binaries.

## TEST AND BUILD BASELINE

Establish Maven Wrapper commands; the minimum completion command is:

```text
./mvnw verify
```

Real-MySQL Testcontainers suites use `disabledWithoutDocker = true`: they run whenever Docker is reachable and are reported as skipped otherwise. A sandbox skip is not evidence that a migration or end-to-end database flow passed; release verification must run them with Docker available.

For macOS manual testing, the root `../启动本地测试.command` uses an already available MySQL service or safely starts the existing `local-mysql` container, runs this service with the `dev` profile, waits for `/api/v1`, and opens the canonical `personnel-archive-desktop-b` client. It must never create, reset, or delete the user's database volume.

- Cover exact-label layout resolution, historical row shifts, optional/partially filled fields, evidence-based family birth-date association, numeric Excel date cells preserving their visible year/month/day precision, V3 canonical Chinese numeral/mixed-delimited age/date values, V4 repeated-separator, 128-character range bound and `起点—至今` range text, aggregated field/cell failures, fuzzy-value rejection and single-file rollback, immutable V10/V11 plus active-only V13/V14 backfills, V13/V14 closed-result-set keyset pages, duplicate precheck, default skip, overwrite history, reasoned soft deletion, persistent import-history pagination, repeated overwrites/deletes, active-only uniqueness/querying, stale/concurrent decisions, fuzzy search, authorization, identity/phone masking and explicit reveal, audit redaction, dynamic-family canonical export, and photo insertion. `DefaultAdminMigrationContractTest` must lock the MySQL V8 deterministic reset/unlock and sole `ADMIN` association without exposing plaintext credentials. Every schema change updates the MySQL migration path and a real MySQL integration test; every API change updates OpenAPI and client types.

## CODE DOCUMENTATION

- Keep the backend module entries, workflows, invariants, and optimization roadmap in `../CODE_GUIDE.md` synchronized with source changes.
- Add or update Javadoc/comments around transaction propagation, locking and stale decisions, active-snapshot filtering, cryptographic purpose, Excel coordinates, raw-text ordering, photo reconstruction, and audit redaction.
- Comments explain why an invariant exists and what must remain true; do not annotate every assignment or duplicate the type system.
- Flyway SQL and the binary production workbook are described in `../CODE_GUIDE.md`; never rewrite an applied migration or modify the template without matching structural/export tests.
- Before completion, reconcile `CURRENT SERVICE STATUS` with controllers, migrations, security rules, and tests; never list an endpoint or persistence behavior as complete before it exists.

## SERVICE ANTI-PATTERNS

- Microservices, Redis, MinIO, queues, or asynchronous infrastructure without a demonstrated requirement.
- Source workbook retention, Base64 photo storage, retaining fuzzy nonblank age/date values in an active snapshot, formatting canonical values during query/export, invented date precision, or plaintext identity-card indexes.
- Shared batch transaction, in-place overwrite, hard-deleting history, missing active filters, partial overwrite, silent merge/truncation, or unbounded uploads.
- Logging full DTOs, Excel contents, identity data, tokens, or binary files.
