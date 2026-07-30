# Source-Value Fidelity Implementation Plan

> Follow-up: the 2026-07-30 Chinese source-value projections plan preserves every raw-string guarantee below while adding nullable numeric components. Earlier statements that parsed values are never persisted are superseded only for those explicit projections; API and export behavior remains unchanged.

**Goal:** Preserve every supplied business-date and family-age display value through import, MySQL, detail API, desktop display, and XLSX reconstruction without normalization or derivation.

**Architecture:** `SourceDate` keeps the authoritative source text plus precision-specific validation semantics. Flyway V7 converts every business-date column to `VARCHAR(64)` and removes the V6 transitional companion columns. Persistence, query, client, and export boundaries carry strings directly. Literal family ages remain exact; only the template's exact `DATEDIF(...,TODAY(),"y")` formula is evaluated at import and frozen as text, after which no layer recalculates it.

## Global constraints

- A year remains a year, a year-month remains a year-month, and a year-month-day remains a year-month-day.
- Never add a missing month/day, normalize separators, change a literal age, execute an unrecognized formula, recalculate a stored age, or append a display unit. The sole calculation is the template's exact standard age formula at import.
- Empty values remain empty.
- Parsed date objects validate input and are never returned; the follow-up plan may persist only their explicitly present numeric components, never a formatted replacement date.
- Existing transaction, authorization, duplicate, audit, photo, and source-file-retention rules remain unchanged.

## Task 1: Lock source parsing behavior

- [x] Add parser tests for year, year-month, year-month-day, literal/formula age, stale formula cache, blank formula references, and date ranges.
- [x] Add `SourceDate` support for `Year`, `YearMonth`, and `LocalDate` validation without changing `text`.
- [x] Copy every literal main-sheet age unchanged into `FamilyMember.ageText`; for the exact standard age formula, replace stale caches with the import-day Excel-equivalent display value and freeze it as text.
- [x] Preserve ordered-row date fragments as source strings.

Verification: `./mvnw -Dtest=ExcelTemplateV1Test test`

## Task 2: Store strings directly

- [x] Keep V6 as an immutable transition that protects date text and introduces `age_text`.
- [x] Add V7 to convert all twelve business-date columns to `VARCHAR(64)`.
- [x] In V7, copy V6 source text into the original columns before dropping every `*_date_text` column.
- [x] Change insert/read mapper models from `LocalDate` plus companion text to one `String` per business date.
- [x] Make `SnapshotWriter` write `SourceDate.text()` and ordered date fragments directly.
- [x] Add real MySQL 8.4 migration assertions for column types, removed companions, and mixed precision values.

Verification: `./mvnw -Dtest=SnapshotWriterTest,MySqlMigrationIntegrationTest test`

## Task 3: Return and display only source strings

- [x] Return direct database strings from `PersonQueryService` without fallbacks or normalization.
- [x] Define every date and family age as source strings in OpenAPI.
- [x] Change the B client family-age schema to string and reject numeric API ages.
- [x] Render age text directly without adding “岁”.

Verification:

- `./mvnw -Dtest=PersonQueryServiceTest,ApiContractSerializationTest test`
- `pnpm test -- tests/unit/archive-detail-schema.test.ts tests/unit/archive-detail.test.ts`

## Task 4: Reconstruct exact values

- [x] Remove `AgeCalculator` and all export/query age derivation.
- [x] Write scalar dates, declaration dates, family birth dates, and ages as database strings.
- [x] Add a reopened-workbook regression covering year, year-month, year-month-day, and age.
- [x] Keep raw ordered rows, photos, dynamic family expansion, styles, and layout behavior unchanged.

Verification: `./mvnw -Dtest=ArchiveExportServiceTest,PersonnelArchiveFlowIntegrationTest test`

## Task 5: Synchronize and verify

- [x] Update root/service/client knowledge bases, `CODE_GUIDE.md`, current design notes, OpenAPI, and client contracts.
- [x] Run complete backend verification with Docker/MySQL available: `./mvnw verify`.
- [x] Run complete client verification: `pnpm lint`, `pnpm typecheck`, `pnpm test`, `pnpm build`.
- [x] Run `git diff --check` in both repositories.
- [x] Restart the root local launcher so Flyway V7 applies to `local-mysql`; do not reset its volume.
