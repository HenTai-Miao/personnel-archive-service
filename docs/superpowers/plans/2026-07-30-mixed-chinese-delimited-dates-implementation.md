# Mixed Chinese Delimited Dates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Import, project, migrate, and losslessly export dates such as `二〇二四.五.三` without changing the frozen V1/V10 interpretation contract.

**Architecture:** Add `SourceValueParserV2` as a compatibility layer that delegates all existing inputs to V1 and only adds mixed Chinese/Arabic delimited dates. Route current imports and snapshot projections through V2, then add a database-neutral V11 backfill used by thin MySQL and KingbaseES Java migration entries. Source text remains authoritative throughout API and XLSX reconstruction.

**Tech Stack:** Java 21, Spring Boot, Hutool Core 5.8.46, Apache POI, Flyway Java migrations, JUnit 5, AssertJ, Mockito, Testcontainers MySQL 8.4.3.

## Global Constraints

- Do not modify `SourceValueParserV1`, `StructuredSourceValueBackfillV1`, or either V10 migration.
- Preserve every source-visible date string exactly; projections may never replace or reformat source text.
- Preserve date precision: year-only has no month/day, and year-month has no day.
- Accept `.`, `/`, and `-`, including NFKC-equivalent full-width punctuation, with Chinese or Arabic components and the explicitly designed optional `年`/`月`/`日` suffixes.
- Reject invalid calendar dates, misplaced suffixes, fuzzy dates, two-digit years, `号`, and unsupported natural language.
- MySQL and KingbaseES must share the same V2 parsing and V11 JDBC backfill implementation.
- Do not add API fields or client changes.
- Preserve all pre-existing uncommitted work; do not commit or stage files unless the user explicitly asks.

---

### Task 1: Versioned V2 Parser

**Files:**
- Create: `src/main/java/com/company/personnelarchive/archiveimport/SourceValueParserV2.java`
- Create: `src/test/java/com/company/personnelarchive/archiveimport/SourceValueParserV2Test.java`
- Verify unchanged: `src/main/java/com/company/personnelarchive/archiveimport/SourceValueParserV1.java`

**Interfaces:**
- Consumes: `SourceValueParserV1.parseAge(String)`, `SourceValueParserV1.parseDate(String)`, `SourceDate`, and `SourceAge`.
- Produces: `SourceValueParserV2.parseAge(String)`, `SourceValueParserV2.parseDate(String)`, `SourceValueParserV2.parseDateRange(String)`, and `SourceValueParserV2.DateRange`.

- [x] **Step 1: Write parser tests that name the unsupported-format regression**

Create a parameterized test with literal expectations:

```java
@ParameterizedTest
@MethodSource("mixedDelimitedDates")
void parsesMixedChineseDelimitedDatesWithoutChangingSourceText(
    String raw, Year year, YearMonth month, LocalDate exact) {
    SourceDate parsed = SourceValueParserV2.parseDate(raw);

    assertThat(parsed.text()).isEqualTo(raw);
    assertThat(parsed.year()).isEqualTo(year);
    assertThat(parsed.yearMonth()).isEqualTo(month);
    assertThat(parsed.exactDate()).isEqualTo(exact);
}

static Stream<Arguments> mixedDelimitedDates() {
    return Stream.of(
        Arguments.of("二〇二四.五", Year.of(2024), YearMonth.of(2024, 5), null),
        Arguments.of("二〇二四.五.三", Year.of(2024), YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3)),
        Arguments.of("二〇二四/5/三", Year.of(2024), YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3)),
        Arguments.of("2024-五月", Year.of(2024), YearMonth.of(2024, 5), null),
        Arguments.of("二〇2四年.05月.三日", Year.of(2024), YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3)),
        Arguments.of("二〇二四．五．三", Year.of(2024), YearMonth.of(2024, 5), LocalDate.of(2024, 5, 3))
    );
}
```

Add separate behavior tests proving V1 remains frozen, invalid dates still throw, age behavior is delegated unchanged, a hyphenated scalar is not split as a range, and a unique V2 range is recognized:

```java
assertThatThrownBy(() -> SourceValueParserV1.parseDate("二〇二四.五.三"))
    .isInstanceOf(DateTimeException.class);
assertThatThrownBy(() -> SourceValueParserV2.parseDate("二〇二三.二.二十九"))
    .isInstanceOf(DateTimeException.class);
assertThat(SourceValueParserV2.parseAge("三十多岁").value()).isNull();
assertThat(SourceValueParserV2.parseDateRange("二〇二四-五-三").start().exactDate())
    .isEqualTo(LocalDate.of(2024, 5, 3));
assertThat(SourceValueParserV2.parseDateRange("二〇二四.五-二〇二五.六").end().yearMonth())
    .isEqualTo(YearMonth.of(2025, 6));
```

- [x] **Step 2: Run the parser test and verify RED**

Run:

```bash
./mvnw -Dtest=SourceValueParserV2Test test
```

Expected: test compilation fails because `SourceValueParserV2` does not exist. This is the intended missing-feature failure.

- [x] **Step 3: Implement the smallest V2 parser**

Implement V1-first delegation and only the new grammar:

```java
public static SourceAge parseAge(String raw) {
    return SourceValueParserV1.parseAge(raw);
}

public static SourceDate parseDate(String raw) {
    try {
        return SourceValueParserV1.parseDate(raw);
    } catch (DateTimeException legacyFailure) {
        return parseMixedDelimitedDate(raw, legacyFailure);
    }
}
```

Use full-date and year-month patterns whose numeric components contain only ASCII digits or the existing Chinese-number alphabet. Permit optional correct suffixes:

```java
private static final Pattern DELIMITED_FULL_DATE = Pattern.compile(
    "^(" + NUMBER + ")(?:年)?[./-](" + NUMBER + ")(?:月)?[./-](" + NUMBER + ")(?:日)?$");
private static final Pattern DELIMITED_MONTH = Pattern.compile(
    "^(" + NUMBER + ")(?:年)?[./-](" + NUMBER + ")(?:月)?$");
```

Normalize only a recognition copy with NFKC and whitespace removal. Reuse Hutool only for all-Chinese counting components, parse positional four-character years explicitly, and validate with `YearMonth.of(...)` or `LocalDate.of(...)`. Return `SourceDate` with the original input string.

For ranges, try the whole raw input as one V2 date before enumerating range separators on the
original string. Validate endpoints through the V2 scalar entry (which retains V1 semantics), but
construct endpoint `SourceDate` values from the raw substrings so even a V1-compatible range keeps
full-width punctuation and internal spacing. Return a range only when exactly one candidate split has
two valid endpoints or a supported present marker.

- [x] **Step 4: Run parser tests and existing V1 tests to verify GREEN**

Run:

```bash
./mvnw -Dtest=SourceValueParserV1Test,SourceValueParserV2Test test
```

Expected: both suites pass; V1 still rejects `二〇二四.五.三`, while V2 accepts it.

---

### Task 2: Route New Imports and Snapshot Projections Through V2

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/archiveimport/ExcelTemplateV1Test.java`
- Modify: `src/test/java/com/company/personnelarchive/person/SnapshotWriterTest.java`
- Modify: `src/test/java/com/company/personnelarchive/PersonnelArchiveFlowIntegrationTest.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/ExcelArchiveReader.java`
- Modify: `src/main/java/com/company/personnelarchive/person/SnapshotWriter.java`

**Interfaces:**
- Consumes: the V2 parser API from Task 1.
- Produces: all current-import date paths and string-to-projection paths use V2 while DTOs and database mapper signatures stay unchanged.

- [x] **Step 1: Add failing Excel-reader coverage for every affected input path**

Add `readsMixedDelimitedDatesAcrossScalarHelperDeclarationAndRange` to `ExcelTemplateV1Test`. Build a real workbook using the existing `workbookBytes` helper and set:

```java
cell(main, layout.scalar("birthDate").formatAsString()).setCellValue("二〇二四.五.三");
cell(main, "B" + (layout.workRow() + 2))
    .setCellValue("二〇二四.五-二〇二五.六");
cell(layout.helper(), "D" + helperRow).setCellValue("二〇〇〇/七/二十三");
cell(main, "B" + (layout.declarationValueRow() + 1))
    .setCellValue("承诺人：测试甲  时间：二〇二六-七-二十二");
```

Populate the matching family relationship/name cells. Assert the scalar exact date, both range endpoint source strings, family helper exact date, and declaration exact date using hand-written `LocalDate`/`YearMonth` values. Assert every returned `.text()` equals its source substring.

- [x] **Step 2: Add failing snapshot projection coverage**

Extend `insertsEveryStructuredProjectionWithoutChangingSourceText` so one `ArchiveDocument.OrderedText` endpoint contains `二〇二四.五.三`, and verify:

```java
new PersonMapper.DateProjection("二〇二四.五.三", 2024, 5, 3)
```

is passed to the corresponding Mapper insert call. This fails while `SnapshotWriter.dateProjection(String)` still calls V1.

- [x] **Step 3: Add the V2 format to the full import/export flow before changing production wiring**

In `PersonnelArchiveFlowIntegrationTest.workbook(...)`, change the work-start source value to
`二〇二〇.一.一`. Update database, detail-API, and rebuilt-workbook assertions to require that exact raw
string and projections `2020`, `1`, `1`:

```java
assertThat(detail.at("/employment/workStartDate").asText()).isEqualTo("二〇二〇.一.一");
assertThat(formatter.formatCellValue(main.getRow(4).getCell(5))).isEqualTo("二〇二〇.一.一");
```

- [x] **Step 4: Run affected tests and verify RED**

Run the fast tests first:

```bash
./mvnw -Dtest=ExcelTemplateV1Test,SnapshotWriterTest test
```

Expected: the new Excel-reader case is rejected as an invalid date and the snapshot projection is `NULL`.

Then run:

```bash
./mvnw -Dtest=PersonnelArchiveFlowIntegrationTest test
```

Expected with Docker available: the upload fails or lacks the expected V2 raw/projection result because the import reader still calls V1.

- [x] **Step 5: Switch only current runtime call sites to V2**

In `ExcelArchiveReader`:

- replace V1 with V2 in formula helper parsing, scalar/helper `parseDate`, and date-range parsing;
- parse the complete tail after the exact declaration `时间：` label through V2, so a valid prefix
  cannot hide unsupported trailing text;
- keep cell-coordinate `TEMPLATE_INVALID` handling and all workbook structure logic unchanged.

In `SnapshotWriter.dateProjection(String)`, call `SourceValueParserV2.parseDate(text)`. Keep
`dateProjection(SourceDate)` and `AgeProjection` behavior unchanged; age may continue through V1 or use V2 delegation with identical results.

- [x] **Step 6: Run the affected tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=SourceValueParserV1Test,SourceValueParserV2Test,ExcelTemplateV1Test,SnapshotWriterTest test
./mvnw -Dtest=PersonnelArchiveFlowIntegrationTest test
```

Expected: all pass, with source strings unchanged in the database, detail JSON, and rebuilt XLSX.

---

### Task 3: V11 Historical Projection Backfill

**Files:**
- Create: `src/main/java/com/company/personnelarchive/migration/StructuredSourceValueBackfillV2.java`
- Create: `src/main/java/db/migration/V11__backfill_mixed_delimited_date_projections.java`
- Create: `src/main/java/db/kingbase/V11__backfill_mixed_delimited_date_projections.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/KingbaseMigrationContractTest.java`

**Interfaces:**
- Consumes: `SourceValueParserV2.parseDate(String)` and the twelve V9 date source/projection column groups.
- Produces: `StructuredSourceValueBackfillV2.migrate(Connection)` and two Flyway version-11 Java entries with fixed checksum `11_001`.

- [x] **Step 1: Make the real-MySQL migration test isolate V11 behavior**

Change the first Flyway target from `9` to `10`, then insert V2-only source strings after V10 has already run:

```sql
UPDATE pa_person
SET birth_date='二〇二四.五.三',
    birth_date_year=NULL,birth_date_month=NULL,birth_date_day=NULL
WHERE id=?
```

After unrestricted `migrate()`, assert the same row contains the exact source string plus
`birth_date_year=2024`, `birth_date_month=5`, and `birth_date_day=3`. Also seed a V1-valid projection
with deliberately wrong numbers before V11 and assert V11 deterministically corrects it.

- [x] **Step 2: Add the Kingbase V11 migration behavior contract**

Add:

```java
@Test
void exposesTheKingbaseV11JavaBackfillAtTheConfiguredClasspathLocation() {
    var migration = new db.kingbase.V11__backfill_mixed_delimited_date_projections();

    assertThat(migration.getVersion().toString()).isEqualTo("11");
    assertThat(migration.getChecksum()).isEqualTo(11_001);
}
```

- [x] **Step 3: Run migration tests and verify RED**

Run:

```bash
./mvnw -Dtest=MySqlMigrationIntegrationTest,KingbaseMigrationContractTest test
```

Expected: test compilation fails because the V11 Kingbase class is absent, or—when running the MySQL
test alone—the V2-only row remains without projections because the latest migration is still V10.

- [x] **Step 4: Implement the database-neutral V2 date backfill**

Create `StructuredSourceValueBackfillV2` with:

```java
public static void migrate(Connection connection) throws SQLException {
    backfillDates(connection, "pa_person", "birth_date", "political_join_date", "declaration_date");
    backfillDates(connection, "pa_employment", "work_start_date",
        "dispatched_to_bidding_company_date", "title_or_skill_acquired_date");
    backfillDates(connection, "pa_education", "start_date", "end_date");
    backfillDates(connection, "pa_work_experience", "start_date", "end_date");
    backfillDates(connection, "pa_reward_punishment", "occurred_date");
    backfillDates(connection, "pa_family_member", "birth_date");
}
```

Use the existing V1 backfill's 500-row JDBC batch shape but call V2 and update only
`*_year`, `*_month`, `*_day`. Do not select or write `age_text`/`age_value`. Blank or invalid raw dates
produce three SQL `NULL` values, and the migration never logs the raw input.

- [x] **Step 5: Add thin V11 Flyway entries**

Both entries extend `BaseJavaMigration`, return checksum `11_001`, and call:

```java
StructuredSourceValueBackfillV2.migrate(context.getConnection());
```

The package must match its configured location: `db.migration` for MySQL and `db.kingbase` for
KingbaseES.

- [x] **Step 6: Run migration tests and verify GREEN**

Run:

```bash
./mvnw -Dtest=MySqlMigrationIntegrationTest,KingbaseMigrationContractTest test
```

Expected: the real MySQL container reaches V11, exact raw strings are unchanged, V2 projections are
filled, deliberately stale projections are corrected, and the Kingbase V11 classpath/checksum contract
passes.

---

### Task 4: Documentation, Regression Verification, and Local XLSX Acceptance

**Files:**
- Modify: `AGENTS.md`
- Modify: `../AGENTS.md`
- Modify: `../CODE_GUIDE.md`
- Verify fixture: `../outputs/019fb113-6390-74d2-b3db-178ed1862f9c/中文日期年龄导入模拟.xlsx`

**Interfaces:**
- Consumes: completed V2 runtime behavior and V11 migration.
- Produces: synchronized project documentation and evidence from automated plus local end-to-end verification.

- [x] **Step 1: Synchronize the knowledge base**

Update the service and root `AGENTS.md` status/constraints to say:

- latest structured-source migration is V11;
- V1/V10 remain frozen;
- current imports use V2 for mixed Chinese/Arabic delimited dates;
- V11 recalculates only date projections and never source text.

Update `CODE_GUIDE.md` source maps and import flow with `SourceValueParserV2`,
`StructuredSourceValueBackfillV2`, and both V11 entries. Do not describe new API fields or claim a real
KingbaseES runtime test.

- [x] **Step 2: Run focused regression suites**

Run:

```bash
./mvnw -Dtest=SourceValueParserV1Test,SourceValueParserV2Test,ExcelTemplateV1Test,SnapshotWriterTest test
./mvnw -Dtest=MySqlMigrationIntegrationTest,KingbaseMigrationContractTest test
./mvnw -Dtest=PersonnelArchiveFlowIntegrationTest test
```

Expected: every command exits `0`.

- [x] **Step 3: Run the authoritative service verification**

Run with the project's Java 21 environment:

```bash
JAVA_HOME=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle \
PATH=/Users/zhongerbing/.sdkman/candidates/java/21.0.11-oracle/bin:$PATH \
./mvnw verify
```

Expected: Maven exits `0`; unit, Spring, security, MySQL migration, import, API, and export tests pass.

- [x] **Step 4: Check the final diff without touching unrelated work**

Run:

```bash
git diff --check
git status --short
git diff -- src/main/java/com/company/personnelarchive/archiveimport/SourceValueParserV1.java \
  src/main/java/com/company/personnelarchive/migration/StructuredSourceValueBackfillV1.java \
  src/main/java/db/migration/V10__backfill_structured_source_values.java \
  src/main/java/db/kingbase/V10__backfill_structured_source_values.java
```

Expected: no whitespace errors and no V1/V10 diff. Review all other changes as belonging either to the
already-existing projection feature or this V2/V11 extension.

- [x] **Step 5: Import the existing simulated workbook through the local application**

Ensure the dev backend responds at `http://127.0.0.1:18080/api/v1`, start the canonical
`personnel-archive-desktop-b` client if needed, and import:

```text
/Users/zhongerbing/Documents/xm/personnel-archive/outputs/019fb113-6390-74d2-b3db-178ed1862f9c/中文日期年龄导入模拟.xlsx
```

Expected:

- the item status is `IMPORTED`, not `H5 日期格式不正确`;
- the new active archive exists;
- `二〇二四.五.三` is visible unchanged;
- the corresponding database projection is `2024-05-03`;
- a freshly exported XLSX reopens with the same `二〇二四.五.三` source text.

- [x] **Step 6: Record completion evidence**

Report the exact focused/full test results, local import batch result, database projection, exported
source value, files changed, and the explicit limitation that KingbaseES received static/shared-code
verification but not a real runtime deployment test.

Local acceptance evidence already collected:

- the final affected regression set passed `79/79`, and the post-review authoritative
  `./mvnw verify` passed `140/140` with Docker-backed MySQL tests enabled;
- import batch `#46` completed as `IMPORTED`; the prior `H5 日期格式不正确` no longer occurs;
- active person `SIM-CN-20260730-001` preserved dispatch date `二〇二四.五.三` and stored
  projections `2024 / 5 / 3`;
- exact ages `三十五岁` and `35岁` projected to `35`, while `三十多岁` remained `NULL`;
- the regenerated workbook reopened successfully and still contained all source-visible date/age
  strings unchanged;
- local MySQL reached V11; KingbaseES verification remains the shared-code/static migration contract,
  not a real KingbaseES runtime deployment.
- `git diff --check` completed without whitespace errors, and the frozen V1/V10 paths had no tracked
  diff.
