# 开放日期区间标准化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将所有合法开放日期区间统一为 `起点—至今`，并通过 Flyway V14 同步已有活动档案，保留软删除历史。

**Architecture:** 冻结 V1/V2/V3 与 V10–V13；实时导入由 V4 在 V3 校验后统一开放区间组合文本。MySQL 与 KingbaseES 的 V14 Java 入口共享数据库无关 JDBC 回填，只重建活动主要学历和工作经历的 `raw_text`。

**Tech Stack:** Java 21、Spring Boot 4、Apache POI、Flyway Java migrations、MyBatis、JUnit 5、AssertJ、Testcontainers MySQL 8.4。

**Repository rule:** 不创建 commit、不 push，不修改用户附件或软删除历史。

---

## File map

**Modify:**

- `src/main/java/com/company/personnelarchive/archiveimport/SourceValueNormalizerV4.java` — 实时开放区间组合文本规范。
- `src/test/java/com/company/personnelarchive/archiveimport/SourceValueNormalizerV4Test.java` — V4 输入兼容与标准输出。
- `src/test/java/com/company/personnelarchive/archiveimport/LegacyDateRangeSeparatorImportTest.java` — 真实模板重复区段读取。
- `src/test/java/com/company/personnelarchive/configuration/CanonicalValueMigrationIntegrationTest.java` — 真实 MySQL V13→V14 验证。
- `src/test/java/com/company/personnelarchive/configuration/KingbaseMigrationContractTest.java` — KingbaseES V14 静态契约。
- `src/test/java/com/company/personnelarchive/PersonnelArchiveFlowIntegrationTest.java` — 导入、数据库、详情和导出主链路。
- `src/test/java/com/company/personnelarchive/person/PersonQueryServiceTest.java` — 详情标准文本夹具。
- `src/test/java/com/company/personnelarchive/configuration/ApiContractSerializationTest.java` — API 示例标准文本。
- `AGENTS.md`、`../AGENTS.md`、`../CODE_GUIDE.md` — 版本、流程和不变量。

**Create:**

- `src/main/java/com/company/personnelarchive/migration/OpenEndedRangeTextBackfillV4.java` — 活动组合文本回填。
- `src/main/java/db/migration/V14__canonicalize_open_ended_range_text.java` — MySQL V14 入口。
- `src/main/java/db/kingbase/V14__canonicalize_open_ended_range_text.java` — KingbaseES V14 入口。

---

### Task 1: 新导入开放区间标准化

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/archiveimport/SourceValueNormalizerV4Test.java`
- Modify: `src/test/java/com/company/personnelarchive/archiveimport/LegacyDateRangeSeparatorImportTest.java`
- Modify: `src/main/java/com/company/personnelarchive/archiveimport/SourceValueNormalizerV4.java`

- [x] **Step 1: 写 V4 红灯**

在 `SourceValueNormalizerV4Test` 增加参数化测试，保留 V3 既有测试不动：

```java
@ParameterizedTest
@ValueSource(strings = {
    "2018-06至今",
    "2018-06-今",
    "2018-06—至今",
    "二〇一八年六月至今"
})
void canonicalizesOpenEndedRangesWithVisibleSeparator(String input) {
    var normalized = SourceValueNormalizerV4.normalizeDateRange(input);

    assertThat(normalized.start().text()).isEqualTo("2018-06");
    assertThat(normalized.end().text()).isEqualTo("至今");
    assertThat(normalized.text()).isEqualTo("2018-06—至今");
}
```

- [x] **Step 2: 在生产模板读取测试加入开放工作经历**

在 `LegacyDateRangeSeparatorImportTest` 的工作经历区加入 `2018-06至今`，断言：

```java
tuple("2018-06—至今\t测试在职经历", "2018-06", "至今")
```

结束端的年/月/日投影由后续完整流程测试断言为 `NULL`。

- [x] **Step 3: 运行红灯**

```bash
export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.11-oracle"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -Dtest=SourceValueNormalizerV4Test,LegacyDateRangeSeparatorImportTest test
```

预期：比较失败，实际值仍是 `2018-06至今`；不能是编译错误。

- [x] **Step 4: 最小实现 V4 输出规范**

V4 继续委托 V3，随后只改组合文本：

```java
private static SourceValueNormalizerV3.NormalizedDateRange canonicalizeOpenEnded(
    SourceValueNormalizerV3.NormalizedDateRange range
) {
    if (!"至今".equals(range.end().text())) return range;
    return new SourceValueNormalizerV3.NormalizedDateRange(
        range.start(), range.end(), range.start().text() + "—至今");
}
```

`normalizeDateRange` 的“无重复连接符”和“兼容连接符”两个返回路径都调用该方法；闭区间、单日期和空值保持 V3 结果。

- [x] **Step 5: 运行绿灯与冻结 V3 回归**

```bash
./mvnw -Dtest=SourceValueNormalizerV3Test,SourceValueNormalizerV4Test,LegacyDateRangeSeparatorImportTest test
```

预期：V3 仍输出冻结的 `2018-06至今` 语义测试，V4 和真实模板输出 `2018-06—至今`，全部通过。

---

### Task 2: Flyway V14 活动档案迁移

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/configuration/CanonicalValueMigrationIntegrationTest.java`
- Create: `src/main/java/com/company/personnelarchive/migration/OpenEndedRangeTextBackfillV4.java`
- Create: `src/main/java/db/migration/V14__canonicalize_open_ended_range_text.java`
- Create: `src/main/java/db/kingbase/V14__canonicalize_open_ended_range_text.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/KingbaseMigrationContractTest.java`

- [x] **Step 1: 写真实 MySQL V13→V14 红灯**

在 `CanonicalValueMigrationIntegrationTest` 中：先 `flyway("13").migrate()`；建立一个活动人员和一个软删除人员；分别插入：

```text
MAIN education: start_date=2010-09, end_date=至今, raw_text=2010-09至今\t活动学历
work:           start_date=2018-06, end_date=至今, raw_text=2018-06至今\t活动单位
```

软删除人员插入相同旧格式。执行 `flyway(null).migrate()` 后断言：

```text
活动学历 raw_text = 2010-09—至今\t活动学历
活动工作 raw_text = 2018-06—至今\t活动单位
软删除学历/工作 raw_text 保持旧格式
start_date、end_date 和全部数字投影逐项不变
Flyway 当前版本 = 14
```

- [x] **Step 2: 运行迁移红灯**

```bash
./mvnw -Dtest=CanonicalValueMigrationIntegrationTest test
```

预期：V14 尚不存在，活动 `raw_text` 仍为无破折号形式，断言失败。

- [x] **Step 3: 实现共享 V14 回填**

`OpenEndedRangeTextBackfillV4.migrate(Connection)` 使用参数化批量更新：

```java
public static void migrate(Connection connection) throws SQLException {
    rewriteEducation(connection);
    rewriteWork(connection);
}
```

学历查询限定：

```sql
SELECT e.id,e.start_date,e.raw_text
FROM pa_education e
JOIN pa_person p ON p.id=e.person_id AND p.tenant_id=e.tenant_id
WHERE p.is_deleted=0 AND e.education_type='MAIN' AND e.end_date='至今'
ORDER BY e.id
```

工作查询限定：

```sql
SELECT w.id,w.start_date,w.organization_and_position
FROM pa_work_experience w
JOIN pa_person p ON p.id=w.person_id AND p.tenant_id=w.tenant_id
WHERE p.is_deleted=0 AND w.end_date='至今'
ORDER BY w.id
```

两者使用 `start_date + "—至今"`；学历保留 `raw_text` 第一个制表符后的文字，工作使用独立的
`organization_and_position`。起点为空时抛出只含表名、记录 ID 和字段名的 `SQLException`，
不把业务原文写入异常。每 500 行执行一次 batch，Flyway 事务负责原子性。

- [x] **Step 4: 创建双数据库 V14 入口**

两个入口都调用同一实现并返回固定校验和：

```java
public final class V14__canonicalize_open_ended_range_text extends BaseJavaMigration {
    @Override
    public Integer getChecksum() {
        return 14_001;
    }

    @Override
    public void migrate(Context context) throws Exception {
        OpenEndedRangeTextBackfillV4.migrate(context.getConnection());
    }
}
```

包名分别是 `db.migration` 和 `db.kingbase`。

- [x] **Step 5: 运行 MySQL 迁移绿灯**

```bash
./mvnw -Dtest=CanonicalValueMigrationIntegrationTest test
```

预期：活动数据更新、软删除历史不变、版本 14，全部通过。

- [x] **Step 6: 写 KingbaseES 契约红灯并实现**

`KingbaseMigrationContractTest` 新增 V14 文件路径、版本 `14`、校验和 `14_001`、共享委托类名断言；先在入口不存在时运行并确认红灯，再创建 Kingbase 入口并转绿：

```bash
./mvnw -Dtest=KingbaseMigrationContractTest test
```

---

### Task 3: 主链路、详情与导出合同

**Files:**
- Modify: `src/test/java/com/company/personnelarchive/PersonnelArchiveFlowIntegrationTest.java`
- Modify: `src/test/java/com/company/personnelarchive/person/PersonQueryServiceTest.java`
- Modify: `src/test/java/com/company/personnelarchive/configuration/ApiContractSerializationTest.java`

- [x] **Step 1: 收紧完整流程断言**

现有工作经历输入 `二〇二四年七月至今` 不变；新增数据库断言：

```java
assertThat(jdbc.queryForObject(
    "SELECT raw_text FROM pa_work_experience WHERE person_id=?", String.class, firstPersonId))
    .isEqualTo("2024-07—至今\t" + workDescription);
```

同时保留：`start_date=2024-07`、`end_date=至今`、结束端数字投影均为 `NULL`。详情 DTO 和重建 Excel 的工作经历日期单元格都断言 `2024-07—至今`。

- [x] **Step 2: 更新标准夹具**

把 `PersonQueryServiceTest` 和 `ApiContractSerializationTest` 中人为构造的
`2024-07至今` 改为 `2024-07—至今`。API JSON 字段结构不变。

- [x] **Step 3: 运行主链路测试**

```bash
./mvnw -Dtest=PersonnelArchiveFlowIntegrationTest,PersonQueryServiceTest,ApiContractSerializationTest test
```

预期：数据库、详情和 Excel 重建都使用同一标准文本。

执行时主链路首次红灯揭示 `SnapshotWriter.orderedRaw()` 仍调用冻结 V3，导致读取层的
`起点—至今` 在写库前降级。已补充 `SnapshotWriterTest#writesOpenEndedRangeWithCurrentCanonicalSeparator`
并以 V3/V4 toggle 证明根因，最终让 writer 与 reader 同用 V4 区间边界。

---

### Task 4: 文档、完整验证与运行态升级

**Files:**
- Modify: `AGENTS.md`
- Modify: `../AGENTS.md`
- Modify: `../CODE_GUIDE.md`
- Verify: `docs/superpowers/specs/2026-07-30-open-ended-date-range-canonicalization-design.md`

- [x] **Step 1: 更新产品和后端契约**

把 MySQL `V1–V13` 更新为 `V1–V14`，KingbaseES `V8–V13` 更新为 `V8–V14`。明确：

- 开放区间组合文本统一 `起点—至今`；
- V4 负责实时导入；
- V14 只重建活动学历/工作组合文本；
- V2/V3/V13 和软删除历史不变。

- [x] **Step 2: 更新代码导图和测试地图**

`CODE_GUIDE.md` 新增共享 V14 回填、双数据库入口和迁移测试说明；运维路线中的真实 KingbaseES 验证范围更新到 V14。

- [x] **Step 3: 运行定向验证**

```bash
./mvnw -Dtest=SourceValueNormalizerV3Test,SourceValueNormalizerV4Test,LegacyDateRangeSeparatorImportTest,CanonicalValueMigrationIntegrationTest,KingbaseMigrationContractTest,PersonnelArchiveFlowIntegrationTest,PersonQueryServiceTest,ApiContractSerializationTest test
```

预期：0 failures、0 errors。

- [x] **Step 4: 运行完整质量门**

```bash
./mvnw verify
```

预期：全部测试通过并完成 Spring Boot jar 打包。记录既有 Maven settings/Flyway 兼容警告，但不能把警告当失败或隐藏新错误。

- [x] **Step 5: 只读解析用户原附件**

使用编译后的 `ExcelArchiveReader` 只读解析附件，不写数据库、不打印个人字段；相关输出应为：

```text
education=[1996-09—1999-06, 1999-09—2002-06]
work=[2003-04—2009-06, 2009-10—2018-11, 2019-03—至今]
```

- [x] **Step 6: 重启后端并验证 V14**

停止当前后端会话，使用 Java 21 和 `dev` profile 重启。启动日志必须显示 schema 到 V14；随后运行：

```text
后端未认证探针 = 401
Vite http://localhost:5173 = 200
MySQL local-mysql = healthy
Electron 主进程和 Renderer 存活
```

不重置数据库、不修改上传源文件、不提交或 push。

## Post-review security hardening

- [x] V4 在正则和冻结 V3/V2 区间解析前拒绝超过 128 字符的原始日期范围，避免超长多分隔符输入造成近二次复杂度。
- [x] V14 以 `id>? ORDER BY id LIMIT 500` 扫描学历和工作经历，关闭每页 `ResultSet` 后才批量更新，避免 JDBC 驱动全量缓存迁移结果。
- [x] 新增确定性 RED/GREEN 回归测试；不使用时间阈值断言。
- [x] 安全修复后的完整 `./mvnw verify` 为 187/187 通过，0 failures、0 errors、0 skipped，Spring Boot JAR 打包成功。
- [x] 使用 Java 21 和 dev profile 重启后端；Flyway 确认 schema 为 V14，未认证 API 探针返回预期 401，Vite 返回 200，Electron renderer 存活，MySQL healthy。
