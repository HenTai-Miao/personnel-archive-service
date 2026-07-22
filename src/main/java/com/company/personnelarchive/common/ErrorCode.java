/* API 稳定错误目录：集中绑定错误码、HTTP 状态和可安全展示的中文消息。 */
package com.company.personnelarchive.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数不正确"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "登录凭证无效或已过期"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码不正确"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "账号暂时锁定，请稍后重试"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有执行此操作的权限"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "档案不存在"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "仅支持真实的 .xlsx 文件"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过大小限制"),
    BATCH_EXPORT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "批量导出内容超过大小限制"),
    TEMPLATE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "档案模板结构不正确"),
    TEMPLATE_CAPACITY_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "档案内容超过模板容量"),
    INVALID_IDENTITY_CARD(HttpStatus.UNPROCESSABLE_ENTITY, "身份证号格式或校验位不正确"),
    DUPLICATE_REQUIRES_DECISION(HttpStatus.CONFLICT, "档案已存在，请选择覆盖或跳过"),
    IDENTITY_CARD_CONFLICT(HttpStatus.CONFLICT, "身份证号已存在，但姓名不一致"),
    EMPLOYEE_NO_CONFLICT(HttpStatus.CONFLICT, "员工号已由其他人员使用"),
    CONCURRENT_ARCHIVE_CONFLICT(HttpStatus.CONFLICT, "档案已被其他操作更新，请重新预检"),
    STALE_IMPORT_DECISION(HttpStatus.CONFLICT, "档案状态已变化，请重新确认"),
    PENDING_IMPORT_CAPACITY_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, "待确认导入过多，请稍后重试"),
    INVALID_IMPORT_DECISION(HttpStatus.BAD_REQUEST, "导入决定无效"),
    TEMPLATE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "服务端 Excel 模板尚未配置"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系统处理失败");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
