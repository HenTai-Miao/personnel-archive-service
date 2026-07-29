# Label-Anchored Workbook Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import all five supported historical personnel workbooks by locating exact template labels, preserving blanks, and accepting any positive number of family-member rows without weakening file or image safety checks.

**Architecture:** Add one package-private layout resolver that turns workbook labels into bounded row/column coordinates, then keep value mapping in `ExcelArchiveReader`. Family birth dates are resolved only from formula references or an exact normalized relationship/name match. A Flyway migration removes the six-row database limit, and export expands the family areas before writing more than six records.

**Tech Stack:** Java 21, Spring Boot 4, Apache POI XSSF 5.4.1, Flyway, MyBatis, MySQL 8.4, JUnit 5, AssertJ, Mockito, Testcontainers.

## Global Constraints

- Workbook business fields are optional; a blank cell remains blank and never causes a required-field error.
- Uploaded workbook bytes and real personnel values must not enter source control, test fixtures, logs, or audit metadata.
- Keep ZIP-bomb limits, sheet checks, picture MIME/size/anchor checks, per-workbook transactions, duplicate decisions, and stable error codes unchanged.
- Do not use workbook named ranges; broken `#REF!` names are ignored.
- Main-table family rows are authoritative. Never associate a helper row by residual position, fuzzy name, or age.
- Family-member `sort_order` remains positive and unique per archive, but has no business maximum.
- Use Java 21 for every Maven command by prefixing the stated Maven goal with:
  `env JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:/usr/bin:/bin:/usr/sbin:/sbin`

---

### Task 1: Resolve historical layouts from exact labels

**Files:**
- Create: `src/main/java/com/company/personnelarchive/archiveimport/WorkbookLayoutV1.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ExcelTemplateV1.java`
- Modify: `src/test/java/com/company/personnelarchive/archiveimport/ExcelTemplateV1Test.java`

**Interfaces:**
- Consumes: `XSSFWorkbook`, `DataFormatter`, the exact normalized label vocabulary in `ExcelTemplateV1`.
- Produces: `WorkbookLayoutV1.resolve(XSSFWorkbook, DataFormatter)` returning main/helper sheets, section rows, declaration row, and the scalar value-cell coordinates used by `ExcelArchiveReader`.

- [ ] **Step 1: Write a failing shifted-layout test**

  Copy the production template in memory, shift the reward/family/declaration region by two rows, update its section merges, place synthetic values in the shifted rows, and assert `reader.read(InputStream, long, String)` returns the shifted work/reward/family data. The production change that makes this test fail is restoring fixed rows `34`, `38`, `39..44`, or `B46`.

- [ ] **Step 2: Verify the test fails for the expected fixed-coordinate reason**

  Run:
  `./mvnw -Dtest=ExcelTemplateV1Test#readsShiftedSectionsFromLabels test`

  Expected: `BusinessException` containing `模板标签不匹配` or `模板合并区域不匹配`.

- [ ] **Step 3: Implement bounded exact-label resolution**

  `WorkbookLayoutV1` must normalize CR/LF, whitespace, and Chinese/ASCII colons, reject missing or duplicate labels, require ordered section rows, and require the family headers on one row. It returns coordinates rather than business values; it must not inspect named ranges.

  The package-private record signature is `WorkbookLayoutV1(XSSFSheet main, XSSFSheet helper, int educationRow, int workRow, int rewardRow, int familyHeaderRow, int declarationValueRow, int helperHeaderRow, Map<String, CellAddress> scalarValues)`, with a package-private static factory `resolve(XSSFWorkbook workbook, DataFormatter formatter)`.

- [ ] **Step 4: Replace fixed merge validation with semantic structure validation**

  Keep the production template test that locks its own merge count/print setup, but make uploaded historical workbooks pass when required labels and order are valid even if section merges differ.

- [ ] **Step 5: Run the focused layout tests**

  Run: `./mvnw -Dtest=ExcelTemplateV1Test test`

  Expected: all template tests pass.

### Task 2: Map dynamic sections and preserve blank values

**Files:**
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ExcelArchiveReader.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ArchiveDocument.java`
- Modify: `src/test/java/com/company/personnelarchive/archiveimport/ExcelTemplateV1Test.java`

**Interfaces:**
- Consumes: `WorkbookLayoutV1` coordinates and section boundaries.
- Produces: the existing `ArchiveDocument` API; no controller or client schema changes.

- [ ] **Step 1: Add failing tests for variable rows, month text, and multi-cell values**

  Add separate tests that assert:

  ```java
  assertThat(document.birthDate()).isEqualTo(LocalDate.of(1988, 7, 1));
  assertThat(document.familyMembers()).hasSize(10);
  assertThat(document.familyMembers().get(9).birthDate()).isNull();
  assertThat(document.familyMembers().get(4).workplacePosition()).isEqualTo("第一段\t第二段");
  ```

  Build every workbook in memory from the clean template using synthetic values only.

- [ ] **Step 2: Verify each new test fails before production changes**

  Run these methods individually and confirm the stated failure source:

  - `-Dtest=ExcelTemplateV1Test#readsMonthOnlyTextWithoutInventingFailure`
  - `-Dtest=ExcelTemplateV1Test#readsTenFamilyRowsAndKeepsUnlinkedBirthDateBlank`
  - `-Dtest=ExcelTemplateV1Test#preservesAllWorkplaceCellsInFamilyRow`

- [ ] **Step 3: Read dynamic ordered-text ranges**

  Read from each section label row up to the next section label, retain only rows with user values, assign contiguous zero-based `sortOrder`, and join nonblank right-side cells with `\t` so every entered cell remains represented.

- [ ] **Step 4: Resolve family birth dates without invention**

  For each main row, parse an explicit `家庭成员年龄计算!D<n>` reference first. Otherwise use one unique unused helper row whose normalized relationship and name both match. When neither applies, set `birthDate = null`; unmatched helper rows do not create members or errors.

- [ ] **Step 5: Accept month-only text and dynamic declaration rows**

  Extend date parsing with the existing `MONTH` pattern and return the first day of that month. Search the resolved declaration value row for signer/date text instead of reading `B46`.

- [ ] **Step 6: Run parser and snapshot unit tests**

  Run:
  `./mvnw -Dtest=ExcelTemplateV1Test,SnapshotWriterTest test`

  Expected: all focused tests pass.

### Task 3: Remove the six-member persistence limit

**Files:**
- Create: `src/main/resources/db/migration/V5__allow_unbounded_family_members.sql`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ExcelTemplateV1.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ArchiveDocument.java`
- Modify: `src/test/java/com/company/personnelarchive/person/SnapshotWriterTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: any `ArchiveDocument.familyMembers()` list allowed by upload and memory limits.
- Produces: positive family `sort_order` values with no maximum and unchanged uniqueness.

- [ ] **Step 1: Add a failing writer test for ten members**

  Pass ten synthetic members to `SnapshotWriter.prepare/insert` and verify `insertFamily` is invoked ten times with sort orders `1..10`. This must fail while `validateCapacity()` enforces six.

- [ ] **Step 2: Verify the writer test fails with `TEMPLATE_CAPACITY_EXCEEDED`**

  Run: `./mvnw -Dtest=SnapshotWriterTest#insertsEveryFamilyMemberBeyondTemplateSixRows test`

- [ ] **Step 3: Add and test the Flyway migration**

  Migration SQL:

  ```sql
  ALTER TABLE pa_family_member DROP CHECK ck_pa_family_member_order;
  ALTER TABLE pa_family_member
      ADD CONSTRAINT ck_pa_family_member_order CHECK (sort_order >= 1);
  ```

  Extend `MySqlMigrationIntegrationTest` to insert a row with `sort_order = 10` and reject `sort_order = 0`.

- [ ] **Step 4: Remove only the family application capacity**

  Keep education/work/reward capacity checks unchanged. Remove `FAMILY_MEMBER_CAPACITY` and the family call in `ArchiveDocument.validateCapacity()`.

- [ ] **Step 5: Run unit tests and record Docker integration status**

  Run:
  `./mvnw -Dtest=SnapshotWriterTest,MySqlMigrationIntegrationTest test`

  Expected: unit test passes; MySQL test passes when Docker is available or is explicitly reported skipped in the sandbox.

### Task 4: Preserve export for more than six family members

**Files:**
- Modify: `src/main/java/com/company/personnelarchive/archiveexport/ArchiveExportService.java`
- Modify: `src/test/java/com/company/personnelarchive/archiveexport/ArchiveExportServiceTest.java`

**Interfaces:**
- Consumes: an ordered `List<PersonReadMapper.FamilyRow>` of any bounded in-memory size.
- Produces: a valid workbook containing every family row and a declaration shifted below the expanded section.

- [ ] **Step 1: Add a failing ten-member export test**

  Return ten synthetic `FamilyRow` records from the mapper, generate and reopen the workbook, then assert main rows `39..48`, helper rows `2..11`, extended A-column merges, and shifted declaration content all exist. The test must fail while the exporter throws `TEMPLATE_CAPACITY_EXCEEDED`.

- [ ] **Step 2: Verify the export test fails for the six-member guard**

  Run: `./mvnw -Dtest=ArchiveExportServiceTest#expandsTemplateForTenFamilyMembers test`

- [ ] **Step 3: Expand the template before filling**

  Insert the extra main/helper rows before the declaration, clone row heights/cell styles from the final template family row, reproduce per-row F:I merges, extend the family-label A-column merge, and shift row breaks by the inserted count. Write the declaration to its resolved shifted row.

- [ ] **Step 4: Reopen and validate the generated workbook**

  Run: `./mvnw -Dtest=ArchiveExportServiceTest test`

  Expected: all export tests pass and the six-row output remains unchanged.

### Task 5: Documentation and full automated verification

**Files:**
- Modify: `../AGENTS.md`
- Modify: `AGENTS.md`
- Modify: `../CODE_GUIDE.md`

**Interfaces:**
- Consumes: completed behavior and migration.
- Produces: synchronized project status, source map, import flow, migration list, and documented limits.

- [ ] **Step 1: Update documentation**

  Replace fixed-coordinate/six-family descriptions with exact-label/dynamic-family behavior, add Flyway V5, and document that unmatched helper rows neither block import nor populate a blank main row.

- [ ] **Step 2: Run formatting and complete verification**

  Run:

  ```text
  ./mvnw verify
  git diff --check
  ```

  Expected: Maven exit `0`; no whitespace errors. Report Docker-dependent skips separately.

- [ ] **Step 3: Commit the implementation**

  Stage source, tests, migration, and synchronized docs only. Do not stage `config/application-local.yml`.

  Commit message: `feat: import label-anchored workbook variants`

### Task 6: Import the five user workbooks through the running system

**Files:**
- No source files; use the five user-provided Desktop paths only at runtime.

**Interfaces:**
- Consumes: local MySQL, local profile service, authenticated `/api/v1/imports`, and the B desktop client.
- Produces: five persistent import-history items plus visible active archive details.

- [ ] **Step 1: Start local MySQL/service and B client**

  Start the service with Java 21 and the existing uncommitted local profile. Start `personnel-archive-desktop-b` with `pnpm dev`; do not use the root launcher because it opens the sibling packaged client.

- [ ] **Step 2: Upload all five files in one batch**

  Use the authenticated manual batch-import flow. If the service returns an existing duplicate, stop before choosing `OVERWRITE` and report the exact duplicate state; do not silently replace an archive.

- [ ] **Step 3: Verify persistent results and database state**

  Confirm all five items end in `IMPORTED` (or an explicitly user-approved duplicate decision), query active person details, and verify family counts against the source audit: `7, 6, 7, 6, 10`. Verify the two source-blank family birth dates remain `NULL`, and verify five photos are present.

- [ ] **Step 4: Leave the B client open on the visible results**

  Refresh the permanent history panel and personnel directory so the user can inspect the imported archives and the ten-member detail directly.
