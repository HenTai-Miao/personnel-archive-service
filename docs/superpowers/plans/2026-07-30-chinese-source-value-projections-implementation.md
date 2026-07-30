# Chinese Source-Value Projections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve Chinese age/date source text while additionally storing deterministic numeric age and year/month/day projections for new and existing snapshots.

**Architecture:** A frozen `SourceValueParserV1` deep module uses pinned Hutool Core for unit-style Chinese integers and project-owned grammar for digit-wise years, precision, ranges, and `java.time` validation. Flyway V9 adds nullable projections to MySQL and KingbaseES; V10 Java migrations reuse the frozen V1 semantics to backfill only deterministic historical values. Import and export keep all existing raw-string contracts.

**Tech Stack:** Java 21, Spring Boot 4.0.7, MyBatis, Apache POI, Hutool Core 5.8.46, Flyway, MySQL 8.4, KingbaseES SQL contract tests, JUnit 5, AssertJ, Mockito, Testcontainers.

## Global Constraints

- Source text remains authoritative for API responses, UI display, search results, and XLSX reconstruction.
- Never invent missing month/day values or derive age from birth date, identity card, or the current date.
- Exact ages receive a value; fuzzy ages remain `NULL` without rejecting the workbook.
- Existing applied Flyway migrations V1-V8 are immutable. V1 parsing semantics used by V10 are also immutable after release.
- Do not add public API fields, frontend changes, indexes, or unrelated abstractions.
- No source workbook bytes or sensitive source values may be logged by parsing or migration code.

---

## Task 1: Lock the parser contract with failing tests

**Files:**

- Create: `src/test/java/com/company/personnelarchive/archiveimport/SourceValueParserV1Test.java`
- Create: `src/main/java/com/company/personnelarchive/archiveimport/SourceAge.java`
- Create: `src/main/java/com/company/personnelarchive/archiveimport/SourceValueParserV1.java`
- Modify: `pom.xml`

- [x] Add tests for `三十五岁`, `35岁`, optional `周岁`, zero, and exact upper-bound behavior.
- [x] Add tests proving `三十多岁`, `约三十岁`, `三十五岁半`, negative and overflowing values keep raw text with a `NULL` numeric value.
- [x] Add tests for `二〇二四年`, `二〇二四年五月`, `二〇二四年五月三日`, existing Arabic formats, and mixed digit years.
- [x] Add tests for leap-day validation, invalid month/day, ranges, and `至今` markers.
- [x] Run the parser test and observe the expected compilation/test failure before implementation.
- [x] Pin `cn.hutool:hutool-core:5.8.46` and implement only enough parser code to pass the tests.

Verification: `./mvnw -Dtest=SourceValueParserV1Test test`

## Task 2: Route Excel parsing through the versioned module

**Files:**

- Modify: `src/test/java/com/company/personnelarchive/archiveimport/ExcelTemplateV1Test.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ExcelArchiveReader.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/SourceDate.java`

- [x] Add failing workbook tests for Chinese scalar year/month/day, declaration date, family helper birth date, exact/fuzzy age text, and Chinese repeated-row date ranges.
- [x] Replace duplicated numeric-only parsing with `SourceValueParserV1` while preserving coordinate-only validation errors.
- [x] Keep numeric Excel date cells, standard age-formula freezing, other formula-cache behavior, and family helper association unchanged.
- [x] Ensure ordered rows retain raw text and only expose parseable date fragments.

Verification: `./mvnw -Dtest=SourceValueParserV1Test,ExcelTemplateV1Test test`

## Task 3: Add schema projections and historical backfill

**Files:**

- Create: `src/main/resources/db/migration/V9__add_structured_source_value_projections.sql`
- Create: `src/main/resources/db/kingbase/V9__add_structured_source_value_projections.sql`
- Create: `src/main/java/com/company/personnelarchive/migration/StructuredSourceValueBackfillV1.java`
- Create: `src/main/java/db/migration/V10__backfill_structured_source_values.java`
- Create: `src/main/java/db/kingbase/V10__backfill_structured_source_values.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/KingbaseMigrationContractTest.java`

- [x] Change the MySQL test to migrate first through V9, insert deterministic and fuzzy historical source strings, then run V10.
- [x] Add failing assertions for all new columns, hierarchy/range constraints, V10 backfill values, `NULL` fuzzy projections, and unchanged source text.
- [x] Add failing KingbaseES SQL contract assertions for equivalent columns and constraints.
- [x] Implement V9 additive DDL for all twelve date fields and family age without changing existing columns.
- [x] Implement a JDBC backfill that batches updates, never logs values, and only writes the new projection columns.
- [x] Add thin MySQL/KingbaseES V10 Java migration entry points with fixed checksums.

Verification: `./mvnw -Dtest=MySqlMigrationIntegrationTest,KingbaseMigrationContractTest test`

## Task 4: Persist raw values and projections together

**Files:**

- Modify: `src/test/java/com/company/personnelarchive/person/SnapshotWriterTest.java`
- Modify: `src/main/java/com/company/personnelarchive/person/PersonMapper.java`
- Modify: `src/main/java/com/company/personnelarchive/person/SnapshotWriter.java`

- [x] Add failing mapper-capture tests for person, employment, ordered sections, family birth date, exact age, and fuzzy age.
- [x] Add compact `DateProjection` and `AgeProjection` mapper value types.
- [x] Extend every insert SQL statement with the matching projection columns.
- [x] Convert `SourceDate` directly and parse age/ordered-row source strings through `SourceValueParserV1` at the snapshot boundary.
- [x] Keep empty raw values, identity protection, transaction ownership, child ordering, and photo writes unchanged.

Verification: `./mvnw -Dtest=SnapshotWriterTest test`

## Task 5: Prove the end-to-end fidelity contract

**Files:**

- Modify: `src/test/java/com/company/personnelarchive/PersonnelArchiveFlowIntegrationTest.java`

- [x] Import representative Chinese year, year-month, full-date, exact-age, and fuzzy-age values.
- [x] Assert the database contains the expected projections and `NULL`s.
- [x] Assert detail API JSON still contains the exact source strings and no new public fields.
- [x] Reopen the exported workbook and assert the same source strings remain unchanged.

Verification: `./mvnw -Dtest=PersonnelArchiveFlowIntegrationTest test`

## Task 6: Synchronize project contracts and verify

**Files:**

- Modify: `../AGENTS.md`
- Modify: `AGENTS.md`
- Modify: `../CODE_GUIDE.md`
- Modify: `docs/superpowers/specs/2026-07-29-source-value-fidelity-design.md`
- Modify: `docs/superpowers/plans/2026-07-30-chinese-source-value-projections-implementation.md`

- [x] Update current implementation status, storage invariants, migration inventory, source map, and parser/backfill maintenance rule.
- [x] Record that API/OpenAPI/client contracts remain raw strings and therefore require no shape change.
- [x] Compare the final behavior with root/service `AGENTS.md` and `CODE_GUIDE.md`.
- [x] Run targeted tests, then a fresh full backend verification with Java 21.
- [x] Run `git diff --check` for the service and inspect the root non-Git documentation diff manually.

Verification:

- `JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH ./mvnw verify`
- `git diff --check`
