#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SERVICE_NAME="personnel-archive-service.service"
APP_USER="personnel-archive"
APP_GROUP="personnel-archive"
DATA_ROOT="/data"
INSTALL_DIR="${DATA_ROOT}/personnel-archive-service"
LOG_DIR="${INSTALL_DIR}/log"
JAR_TARGET="${INSTALL_DIR}/personnel-archive-service.jar"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}"
JAR_SOURCE="${SCRIPT_DIR}/personnel-archive-service.jar"
UNIT_TEMPLATE="${SCRIPT_DIR}/service/personnel-archive-service.service.template"
CHECKSUM_FILE="${SCRIPT_DIR}/SHA256SUMS"
SERVICE_LOG_PATTERN="${LOG_DIR}/log.YYYY-MM-DD.log"
MINIMUM_FREE_KB=2097152

NO_START=false
JAVA_BIN=""
JAVA_MAJOR=""
INSTALL_LOG=""

fail() {
    printf '错误：%s\n' "$1" >&2
    return 1
}

usage() {
    printf '%s\n' \
        '用法：sudo ./install.sh [--no-start]' \
        '  --no-start     安装文件但不在本次执行中启用或启动服务' \
        '  --help         显示本说明'
}

parse_options() {
    while (($# > 0)); do
        case "$1" in
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
    if [[ ! -d "$DATA_ROOT" ]]; then
        fail "部署根目录 /data 不存在，请先在目标电脑创建并挂载 /data。"
        return 1
    fi
    reject_symlink "$DATA_ROOT"
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
    if [[ -n "${INSTALL_LOG:-}" && -f "$INSTALL_LOG" ]]; then
        if getent group "$APP_GROUP" >/dev/null 2>&1; then
            chown root:"$APP_GROUP" "$INSTALL_LOG" 2>/dev/null || true
            chmod 0640 "$INSTALL_LOG" 2>/dev/null || true
        else
            chmod 0600 "$INSTALL_LOG" 2>/dev/null || true
        fi
    fi
    if [[ "$status" -ne 0 ]]; then
        printf '安装未完成。请根据上方固定错误说明排查。\n' >&2
        if [[ -n "${INSTALL_LOG:-}" ]]; then
            printf '安装日志：%s\n' "$INSTALL_LOG" >&2
        fi
    fi
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
    for command_name in java systemctl getent groupadd useradd id install readlink \
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
    available_kb="$(df -Pk "$DATA_ROOT" | awk 'NR == 2 {print $4}')"
    if [[ ! "$available_kb" =~ ^[0-9]+$ || "$available_kb" -lt "$MINIMUM_FREE_KB" ]]; then
        fail "/data 所在文件系统至少需要 2 GiB 可用空间。"
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
    install -d -o root -g "$APP_GROUP" -m 0750 "$INSTALL_DIR"
    install -d -o "$APP_USER" -g "$APP_GROUP" -m 0750 "$LOG_DIR"
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
    unit_temp="$(mktemp "${INSTALL_DIR}/.service.XXXXXX")"
    render_service_unit "$unit_temp"
    install -o root -g root -m 0644 "$unit_temp" "$SERVICE_FILE"
    rm -f -- "$unit_temp"
}

fix_log_permissions() {
    reject_symlink "$LOG_DIR"
    chown "$APP_USER":"$APP_GROUP" "$LOG_DIR"
    chmod 0750 "$LOG_DIR"
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

    printf '[步骤 1/9] 校验离线包完整性\n'
    verify_package

    printf '[步骤 2/9] 检查 Java 21、systemd、必需命令和磁盘空间\n'
    verify_runtime

    printf '[步骤 3/9] 创建专用系统用户和受控目录\n'
    create_service_account_and_directories

    printf '[步骤 4/9] 停止旧服务并安装已校验 JAR\n'
    install_jar

    printf '[步骤 5/9] 安装自包含 prod 的 systemd 服务单元\n'
    install_service_unit

    printf '[步骤 6/9] 固定 JAR 同级 log 目录和权限\n'
    fix_log_permissions

    printf '[步骤 7/9] 重新加载 systemd 配置\n'
    systemctl daemon-reload

    printf '[步骤 8/9] 启用并启动人员档案数据服务\n'
    if [[ "$NO_START" == true ]]; then
        printf '已按 --no-start 跳过本次启用和启动。\n'
    else
        start_and_check_service
    fi

    printf '[步骤 9/9] 输出验证结果\n'
    if [[ "$NO_START" == true ]]; then
        printf '文件已安装。核对完成后运行：systemctl enable --now %s\n' "$SERVICE_NAME"
    else
        systemctl is-active --quiet "$SERVICE_NAME"
        printf '服务状态：active\n'
    fi
    printf 'JAR：%s\n' "$JAR_TARGET"
    printf '应用日志：%s\n' "$SERVICE_LOG_PATTERN"
    printf '安装日志：%s\n' "$INSTALL_LOG"
    printf 'prod 数据库与三项安全配置：已固定包含在 JAR 中，不需要外部环境文件。\n'
    printf '安装完成。\n'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
