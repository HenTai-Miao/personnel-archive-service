#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SERVICE_NAME="personnel-archive-service.service"
APP_USER="personnel-archive"
APP_GROUP="personnel-archive"
INSTALL_DIR="/opt/personnel-archive-service"
LOG_DIR="${INSTALL_DIR}/log"
JAR_TARGET="${INSTALL_DIR}/personnel-archive-service.jar"
CONFIG_DIR="/etc/personnel-archive-service"
BACKUP_DIR="${CONFIG_DIR}/backups"
ENV_FILE="${CONFIG_DIR}/personnel-archive-service.env"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}"
JAR_SOURCE="${SCRIPT_DIR}/personnel-archive-service.jar"
UNIT_TEMPLATE="${SCRIPT_DIR}/service/personnel-archive-service.service.template"
CHECKSUM_FILE="${SCRIPT_DIR}/SHA256SUMS"
SERVICE_LOG="${LOG_DIR}/personnel-archive-service.log"
MINIMUM_FREE_KB=2097152

RECONFIGURE=false
NO_START=false
JAVA_BIN=""
JAVA_MAJOR=""
INSTALL_LOG=""
SECRET_TEMP_FILE=""

fail() {
    printf '错误：%s\n' "$1" >&2
    return 1
}

usage() {
    printf '%s\n' \
        '用法：sudo ./install.sh [--reconfigure] [--no-start]' \
        '  --reconfigure  仅交互更换数据库密码；保留三项既有安全密钥' \
        '  --no-start     安装文件但不在本次执行中启用或启动服务' \
        '  --help         显示本说明'
}

parse_options() {
    while (($# > 0)); do
        case "$1" in
            --reconfigure)
                RECONFIGURE=true
                ;;
            --no-start)
                NO_START=true
                ;;
            --help|-h)
                usage
                exit 0
                ;;
            *)
                usage >&2
                fail "不支持的参数。"
                return 1
                ;;
        esac
        shift
    done
}

require_root() {
    if [[ "${EUID}" -ne 0 ]]; then
        fail "必须使用 root 权限运行，例如 sudo ./install.sh。"
        return 1
    fi
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        fail "缺少必需命令：$1。离线安装器不会联网安装依赖。"
        return 1
    fi
}

reject_symlink() {
    if [[ -L "$1" ]]; then
        fail "拒绝使用符号链接路径：$1"
        return 1
    fi
}

prepare_install_log() {
    require_command mktemp
    require_command tee
    reject_symlink "$INSTALL_DIR"
    mkdir -p -- "$INSTALL_DIR"
    reject_symlink "$LOG_DIR"
    mkdir -p -- "$LOG_DIR"
    INSTALL_LOG="$(mktemp "${LOG_DIR}/install-$(date +%Y%m%d-%H%M%S).XXXXXX.log")"
    chmod 0600 "$INSTALL_LOG"
    exec > >(tee -a "$INSTALL_LOG") 2>&1
}

on_exit() {
    local status="$1"
    if [[ -n "${SECRET_TEMP_FILE:-}" && "$SECRET_TEMP_FILE" == "${CONFIG_DIR}/.secret."* ]]; then
        rm -f -- "$SECRET_TEMP_FILE"
    fi
    if [[ -n "${INSTALL_LOG:-}" && -f "$INSTALL_LOG" ]]; then
        if getent group "$APP_GROUP" >/dev/null 2>&1; then
            chown root:"$APP_GROUP" "$INSTALL_LOG" 2>/dev/null || true
            chmod 0640 "$INSTALL_LOG" 2>/dev/null || true
        else
            chmod 0600 "$INSTALL_LOG" 2>/dev/null || true
        fi
    fi
    if [[ "$status" -ne 0 ]]; then
        printf '安装未完成。请根据上方固定错误说明排查；不要发送环境配置文件。\n' >&2
        if [[ -n "${INSTALL_LOG:-}" ]]; then
            printf '安装日志：%s\n' "$INSTALL_LOG" >&2
        fi
    fi
}

systemd_quote() {
    local value="$1"
    local escaped
    if [[ "$value" == *$'\r'* || "$value" == *$'\n'* ]]; then
        fail "配置值不能包含回车或换行。"
        return 1
    fi
    escaped="${value//\\/\\\\}"
    escaped="${escaped//\"/\\\"}"
    printf '"%s"' "$escaped"
}

count_setting_lines() {
    local input_file="$1"
    local prefix="$2"
    local line
    local count=0
    while IFS= read -r line || [[ -n "$line" ]]; do
        if [[ "$line" == "$prefix"* ]]; then
            count=$((count + 1))
        fi
    done < "$input_file"
    printf '%s' "$count"
}

validate_environment_file() {
    local input_file="$1"
    local prefix
    local count
    local prefixes=(
        "SPRING_PROFILES_ACTIVE="
        "PA_SERVER_PORT="
        "PA_DB_URL="
        "PA_DB_USERNAME="
        "PA_DB_PASSWORD="
        "PA_TOKEN_PEPPER="
        "PA_IDENTITY_HMAC_KEY="
        "PA_IDENTITY_ENCRYPTION_KEY="
        "PA_LOG_FILE="
        "PA_LOG_ARCHIVE_PATTERN="
    )
    if [[ ! -f "$input_file" || -L "$input_file" ]]; then
        fail "受保护的环境配置不是普通文件。"
        return 1
    fi
    for prefix in "${prefixes[@]}"; do
        count="$(count_setting_lines "$input_file" "$prefix")"
        if [[ "$count" -ne 1 ]]; then
            fail "受保护的环境配置结构不完整或存在重复项。"
            return 1
        fi
    done
}

rewrite_environment_password() {
    local input_file="$1"
    local output_file="$2"
    local replacement="$3"
    local line
    local prefix
    local count
    local quoted_password
    local protected_prefixes=(
        "PA_DB_PASSWORD="
        "PA_TOKEN_PEPPER="
        "PA_IDENTITY_HMAC_KEY="
        "PA_IDENTITY_ENCRYPTION_KEY="
    )
    if [[ ! -f "$input_file" || -L "$input_file" || "$input_file" == "$output_file" ]]; then
        fail "无法安全重写环境配置。"
        return 1
    fi
    quoted_password="$(systemd_quote "$replacement")" || return 1
    for prefix in "${protected_prefixes[@]}"; do
        count="$(count_setting_lines "$input_file" "$prefix")"
        if [[ "$count" -ne 1 ]]; then
            fail "环境配置的密码或安全密钥结构异常，已拒绝覆盖。"
            return 1
        fi
    done
    : > "$output_file"
    chmod 0600 "$output_file"
    while IFS= read -r line || [[ -n "$line" ]]; do
        if [[ "$line" == "${protected_prefixes[0]}"* ]]; then
            printf 'PA_DB_PASSWORD=%s\n' "$quoted_password" >> "$output_file"
        else
            printf '%s\n' "$line" >> "$output_file"
        fi
    done < "$input_file"
}

read_database_password() {
    local first
    local second
    if [[ ! -t 0 ]]; then
        fail "数据库密码必须在交互式终端中输入。"
        return 1
    fi
    printf '请输入刚在 MySQL 中生成的应用账号新密码（不会回显）：' >&2
    IFS= read -r -s first
    printf '\n请再次输入数据库密码（不会回显）：' >&2
    IFS= read -r -s second
    printf '\n' >&2
    if [[ -z "$first" || "$first" != "$second" ]]; then
        unset first second
        fail "两次输入不一致或密码为空。"
        return 1
    fi
    if [[ "$first" == *$'\r'* || "$first" == *$'\n'* ]]; then
        unset first second
        fail "数据库密码不能包含回车或换行。"
        return 1
    fi
    printf '%s' "$first"
    unset first second
}

write_new_environment_file() {
    local output_file="$1"
    local db_password="$2"
    local token_pepper
    local identity_hmac_key
    local identity_encryption_key
    local quoted_password
    local quoted_token
    local quoted_hmac
    local quoted_encryption
    token_pepper="$(openssl rand -base64 48 | tr -d '\n')"
    identity_hmac_key="$(openssl rand -base64 48 | tr -d '\n')"
    identity_encryption_key="$(openssl rand -base64 32 | tr -d '\n')"
    if [[ -z "$token_pepper" || -z "$identity_hmac_key" || -z "$identity_encryption_key" ]]; then
        fail "生成安全密钥失败。"
        return 1
    fi
    quoted_password="$(systemd_quote "$db_password")" || return 1
    quoted_token="$(systemd_quote "$token_pepper")"
    quoted_hmac="$(systemd_quote "$identity_hmac_key")"
    quoted_encryption="$(systemd_quote "$identity_encryption_key")"
    {
        printf 'SPRING_PROFILES_ACTIVE=prod\n'
        printf 'PA_SERVER_PORT=18080\n'
        printf 'PA_DB_URL=%s\n' '"jdbc:mysql://127.0.0.1:3306/personnel_archive?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai"'
        printf 'PA_DB_USERNAME=personnel_archive\n'
        printf 'PA_DB_PASSWORD=%s\n' "$quoted_password"
        printf 'PA_TOKEN_PEPPER=%s\n' "$quoted_token"
        printf 'PA_IDENTITY_HMAC_KEY=%s\n' "$quoted_hmac"
        printf 'PA_IDENTITY_ENCRYPTION_KEY=%s\n' "$quoted_encryption"
        printf 'PA_LOG_FILE=/opt/personnel-archive-service/log/personnel-archive-service.log\n'
        printf 'PA_LOG_ARCHIVE_PATTERN=/opt/personnel-archive-service/log/personnel-archive-service.%%d{yyyy-MM-dd}.%%i.log.gz\n'
        printf 'PA_LOG_MAX_FILE_SIZE=20MB\n'
        printf 'PA_LOG_MAX_HISTORY=30\n'
        printf 'PA_LOG_TOTAL_SIZE_CAP=1GB\n'
        printf 'JAVA_TOOL_OPTIONS=%s\n' '"-Duser.timezone=Asia/Shanghai"'
    } > "$output_file"
    chmod 0600 "$output_file"
    unset db_password token_pepper identity_hmac_key identity_encryption_key
    unset quoted_password quoted_token quoted_hmac quoted_encryption
}

install_or_reconfigure_environment() {
    local db_password
    local backup_file
    reject_symlink "$ENV_FILE"
    if [[ -f "$ENV_FILE" ]]; then
        validate_environment_file "$ENV_FILE"
        if [[ "$RECONFIGURE" == false ]]; then
            chown root:root "$ENV_FILE"
            chmod 0600 "$ENV_FILE"
            printf '已保留现有数据库密码和三项安全密钥。\n'
            return 0
        fi
        db_password="$(read_database_password)" || return 1
        backup_file="${BACKUP_DIR}/personnel-archive-service.$(date +%Y%m%d-%H%M%S).$$.env"
        install -o root -g root -m 0600 "$ENV_FILE" "$backup_file"
        SECRET_TEMP_FILE="$(mktemp "${CONFIG_DIR}/.secret.XXXXXX")"
        rewrite_environment_password "$ENV_FILE" "$SECRET_TEMP_FILE" "$db_password"
        validate_environment_file "$SECRET_TEMP_FILE"
        install -o root -g root -m 0600 "$SECRET_TEMP_FILE" "$ENV_FILE"
        rm -f -- "$SECRET_TEMP_FILE"
        SECRET_TEMP_FILE=""
        unset db_password
        printf '数据库密码已更新；三项安全密钥及其他配置保持不变。旧配置备份仅 root 可读。\n'
        return 0
    fi
    db_password="$(read_database_password)" || return 1
    SECRET_TEMP_FILE="$(mktemp "${CONFIG_DIR}/.secret.XXXXXX")"
    write_new_environment_file "$SECRET_TEMP_FILE" "$db_password"
    validate_environment_file "$SECRET_TEMP_FILE"
    install -o root -g root -m 0600 "$SECRET_TEMP_FILE" "$ENV_FILE"
    rm -f -- "$SECRET_TEMP_FILE"
    SECRET_TEMP_FILE=""
    unset db_password
    printf '已创建受保护配置并在本机生成三项安全密钥。\n'
}

render_service_unit() {
    local output_file="$1"
    local line
    local rendered
    if [[ "$JAVA_BIN" != /* || "$JAVA_BIN" == *[[:space:]]* ]]; then
        fail "Java 可执行文件路径不安全。"
        return 1
    fi
    : > "$output_file"
    while IFS= read -r line || [[ -n "$line" ]]; do
        rendered="${line//@JAVA_BIN@/$JAVA_BIN}"
        printf '%s\n' "$rendered" >> "$output_file"
    done < "$UNIT_TEMPLATE"
    if grep -q '@JAVA_BIN@' "$output_file"; then
        fail "systemd 服务模板渲染失败。"
        return 1
    fi
}

port_is_listening() {
    ss -ltn | awk 'NR > 1 {print $4}' | grep -Eq '(^|:|\])18080$'
}

verify_package() {
    require_command sha256sum
    if [[ ! -f "$CHECKSUM_FILE" || -L "$CHECKSUM_FILE" ]]; then
        fail "缺少 SHA256SUMS。"
        return 1
    fi
    if [[ ! -f "$JAR_SOURCE" || -L "$JAR_SOURCE" || ! -f "$UNIT_TEMPLATE" || -L "$UNIT_TEMPLATE" ]]; then
        fail "离线包缺少 JAR 或 systemd 模板。"
        return 1
    fi
    (cd "$SCRIPT_DIR" && sha256sum --check SHA256SUMS)
}

verify_runtime() {
    local available_kb
    local command_name
    for command_name in java openssl tr systemctl getent groupadd useradd id install readlink \
        awk grep ss df mkdir chmod chown; do
        require_command "$command_name"
    done
    if [[ ! -d /run/systemd/system ]]; then
        fail "当前系统不是正在运行的 systemd 环境。"
        return 1
    fi
    JAVA_BIN="$(readlink -f "$(command -v java)")"
    JAVA_MAJOR="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | \
        awk -F= '/^[[:space:]]*java\.specification\.version[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}')"
    if [[ "$JAVA_MAJOR" != "21" ]]; then
        fail "需要 JDK/JRE 21，当前 java.specification.version 不是 21。"
        return 1
    fi
    available_kb="$(df -Pk "$INSTALL_DIR" | awk 'NR == 2 {print $4}')"
    if [[ ! "$available_kb" =~ ^[0-9]+$ || "$available_kb" -lt "$MINIMUM_FREE_KB" ]]; then
        fail "/opt 所在文件系统至少需要 2 GiB 可用空间。"
        return 1
    fi
}

create_service_account_and_directories() {
    local nologin_shell
    if ! getent group "$APP_GROUP" >/dev/null; then
        groupadd --system "$APP_GROUP"
    fi
    if ! getent passwd "$APP_USER" >/dev/null; then
        nologin_shell="$(command -v nologin || true)"
        if [[ -z "$nologin_shell" ]]; then
            nologin_shell="/usr/sbin/nologin"
        fi
        useradd --system --gid "$APP_GROUP" --home-dir "$INSTALL_DIR" --shell "$nologin_shell" "$APP_USER"
    fi
    if [[ "$(id -gn "$APP_USER")" != "$APP_GROUP" ]]; then
        fail "现有服务用户的主组不是 personnel-archive，已拒绝继续。"
        return 1
    fi
    reject_symlink "$CONFIG_DIR"
    reject_symlink "$BACKUP_DIR"
    install -d -o root -g "$APP_GROUP" -m 0750 "$INSTALL_DIR"
    install -d -o "$APP_USER" -g "$APP_GROUP" -m 0750 "$LOG_DIR"
    install -d -o root -g root -m 0700 "$CONFIG_DIR"
    install -d -o root -g root -m 0700 "$BACKUP_DIR"
    chown root:"$APP_GROUP" "$INSTALL_LOG"
    chmod 0640 "$INSTALL_LOG"
}

install_jar() {
    if systemctl is-active --quiet "$SERVICE_NAME"; then
        systemctl stop "$SERVICE_NAME"
    fi
    reject_symlink "$JAR_TARGET"
    install -o root -g "$APP_GROUP" -m 0640 "$JAR_SOURCE" "$JAR_TARGET"
}

install_service_unit() {
    local unit_temp
    reject_symlink "$SERVICE_FILE"
    unit_temp="$(mktemp "${CONFIG_DIR}/.service.XXXXXX")"
    render_service_unit "$unit_temp"
    install -o root -g root -m 0644 "$unit_temp" "$SERVICE_FILE"
    rm -f -- "$unit_temp"
}

fix_log_permissions() {
    reject_symlink "$SERVICE_LOG"
    if [[ ! -e "$SERVICE_LOG" ]]; then
        install -o "$APP_USER" -g "$APP_GROUP" -m 0640 /dev/null "$SERVICE_LOG"
    else
        chown "$APP_USER":"$APP_GROUP" "$SERVICE_LOG"
        chmod 0640 "$SERVICE_LOG"
    fi
    chown root:"$APP_GROUP" "$INSTALL_LOG"
    chmod 0640 "$INSTALL_LOG"
}

start_and_check_service() {
    local attempt
    if port_is_listening; then
        fail "端口 18080 已被其他进程监听。"
        return 1
    fi
    systemctl enable "$SERVICE_NAME"
    systemctl start "$SERVICE_NAME"
    for attempt in {1..30}; do
        if systemctl is-active --quiet "$SERVICE_NAME" && port_is_listening; then
            printf '服务已启动，18080 端口已监听。\n'
            return 0
        fi
        sleep 2
    done
    fail "服务未在 60 秒内就绪。请查看文件日志和 journal，安装器不会输出配置内容。"
    return 1
}

main() {
    parse_options "$@"
    require_root
    umask 077
    prepare_install_log
    trap 'on_exit $?' EXIT

    printf '[步骤 1/10] 校验离线包完整性\n'
    verify_package

    printf '[步骤 2/10] 检查 Java 21、systemd、必需命令和磁盘空间\n'
    verify_runtime

    printf '[步骤 3/10] 创建专用系统用户和受控目录\n'
    create_service_account_and_directories

    printf '[步骤 4/10] 停止旧服务并安装已校验 JAR\n'
    install_jar

    printf '[步骤 5/10] 创建或核对受保护的生产配置\n'
    install_or_reconfigure_environment

    printf '[步骤 6/10] 安装 systemd 服务单元\n'
    install_service_unit

    printf '[步骤 7/10] 固定 JAR 同级 log 目录和权限\n'
    fix_log_permissions

    printf '[步骤 8/10] 重新加载 systemd 配置\n'
    systemctl daemon-reload

    printf '[步骤 9/10] 启用并启动人员档案数据服务\n'
    if [[ "$NO_START" == true ]]; then
        printf '已按 --no-start 跳过本次启用和启动。\n'
    else
        start_and_check_service
    fi

    printf '[步骤 10/10] 输出验证结果\n'
    if [[ "$NO_START" == true ]]; then
        printf '文件已安装。核对完成后运行：systemctl enable --now %s\n' "$SERVICE_NAME"
    else
        systemctl is-active --quiet "$SERVICE_NAME"
        printf '服务状态：active\n'
    fi
    printf 'JAR：%s\n' "$JAR_TARGET"
    printf '应用日志：%s\n' "$SERVICE_LOG"
    printf '安装日志：%s\n' "$INSTALL_LOG"
    printf '环境配置：%s（仅 root 可读，切勿发送或复制到普通文本文件）\n' "$ENV_FILE"
    printf '安装完成。\n'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
