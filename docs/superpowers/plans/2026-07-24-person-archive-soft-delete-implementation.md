# Person Archive Soft Delete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an audited, role-protected archive-list deletion action that marks the active person snapshot deleted while preserving every historical child row and photo.

**Architecture:** A Flyway migration expands `pa_person.delete_reason` to `LONGTEXT`. A dedicated transactional `PersonDeletionService` performs one tenant-scoped conditional update and writes a metadata-only audit entry; the desktop calls a POST command through a typed API boundary and owns only confirmation/presentation state.

**Tech Stack:** Java 21, Spring Boot 4.0.7, MyBatis, Flyway, MySQL 8.4.3, JUnit 5/Testcontainers, Electron 43, Vue 3, TypeScript 5.9, Pinia, Element Plus, Avue, Ky, Zod 4, Vitest, Playwright.

## Global Constraints

- The endpoint is `POST /api/v1/persons/{personId}/deletion`; the renderer never sends or manipulates `is_deleted` directly.
- Only `ADMIN` and `ARCHIVE_EDITOR` may delete. `VIEWER` remains read/export only.
- A reason is mandatory, defaults to `误导入`, is trimmed, and is limited to 10,000 UTF-16 code units at both client and server boundaries.
- `delete_reason` is `LONGTEXT`; existing values are preserved by Flyway V4.
- Soft deletion sets `is_deleted`, `deleted_at`, `deleted_by`, `delete_reason`, and `updated_by` only. It does not set `replaced_by_person_id` or touch child/photo/import rows.
- A successful delete and its `DELETE` audit entry share one transaction. The audit log never copies the free-text reason.
- Existing JDBC timezone design/plan commits remain separate and are not implemented in this feature's commits.
- Preserve desktop user-owned untracked directories `design-previews/central-enterprise-office-v2/` and `output/`.
- Follow red → green → refactor for every production change.

---

### Task 1: Expand `delete_reason` with a tested Flyway migration

**Files:**
- Create: `src/main/resources/db/migration/V4__expand_person_delete_reason.sql`
- Modify: `src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: Existing nullable `pa_person.delete_reason VARCHAR(255)` and deletion check constraint.
- Produces: Nullable `LONGTEXT` column with every existing constraint intact.

- [ ] **Step 1: Add the failing migration assertion**

After Flyway migration in `migrationEnforcesActiveOnlyUniquenessAndAllowsHistory`, add:

```java
assertThat(jdbc.queryForObject("""
    SELECT DATA_TYPE
    FROM information_schema.columns
    WHERE table_schema='personnel_archive'
      AND table_name='pa_person'
      AND column_name='delete_reason'
    """, String.class)).isEqualTo("longtext");
```

- [ ] **Step 2: Run the focused test and verify RED**

```bash
./mvnw -Dtest=MySqlMigrationIntegrationTest test
```

Expected: FAIL because the current data type is `varchar`.

- [ ] **Step 3: Add the V4 migration**

Create `src/main/resources/db/migration/V4__expand_person_delete_reason.sql`:

```sql
ALTER TABLE pa_person
    MODIFY COLUMN delete_reason LONGTEXT NULL;
```

- [ ] **Step 4: Re-run and verify GREEN**

```bash
./mvnw -Dtest=MySqlMigrationIntegrationTest test
```

Expected: `BUILD SUCCESS`, `Tests run: 1`, and `Skipped: 0`.

- [ ] **Step 5: Commit the migration**

```bash
git add src/main/resources/db/migration/V4__expand_person_delete_reason.sql \
  src/test/java/com/company/personnelarchive/configuration/MySqlMigrationIntegrationTest.java
git commit -m "feat: expand archive deletion reasons"
```

---

### Task 2: Implement the transactional soft-delete domain operation

**Files:**
- Create: `src/main/java/com/company/personnelarchive/person/PersonDeletionService.java`
- Create: `src/test/java/com/company/personnelarchive/person/PersonDeletionServiceTest.java`
- Modify: `src/main/java/com/company/personnelarchive/person/PersonMapper.java`
- Modify: `src/main/java/com/company/personnelarchive/audit/AuditService.java`

**Interfaces:**
- Consumes: `delete(long personId, String reason, long operatorId)`.
- Produces: One active-row soft delete or `BusinessException(NOT_FOUND)`; one successful `DELETE/PERSON` audit event.

- [ ] **Step 1: Write the service RED tests**

Create `PersonDeletionServiceTest` with these three Given/When/Then cases:

```java
class PersonDeletionServiceTest {
    private final PersonMapper persons = mock(PersonMapper.class);
    private final AuditService audit = mock(AuditService.class);
    private final ArchiveProperties properties = properties();
    private final PersonDeletionService service = new PersonDeletionService(persons, audit, properties);

    @Test
    void trimsReasonAndAuditsWhenActiveArchiveIsDeleted() {
        // Given
        when(persons.softDeleteActive(1, 7, 9, "误导入")).thenReturn(1);

        // When
        service.delete(7, "  误导入  ", 9);

        // Then
        var ordered = inOrder(persons, audit);
        ordered.verify(persons).softDeleteActive(1, 7, 9, "误导入");
        ordered.verify(audit).record(9L, "DELETE", "PERSON", 7L, "DELETED", true);
    }

    @Test
    void returnsNotFoundWithoutAuditWhenArchiveIsNotActive() {
        // Given
        when(persons.softDeleteActive(1, 7, 9, "误导入")).thenReturn(0);

        // When / Then
        assertThatThrownBy(() -> service.delete(7, "误导入", 9))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).code())
            .isEqualTo(ErrorCode.NOT_FOUND);
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsReasonBeyondApplicationLimitBeforeWriting() {
        // Given
        String reason = "删".repeat(10_001);

        // When / Then
        assertThatThrownBy(() -> service.delete(7, reason, 9))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).code())
            .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(persons, audit);
    }

    @Test
    void rejectsBlankReasonBeforeWriting() {
        // Given
        String reason = "   \n\t";

        // When / Then
        assertThatThrownBy(() -> service.delete(7, reason, 9))
            .isInstanceOf(BusinessException.class)
            .extracting(error -> ((BusinessException) error).code())
            .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(persons, audit);
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000,
                "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500,
                "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
    }
}
```

Import `java.time.Duration` and the existing AssertJ/Mockito static APIs used by neighboring service tests.

- [ ] **Step 2: Run the service test and verify RED**

```bash
./mvnw -Dtest=PersonDeletionServiceTest test
```

Expected: test compilation fails because `PersonDeletionService` and `softDeleteActive` do not exist.

- [ ] **Step 3: Add the mapper update and audit action**

Add to `PersonMapper`:

```java
@Update("""
    UPDATE pa_person
    SET is_deleted=1,
        deleted_at=CURRENT_TIMESTAMP(6),
        deleted_by=#{operatorId},
        delete_reason=#{reason},
        updated_by=#{operatorId}
    WHERE tenant_id=#{tenantId} AND id=#{personId} AND is_deleted=0
    """)
int softDeleteActive(@Param("tenantId") long tenantId, @Param("personId") long personId,
                     @Param("operatorId") long operatorId, @Param("reason") String reason);
```

Add `"DELETE"` to `AuditService.ACTIONS`. Do not pass the reason to `AuditMapper`.

- [ ] **Step 4: Add the minimal transactional service**

Create `PersonDeletionService.java`:

```java
/* 人员档案删除服务：只软删除活动根快照，并在同一事务写入不含原因正文的审计。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonDeletionService {
    static final int MAX_REASON_LENGTH = 10_000;
    private final PersonMapper persons;
    private final AuditService audit;
    private final ArchiveProperties properties;

    public PersonDeletionService(PersonMapper persons, AuditService audit, ArchiveProperties properties) {
        this.persons = persons;
        this.audit = audit;
        this.properties = properties;
    }

    @Transactional
    public void delete(long personId, String reason, long operatorId) {
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.isEmpty() || normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "删除原因须为 1 至 10000 个字符");
        }
        if (persons.softDeleteActive(properties.tenantId(), personId, operatorId, normalizedReason) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "档案不存在或已删除");
        }
        audit.record(operatorId, "DELETE", "PERSON", personId, "DELETED", true);
    }
}
```

- [ ] **Step 5: Run service tests and verify GREEN**

```bash
./mvnw -Dtest=PersonDeletionServiceTest test
```

Expected: every reason, missing-row, mapper, and audit test passes.

- [ ] **Step 6: Commit the domain operation**

```bash
git add src/main/java/com/company/personnelarchive/person/PersonDeletionService.java \
  src/main/java/com/company/personnelarchive/person/PersonMapper.java \
  src/main/java/com/company/personnelarchive/audit/AuditService.java \
  src/test/java/com/company/personnelarchive/person/PersonDeletionServiceTest.java
git commit -m "feat: soft delete active personnel archives"
```

---

### Task 3: Expose and authorize the deletion command

**Files:**
- Modify: `src/main/java/com/company/personnelarchive/person/PersonController.java`
- Modify: `src/test/java/com/company/personnelarchive/person/PersonControllerTest.java`
- Modify: `src/main/java/com/company/personnelarchive/configuration/SecurityConfiguration.java`
- Create: `src/test/java/com/company/personnelarchive/person/PersonDeletionFlowIntegrationTest.java`
- Modify: `openapi/personnel-archive-api.yaml`

**Interfaces:**
- Consumes: `PersonDeletionService.delete(personId, reason, operatorId)`.
- Produces: POST JSON command returning 204; stable 400/401/403/404 behavior; OpenAPI request schema `PersonDeletionRequest`.

- [ ] **Step 1: Add controller and security RED coverage**

Extend `PersonControllerTest` with a mocked `PersonDeletionService`, update every constructor call, and add:

```java
@Test
void returnsNoContentAfterDeletingArchive() {
    // Given
    PersonDeletionService deletions = mock(PersonDeletionService.class);
    var controller = controllerWith(deletions);
    var principal = new AuthPrincipal(9, 1, "editor", Set.of("ARCHIVE_EDITOR"));

    // When
    var response = controller.delete(7, new PersonController.DeletionRequest("误导入"), principal);

    // Then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    verify(deletions).delete(7, "误导入", 9);
}
```

Create a real-MySQL `PersonDeletionFlowIntegrationTest` that inserts an editor, a viewer, one active person, one employment row, and one photo. Its independent tests must assert:

```java
// editor: 204, is_deleted=1, deleted_by/editor, >255-char reason preserved,
// deleted_at non-null, replaced_by_person_id null, child/photo counts unchanged,
// active list/detail no longer return the person, DELETE audit count is 1.

// viewer: POST with ROLE_VIEWER is 403 and is_deleted remains 0.
// anonymous: POST is 401 and is_deleted remains 0.
// validation: blank or 10_001-character reason is 400 and is_deleted remains 0.
// concurrency: a row already marked deleted returns 404 and creates no second DELETE audit.
```

Authenticate MockMvc with an `AuthPrincipal` plus `ROLE_ADMIN`, `ROLE_ARCHIVE_EDITOR`, or `ROLE_VIEWER` authorities so both the security matcher and `@AuthenticationPrincipal` boundary execute.

- [ ] **Step 2: Run the new HTTP tests and verify RED**

```bash
./mvnw -Dtest=PersonControllerTest,PersonDeletionFlowIntegrationTest test
```

Expected: compilation/route failures because the endpoint and role matcher do not exist.

- [ ] **Step 3: Add the controller route**

Inject `PersonDeletionService` into `PersonController`, then add:

```java
@PostMapping(value = "/persons/{personId}/deletion", consumes = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<Void> delete(
    @PathVariable @Min(1) long personId,
    @Valid @RequestBody DeletionRequest request,
    @AuthenticationPrincipal AuthPrincipal principal) {
    deletions.delete(personId, request.reason(), principal.userId());
    return ResponseEntity.noContent().build();
}

public record DeletionRequest(
    @NotBlank @Size(max = PersonDeletionService.MAX_REASON_LENGTH) String reason
) {}
```

Keep the nested request record free of identifiers or `is_deleted` fields.

- [ ] **Step 4: Add authoritative route permission**

Before the generic `/api/v1/**` matcher in `SecurityConfiguration`, add:

```java
.requestMatchers(HttpMethod.POST, "/api/v1/persons/*/deletion")
    .hasAnyRole("ADMIN", "ARCHIVE_EDITOR")
```

Import `org.springframework.http.HttpMethod`. CORS already permits POST and requires no change.

- [ ] **Step 5: Update OpenAPI**

Add the POST operation under `/persons/{personId}/deletion`, reference `PersonId`, accept `PersonDeletionRequest`, and declare 204/400/401/403/404. Add:

```yaml
PersonDeletionRequest:
  type: object
  additionalProperties: false
  required: [reason]
  properties:
    reason: { type: string, minLength: 1, maxLength: 10000, default: 误导入 }
```

- [ ] **Step 6: Run HTTP/integration tests and verify GREEN**

```bash
./mvnw -Dtest=PersonControllerTest,PersonDeletionFlowIntegrationTest test
```

Expected: all tests pass with Testcontainers `Skipped: 0`.

- [ ] **Step 7: Commit the API boundary**

```bash
git add src/main/java/com/company/personnelarchive/person/PersonController.java \
  src/main/java/com/company/personnelarchive/configuration/SecurityConfiguration.java \
  src/test/java/com/company/personnelarchive/person/PersonControllerTest.java \
  src/test/java/com/company/personnelarchive/person/PersonDeletionFlowIntegrationTest.java \
  openapi/personnel-archive-api.yaml
git commit -m "feat: expose archive deletion command"
```

---

### Task 4: Add the typed desktop deletion API and dialog

**Files:**
- Modify: `src/renderer/features/person-list/api.ts`
- Create: `src/renderer/features/person-list/PersonDeleteAction.vue`
- Create: `tests/unit/person-deletion-api.test.ts`
- Create: `tests/unit/person-delete-action.test.ts`

**Interfaces:**
- Consumes: `deletePerson(personId, { reason })`.
- Produces: Trimmed JSON request with 1–10,000 character reason; `deleted` and `missing` component events.

- [ ] **Step 1: Write API boundary RED tests**

Mock `requestVoid` and assert:

```typescript
expect(buildPersonDeletionRequest({ reason: "  误导入  " })).toEqual({ reason: "误导入" })
expect(() => buildPersonDeletionRequest({ reason: "   " })).toThrow()
expect(() => buildPersonDeletionRequest({ reason: "删".repeat(10_001) })).toThrow()
await deletePerson(personIdSchema.parse("7"), { reason: "误导入" })
expect(requestVoid).toHaveBeenCalledWith("persons/7/deletion", {
  method: "post",
  json: { reason: "误导入" },
})
```

- [ ] **Step 2: Run API test and verify RED**

```bash
pnpm test -- tests/unit/person-deletion-api.test.ts
```

Expected: FAIL because the schema/builder/API functions do not exist.

- [ ] **Step 3: Implement the typed API boundary**

In `api.ts`, add a strict Zod schema and named exports:

```typescript
const personDeletionRequestSchema = z
  .object({ reason: z.string().trim().min(1).max(10_000) })
  .strict()

export function buildPersonDeletionRequest(input: unknown) {
  return personDeletionRequestSchema.parse(input)
}

export function deletePerson(personId: unknown, input: unknown): Promise<void> {
  const parsedPersonId = personIdSchema.parse(personId)
  return requestVoid(`persons/${parsedPersonId}/deletion`, {
    method: "post",
    json: buildPersonDeletionRequest(input),
  })
}
```

Import `requestVoid` and reuse the existing branded `personIdSchema`.

- [ ] **Step 4: Write component RED tests**

Mount `PersonDeleteAction` with Element Plus and a mocked `deletePerson`. Test one outcome per case:

```typescript
// opens with a textarea containing “误导入”;
// cancel/close does not call deletePerson;
// blank reason disables confirmation;
// success calls deletePerson once, closes, and emits deleted;
// ApiError("NOT_FOUND", ..., 404) closes and emits missing;
// other API failure leaves the dialog open with a safe error message;
// while the promise is pending, the confirm button is loading/disabled.
```

- [ ] **Step 5: Implement `PersonDeleteAction.vue`**

The component owns `visible`, `reason`, `submitting`, and `message`; resets the reason to `误导入` on each open; uses `<el-input type="textarea" :maxlength="10000" show-word-limit>`; and calls `deletePerson`. Narrow caught values to `ApiError`/`Error`; unknown values must be re-thrown. Emit `missing` only for stable code `NOT_FOUND`.

Render stable selectors `data-test="delete-person"`, `data-test="delete-reason"`, and `data-test="confirm-delete-person"`. The confirm button stays disabled when trimmed input is empty or submitting.

- [ ] **Step 6: Run component/API tests and verify GREEN**

```bash
pnpm test -- tests/unit/person-deletion-api.test.ts tests/unit/person-delete-action.test.ts
```

Expected: all tests pass with no warnings.

---

### Task 5: Integrate deletion into the archive list and E2E flow

**Files:**
- Modify: `src/renderer/features/person-list/PersonListPage.vue`
- Modify: `src/renderer/features/person-list/person-list-crud.ts`
- Modify: `src/renderer/shared/api/error.ts`
- Create: `tests/unit/person-list-deletion.test.ts`
- Create: `tests/e2e/person-deletion.spec.ts`

**Interfaces:**
- Consumes: `PersonDeleteAction` events.
- Produces: Role-aware red action, success/missing feedback, preview/selection reset, and previous-page fallback.

- [ ] **Step 1: Write list-integration RED tests**

Use a dedicated Avue stub that renders the `menu` slot and initialize `useAuthStore().user` for each role. Assert:

```typescript
// ADMIN and ARCHIVE_EDITOR see “删除”; VIEWER does not.
// deleted event when the current page has multiple rows reloads the same page.
// deleted event when page 1 has one row reloads page 0.
// refresh calls clear the active preview and Pinia selection.
// missing event reloads and shows “档案不存在或已删除”.
```

- [ ] **Step 2: Run list test and verify RED**

```bash
pnpm test -- tests/unit/person-list-deletion.test.ts
```

Expected: FAIL because the list has no deletion action or result handling.

- [ ] **Step 3: Integrate the action without expanding page responsibility**

Import `PersonDeleteAction`; reuse the existing editor-role computed value; add it after Export in the Avue menu slot:

```vue
<PersonDeleteAction
  v-if="canImport"
  :person-id="row.id"
  :person-name="row.name"
  @deleted="loadAfterDeletion('档案已删除', false)"
  @missing="loadAfterDeletion('档案不存在或已删除', true)"
/>
```

Add one page-level method:

```typescript
async function loadAfterDeletion(message: string, failed: boolean): Promise<void> {
  const targetPage = store.results.length === 1 && store.page > 0 ? store.page - 1 : store.page
  actionMessage.value = message
  actionFailed.value = failed
  await loadPage(targetPage)
}
```

`loadPage` already clears preview, and `store.load` already clears selected IDs. Increase Avue `menuWidth` only enough for View/Export/Delete. Add `NOT_FOUND` to `ERROR_MESSAGES` with “档案不存在或已删除”.

- [ ] **Step 4: Add the Electron E2E narrative**

Create `tests/e2e/person-deletion.spec.ts` using the existing mocked-route login pattern. Fulfill an ADMIN login, return one person until POST deletion succeeds, assert the default textarea reason, submit, assert the request JSON equals `{ reason: "误导入" }`, and then assert the row disappears while the success message is visible.

- [ ] **Step 5: Run desktop unit/type/build verification**

```bash
pnpm test -- tests/unit/person-deletion-api.test.ts \
  tests/unit/person-delete-action.test.ts tests/unit/person-list-deletion.test.ts
pnpm lint
pnpm typecheck
pnpm build
```

Expected: every command exits zero.

- [ ] **Step 6: Measure edited TypeScript/Vue files**

```bash
awk '!/^[[:space:]]*$/ && !/^[[:space:]]*(\/\/|#|--)/' \
  src/renderer/features/person-list/PersonListPage.vue | wc -l
```

Expected: no edited hand-written TypeScript/Vue file exceeds 250 pure LOC. If `PersonListPage.vue` crosses 250, extract deletion-result coordination into a focused composable before committing.

- [ ] **Step 7: Commit the desktop feature**

```bash
git add src/renderer/features/person-list/api.ts \
  src/renderer/features/person-list/PersonDeleteAction.vue \
  src/renderer/features/person-list/PersonListPage.vue \
  src/renderer/features/person-list/person-list-crud.ts \
  src/renderer/shared/api/error.ts \
  tests/unit/person-deletion-api.test.ts tests/unit/person-delete-action.test.ts \
  tests/unit/person-list-deletion.test.ts tests/e2e/person-deletion.spec.ts
git commit -m "feat: add archive soft deletion action"
```

---

### Task 6: Synchronize documentation and run complete verification

**Files:**
- Modify: `../AGENTS.md`
- Modify: `AGENTS.md`
- Modify: `../CODE_GUIDE.md`
- Modify: `../personnel-archive-desktop/AGENTS.md`

**Interfaces:**
- Consumes: Completed backend and desktop behavior.
- Produces: Accurate implementation status, V4 migration inventory, deletion flow/security/audit invariants, and final evidence.

- [ ] **Step 1: Update implementation status and source maps**

Document Flyway V4, the POST deletion endpoint, `LONGTEXT` reason with 10,000-character request limit, ADMIN/editor permissions, metadata-only DELETE audit, preserved child rows/photos, and archive-list dialog/pagination behavior. Move the behavior into implemented status only after the tests exist.

- [ ] **Step 2: Run authoritative service verification**

```bash
./mvnw verify
rg -n 'skipped="0"' target/surefire-reports/TEST-*PersonDeletion*.xml \
  target/surefire-reports/TEST-*MySqlMigrationIntegrationTest.xml
```

Expected: `BUILD SUCCESS`, deletion/migration suites report zero skipped tests.

- [ ] **Step 3: Run authoritative desktop verification**

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

Expected: all commands exit zero. Run `pnpm test:e2e` only from a normal desktop session; if Codex Seatbelt blocks Electron, report that exact unverified gate instead of weakening the policy.

- [ ] **Step 4: Verify live behavior**

Apply V4 by restarting the local service, open the running desktop client, delete a disposable imported fixture with a reason longer than 255 characters, and verify the row disappears. Query only non-sensitive metadata to confirm `is_deleted=1`, `deleted_at/deleted_by` present, `CHAR_LENGTH(delete_reason)>255`, child/photo counts unchanged, and one DELETE audit row.

- [ ] **Step 5: Commit service/client documentation separately**

In the service repository:

```bash
git add AGENTS.md openapi/personnel-archive-api.yaml
git commit -m "docs: document archive soft deletion"
```

In the desktop repository:

```bash
git add AGENTS.md
git commit -m "docs: document archive deletion workflow"
```

The root `AGENTS.md` and `CODE_GUIDE.md` are workspace documents outside both child repositories; keep their synchronized edits in place.

- [ ] **Step 6: Final repository audit**

```bash
git -C ../personnel-archive-service status --short
git -C ../personnel-archive-desktop status --short
git -C ../personnel-archive-service log -8 --oneline
git -C ../personnel-archive-desktop log -6 --oneline
```

Expected: service and desktop source changes are committed; desktop status contains only the pre-existing user-owned untracked design/output directories; timezone and soft-delete changes remain separate commits.
