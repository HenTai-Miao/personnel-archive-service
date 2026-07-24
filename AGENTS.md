# SERVICE KNOWLEDGE BASE

Inherits all product, data, Excel, and security rules from `../AGENTS.md`. This directory owns the sole backend and all authoritative business decisions.

## CURRENT SERVICE STATUS

- Implemented APIs cover authentication/session lifecycle, manual batch import, duplicate decisions, persistent paginated import history, active archive list/complete detail with masked identity-card/phone values, authenticated no-store single-field reveal and photo bytes, family-member search, single XLSX export, and selected batch ZIP export.
- Implemented schema migrations are V1 initial schema, V2 photo-anchor preservation, and V3 optional employee/name/identity support; photos are stored as BLOBs with reconstruction metadata.
- Import decisions use a bounded in-memory parsed-document store, so a service restart intentionally makes an outstanding decision stale and requires re-upload.
- Desktop folder scanning and source-file moves are intentionally client-side and are implemented by the current client; the service remains authoritative for import success and exposes no local-path API.
- Update this section in the same change whenever an endpoint, migration, persistence rule, or supported workflow changes.

## STACK AND SHAPE

- Java 21, Spring Boot 4.x/Spring MVC, Maven Wrapper.
- Spring Security, Bean Validation, official MyBatis Spring Boot Starter, Flyway, MySQL Connector/J, Apache POI XSSF.
- JUnit 5 and Testcontainers with real MySQL for integration tests; do not substitute H2.
- Modular monolith organized by feature: `auth`, `person`, `family`, `archiveimport`, `archiveexport`, `audit`, and `configuration`.
- Controllers handle HTTP/DTO boundaries only. Application services own transactions and workflows; mappers own SQL.

## DATABASE RULES

- Database name: `personnel_archive`; production containers pin an explicit MySQL LTS patch version.
- Flyway is the only schema-change mechanism. Disable production auto-DDL and never leave manual changes without a migration.
- Use `BIGINT`, `snake_case`, `utf8mb4`, and explicit foreign keys/indexes.
- Include server-controlled `tenant_id`; version 1 always resolves it to `1` and ignores/rejects client input.
- `pa_person` owns snapshot state: `is_deleted` defaults to `0`; also record `deleted_at`, `deleted_by`, `delete_reason`, and `replaced_by_person_id`. Old children remain unchanged under the old `person_id`.
- Enforce supplied nonblank employee-number and identity-card uniqueness only for active snapshots. Nullable generated `active_employee_no`/`active_id_card_hmac` columns become `NULL` for blank/absent identifiers and deleted rows, then unique-index with `tenant_id`; never use `(value, is_deleted)` uniqueness.
- Encrypt full identity-card data with authenticated encryption; keep encryption/HMAC keys outside the database and repository.
- Store photos as bounded binary BLOBs with MIME, byte count, dimensions, filename, and SHA-256.
- Store family birth dates and calculate age using an injected `Clock` so tests are deterministic.
- MySQL `DATETIME(6)` audit and snapshot timestamps use China wall-clock time. Every service JDBC URL must set `connectionTimeZone=Asia/Shanghai`; Java reads API timestamps as `Instant`, and Jackson serializes those instants in UTC. Do not compensate in a client.
- Never point automated tests at the user's running `local-mysql` container.

## API AND AUTH

- Prefix endpoints with `/api/v1`; expose request/response DTOs, never persistence entities.
- Validate at the boundary and return stable machine error codes plus safe Chinese messages.
- Every normal person/detail/duplicate/family/export query filters `pa_person.is_deleted = 0`. Escape `%`, `_`, and the escape character in family-name contains queries; paginate all matched active rows, with 20 rows as the default page size for personnel and family-member queries.
- Base detail DTOs expose every persisted workbook section while returning only front-four/back-three identity-card and phone masks; short nonblank values are fully masked. The service decrypts an identity card only long enough to produce that mask. A separate authenticated endpoint rechecks the active snapshot and returns one explicitly requested full identity-card or phone value with caching disabled and a value-free view audit. Photo metadata remains in detail; raw JPEG/PNG bytes use their own authenticated, no-store, audited endpoint.
- Use Spring Security with `ADMIN`, `ARCHIVE_EDITOR`, and `VIEWER` permissions.
- Passwords use an adaptive hash. Access/session credentials expire and are revocable; store refresh tokens only as hashes.
- Record login, import, overwrite, skip, edit, view, export, and administration actions without sensitive payloads; overwrite audit stores the old/new `person_id` and operator.
- Import-history reads are tenant-scoped, authorized for `ADMIN`/`ARCHIVE_EDITOR`, and audited as views. Normalize upload names to a nonblank basename before parsing/persistence, return the actual decision operator for overwrite/skip, and never expose source bytes, stored file hashes, or client-local paths.
- Secrets come from environment/secret injection. Never commit database passwords, token keys, or field-encryption keys.

## EXCEL IMPORT

- Treat upload content as hostile: verify XLSX signature/type, size, template version, sheets, labels, merges, values, and images.
- Configure Apache POI zip-bomb and memory limits before parsing.
- Use a versioned coordinate/label map; do not depend on invalid workbook named ranges.
- Do not require any workbook business field. Preserve blank and partially filled rows, including empty employee number, name, and identity card; preserve identifier, phone, postal, and textual date values without losing leading zeros.
- Normalize supplied names with Unicode NFKC/trimmed whitespace for matching. Only a nonblank identity card is normalized, validated, encrypted, HMAC-indexed, and given a display suffix; an absent identity card remains `NULL` in all protected columns.
- Process one workbook per transaction. Batch aggregation happens outside those transactions.
- Match duplicates only when both incoming values are supplied and normalized name plus identity-card HMAC equal one active snapshot, then return `DUPLICATE_REQUIRES_DECISION` without writing archive data. Manual selection and desktop folder scanning call this same endpoint and decision service.
- A supplied identity card with a different normalized name is `IDENTITY_CARD_CONFLICT`; a supplied nonblank employee number owned by another snapshot is `EMPLOYEE_NO_CONFLICT`. Blank identifiers skip their conflict lookups, and the same name with a different identity card is not a duplicate.
- On decision, lock and recheck the active target. `SKIP` changes no archive table. `OVERWRITE` atomically soft-deletes the old root, inserts a complete new root/children/photo snapshot, and links old to new; never update/delete old children.
- If the target changed since precheck, return a stale-decision result. Any failure rolls back both the old-row soft delete and every new insert.
- Enforce template capacities `5/16/4/6` and reject overflow; never truncate.
- Delete framework upload temp files and application temp files on every success/failure path.

## EXCEL EXPORT

- Keep a cleaned, immutable, versioned template in application resources or configured read-only storage.
- Fill values from the database, preserve row ordering/raw text, compute family ages, and insert one validated photo in the template photo area.
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

For macOS manual testing, the root `../启动本地测试.command` starts Docker Desktop and the existing local MySQL container, runs this service with the `local` profile, waits for `/api/v1`, and verifies the configured bootstrap-admin login before opening the client. It must never create, reset, or delete the user's database volume.

Cover template validation, optional/partially filled fields, date/age calculation, duplicate precheck, default skip, overwrite history, persistent import-history pagination, repeated overwrites, active-only uniqueness/querying, stale/concurrent decisions, rollback, fuzzy search, authorization, identity/phone masking and explicit reveal, audit redaction, lossless export, and photo insertion. Every schema change includes a Flyway migration and MySQL integration test; every API change updates OpenAPI and client types.

## CODE DOCUMENTATION

- Keep the backend module entries, workflows, invariants, and optimization roadmap in `../CODE_GUIDE.md` synchronized with source changes.
- Add or update Javadoc/comments around transaction propagation, locking and stale decisions, active-snapshot filtering, cryptographic purpose, Excel coordinates, raw-text ordering, photo reconstruction, and audit redaction.
- Comments explain why an invariant exists and what must remain true; do not annotate every assignment or duplicate the type system.
- Flyway SQL and the binary production workbook are described in `../CODE_GUIDE.md`; never rewrite an applied migration or modify the template without matching structural/export tests.
- Before completion, reconcile `CURRENT SERVICE STATUS` with controllers, migrations, security rules, and tests; never list an endpoint or persistence behavior as complete before it exists.

## SERVICE ANTI-PATTERNS

- Microservices, Redis, MinIO, queues, or asynchronous infrastructure without a demonstrated requirement.
- Source workbook retention, Base64 photo storage, persisted age, or plaintext identity-card indexes.
- Shared batch transaction, in-place overwrite, hard-deleting history, missing active filters, partial overwrite, silent merge/truncation, or unbounded uploads.
- Logging full DTOs, Excel contents, identity data, tokens, or binary files.
