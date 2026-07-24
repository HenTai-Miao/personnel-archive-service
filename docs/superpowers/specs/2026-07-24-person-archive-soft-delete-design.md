# 人员档案软删除设计

## 目标

在人员档案列表的“档案操作”中增加删除能力，用于撤销误导入档案。删除只改变活动快照状态，不物理删除人员根记录、子表或照片；所有正常查询继续只读取 `is_deleted = 0` 的档案。

本功能与 JDBC 时区修复保持为独立提交和验证流程。

## 用户交互

- “档案操作”在“查看”“导出”之后显示红色“删除”按钮。
- 仅 `ADMIN` 和 `ARCHIVE_EDITOR` 显示删除按钮；`VIEWER` 不显示，但服务端仍独立执行权限校验。
- 点击删除后打开不可误触提交的确认对话框，明确显示档案姓名或“未填写姓名”。
- 删除原因使用多行文本，必填，默认值为“误导入”，去除首尾空白后长度为 1–10,000 个字符。
- 取消、关闭对话框或校验失败时不发送请求。
- 请求期间禁止重复提交。成功后关闭当前档案预览、清除批量选择、显示成功提示并刷新列表。
- 若当前页最后一条档案被删除，自动回到仍有数据的上一页；其他情况保持当前页。
- 若档案已被其他操作删除，显示“档案不存在或已删除”并刷新当前列表，不重复写入。

## API 与权限

新增命令式接口：

```text
POST /api/v1/persons/{personId}/deletion
Content-Type: application/json

{ "reason": "误导入" }
```

- 成功返回 `204 No Content`。
- 请求 DTO 对 `reason` 使用 `@NotBlank` 和 `@Size(max = 10000)`，应用服务再保存去除首尾空白后的内容。
- 非法人员 ID 或空白/超长原因返回 `VALIDATION_ERROR`。
- 档案不存在或已经删除返回 `NOT_FOUND`，安全消息为“档案不存在或已删除”。
- `ADMIN`、`ARCHIVE_EDITOR` 可以调用；`VIEWER` 和未认证请求分别得到 `FORBIDDEN`、`UNAUTHORIZED`。
- 采用 POST 命令而非带请求体的 DELETE，使原因只进入 JSON 请求体，并保持现有写操作与 CORS 方法集合不变。

## 数据库与事务

新增 Flyway `V4__expand_person_delete_reason.sql`：

```sql
ALTER TABLE pa_person
    MODIFY COLUMN delete_reason LONGTEXT NULL;
```

迁移只扩大字段类型，不改写现有值。软删除应用服务在单个事务内执行租户隔离的条件更新：

```sql
UPDATE pa_person
SET is_deleted = 1,
    deleted_at = CURRENT_TIMESTAMP(6),
    deleted_by = :operatorId,
    delete_reason = :reason,
    updated_by = :operatorId
WHERE tenant_id = :tenantId
  AND id = :personId
  AND is_deleted = 0;
```

- 更新行数必须为 1；为 0 时返回 `NOT_FOUND`。
- 不设置 `replaced_by_person_id`，因为手动删除没有替代快照。
- 不更新或删除人员子表、照片、导入历史。
- 活动唯一键通过既有生成列自动释放，允许以后重新导入同一非空员工号或身份证。
- 成功更新后在同一事务写入 `DELETE` / `PERSON` 审计记录；事务失败时状态和成功审计一起回滚。
- 审计不保存删除原因，避免把自由文本复制到日志；原因只保存在 `pa_person.delete_reason`。

## 服务端结构

- `PersonController` 只负责 HTTP、Bean Validation 和认证用户边界。
- 新建 `PersonDeletionService`，负责原因规范化、事务、条件软删除和审计。
- `PersonMapper` 新增租户隔离的手动软删除更新，覆盖导入仍继续使用原有 `softDeleteForOverwrite`。
- `AuditService` 允许 `DELETE` 动作，但不改变审计表结构。
- `SecurityConfiguration` 对删除命令增加 `ADMIN` / `ARCHIVE_EDITOR` 权限匹配。
- OpenAPI 同步新增请求、204/400/401/403/404 响应契约。

## 客户端结构

现有 `PersonListPage.vue` 已有 227 行纯代码，删除流程不直接堆入页面：

- 新建 `PersonDeleteAction.vue`，负责按钮、对话框、原因表单、提交状态和错误展示。
- 新建人员删除 API/schema 边界，使用 Zod 在发送前规范化并限制 1–10,000 字。
- 页面只负责权限判断以及在 `deleted` 事件后关闭预览、清空选择并请求正确分页。
- Avue 操作列适当加宽，仍只由自定义菜单插槽渲染“查看 / 导出 / 删除”。
- 不把客户端隐藏按钮当作权限控制，服务端始终权威校验。

## 测试与验收

服务端测试：

- Flyway 真实 MySQL 测试验证 `delete_reason` 为 `LONGTEXT`。
- 应用服务测试验证必填/裁剪、10,000 字边界、条件更新、零行并发结果和成功审计。
- 控制器/安全测试验证 ADMIN、ARCHIVE_EDITOR、VIEWER 与未登录响应。
- 真实 MySQL 流程验证根记录软删除、字段完整、子表/照片保留、活动查询消失、唯一键释放和 DELETE 审计。

客户端测试：

- API/schema 测试验证请求路径、JSON、空白原因和 10,001 字拒绝。
- 组件测试验证默认“误导入”、多行输入、取消不请求、重复提交禁止和错误保留。
- 列表测试验证角色可见性、成功刷新、预览关闭、选择清空和末页回退。
- Electron E2E 覆盖编辑员从活动目录软删除一份档案并看到该行消失。

完整验收运行 `./mvnw verify`、`pnpm lint`、`pnpm typecheck`、`pnpm test`、`pnpm build` 和正常桌面会话中的 `pnpm test:e2e`。Testcontainers 或 Electron E2E 因环境跳过时不能声称对应路径已经验证。

## 文档同步与非目标

- 更新根 `AGENTS.md`、服务/客户端 `AGENTS.md`、`CODE_GUIDE.md` 和 OpenAPI 的已实现状态与流程。
- 不增加历史档案恢复、历史列表、批量删除、硬删除或删除原因查询界面。
- 不修改导入覆盖语义；覆盖删除原因仍为 `IMPORT_OVERWRITE`。
