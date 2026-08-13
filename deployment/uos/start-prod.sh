#!/usr/bin/env bash
set -Eeuo pipefail

SERVICE_NAME="personnel-archive-service.service"
APP_USER="personnel-archive"
APP_GROUP="personnel-archive"
DATA_ROOT="/data"
INSTALL_DIR="${DATA_ROOT}/personnel-archive-service"
UNIT_FILE="/etc/systemd/system/${SERVICE_NAME}"
JAR_FILE="/data/personnel-archive-service/personnel-archive-service.jar"
LOG_DIR="/data/personnel-archive-service/log"
LOG_FILE="${LOG_DIR}/log.$(date +%F).log"

JAVA_BIN=""
JAVA_MAJOR=""

fail() {
    printf '错误：%s\n' "$1" >&2
    return 1
}

require_root() {
    if [[ "${EUID}" -ne 0 ]]; then
        fail "必须使用 root 权限运行，例如 sudo ./启动prod环境.sh。"
        return 1
    fi
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        fail "缺少启动检查所需命令：$1。"
        return 1
    fi
}

reject_symlink() {
    if [[ -L "$1" ]]; then
        fail "拒绝使用符号链接路径：$1"
        return 1
    fi
}

resolve_java_21() {
    JAVA_BIN="$(readlink -f "$(command -v java)")"
    if [[ "$JAVA_BIN" != /* || "$JAVA_BIN" == *[[:space:]]* ]]; then
        fail "Java 可执行文件路径不安全。"
        return 1
    fi
    JAVA_MAJOR="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | \
        awk -F= '/^[[:space:]]*java\.specification\.version[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}')"
    if [[ "$JAVA_MAJOR" != "21" ]]; then
        fail "需要 JDK/JRE 21，当前 java.specification.version 不是 21。"
        return 1
    fi
}

prepare_service_layout() {
    local nologin_shell
    if [[ ! -d "$DATA_ROOT" ]]; then
        fail "部署根目录 /data 不存在。"
        return 1
    fi
    reject_symlink "$DATA_ROOT"
    reject_symlink "$INSTALL_DIR"
    if [[ ! -d "$INSTALL_DIR" ]]; then
        fail "请先创建 /data/personnel-archive-service 并放入 JAR 和启动脚本。"
        return 1
    fi
    reject_symlink "$JAR_FILE"
    if [[ ! -f "$JAR_FILE" ]]; then
        fail "缺少 /data/personnel-archive-service/personnel-archive-service.jar。"
        return 1
    fi
    reject_symlink "$LOG_DIR"
    reject_symlink "$UNIT_FILE"

    if ! getent group "$APP_GROUP" >/dev/null; then
        groupadd --system "$APP_GROUP"
    fi
    if ! getent passwd "$APP_USER" >/dev/null; then
        nologin_shell="$(command -v nologin || true)"
        if [[ -z "$nologin_shell" ]]; then
            nologin_shell="/usr/sbin/nologin"
        fi
        useradd --system --gid "$APP_GROUP" --home-dir "$INSTALL_DIR" \
            --shell "$nologin_shell" "$APP_USER"
    fi
    if [[ "$(id -gn "$APP_USER")" != "$APP_GROUP" ]]; then
        fail "现有服务用户的主组不是 personnel-archive，已拒绝继续。"
        return 1
    fi

    install -d -o root -g "$APP_GROUP" -m 0750 "$INSTALL_DIR"
    install -d -o "$APP_USER" -g "$APP_GROUP" -m 0750 "$LOG_DIR"
    chown root:"$APP_GROUP" "$JAR_FILE"
    chmod 0640 "$JAR_FILE"
}

render_service_unit() {
    local output_file="$1"
    local java_binary="$2"
    if [[ "$java_binary" != /* || "$java_binary" == *[[:space:]]* ]]; then
        fail "Java 可执行文件路径不安全。"
        return 1
    fi
    {
        printf '%s\n' \
            '[Unit]' \
            'Description=人员档案数据服务' \
            'Documentation=file:/data/personnel-archive-service/log/' \
            'Wants=network-online.target' \
            'After=network-online.target mysql.service mysqld.service' \
            'ConditionPathIsReadWrite=/data/personnel-archive-service/log' \
            'ConditionPathExists=/data/personnel-archive-service/personnel-archive-service.jar' \
            '' \
            '[Service]' \
            'Type=simple' \
            'User=personnel-archive' \
            'Group=personnel-archive' \
            'WorkingDirectory=/data/personnel-archive-service' \
            'Environment=SPRING_PROFILES_ACTIVE=prod' \
            'Environment=JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai'
        printf 'ExecStart=%s -jar /data/personnel-archive-service/personnel-archive-service.jar\n' \
            "$java_binary"
        printf '%s\n' \
            'SuccessExitStatus=143' \
            'Restart=on-failure' \
            'RestartSec=5s' \
            'TimeoutStartSec=90s' \
            'TimeoutStopSec=30s' \
            'KillSignal=SIGTERM' \
            'UMask=0027' \
            'NoNewPrivileges=true' \
            'PrivateTmp=true' \
            'PrivateDevices=true' \
            'ProtectHome=true' \
            'ProtectSystem=strict' \
            'ProtectKernelTunables=true' \
            'ProtectKernelModules=true' \
            'ProtectControlGroups=true' \
            'RestrictSUIDSGID=true' \
            'LockPersonality=true' \
            'CapabilityBoundingSet=' \
            'ReadWritePaths=/data/personnel-archive-service/log' \
            'StandardOutput=journal' \
            'StandardError=journal' \
            'SyslogIdentifier=personnel-archive-service' \
            '' \
            '[Install]' \
            'WantedBy=multi-user.target'
    } > "$output_file"
}

install_service_unit() {
    local unit_temp
    unit_temp="$(mktemp "${INSTALL_DIR}/.service.XXXXXX")"
    if ! render_service_unit "$unit_temp" "$JAVA_BIN"; then
        rm -f -- "$unit_temp"
        return 1
    fi
    install -o root -g root -m 0644 "$unit_temp" "$UNIT_FILE"
    rm -f -- "$unit_temp"
    systemctl daemon-reload
}

port_is_listening() {
    ss -ltn | awk 'NR > 1 {print $4}' | grep -Eq '(^|:|\])18080$'
}

show_diagnostics() {
    printf '\n===== systemd 状态 =====\n' >&2
    systemctl status "$SERVICE_NAME" --no-pager -l >&2 || true
    printf '\n===== 最近 100 行 journal =====\n' >&2
    journalctl -u "$SERVICE_NAME" -n 100 --no-pager >&2 || true
    printf '\n===== 最近 100 行应用日志 =====\n' >&2
    if [[ -f "$LOG_FILE" && ! -L "$LOG_FILE" ]]; then
        tail -n 100 "$LOG_FILE" >&2 || true
    else
        printf '应用日志尚未生成：%s\n' "$LOG_FILE" >&2
    fi
}

start_or_restart_service() {
    if port_is_listening && ! systemctl is-active --quiet "$SERVICE_NAME"; then
        fail "端口 18080 已被其他进程监听。"
        return 1
    fi
    if systemctl is-active --quiet "$SERVICE_NAME"; then
        printf '\n正在重启 prod 服务以加载指定 JAR……\n'
        systemctl restart "$SERVICE_NAME"
    else
        printf '\n正在启用并启动 prod 服务……\n'
        systemctl enable --now "$SERVICE_NAME"
    fi
}

main() {
    local attempt
    local command_name

    require_root
    umask 077
    for command_name in systemctl journalctl getent groupadd useradd id install readlink \
        awk grep ss df du tail date mktemp rm chmod chown java; do
        require_command "$command_name"
    done
    if [[ ! -d /run/systemd/system ]]; then
        fail "当前系统不是正在运行的 systemd 环境。"
        return 1
    fi

    resolve_java_21
    prepare_service_layout
    install_service_unit

    printf 'Java 版本：\n'
    "$JAVA_BIN" -version 2>&1
    printf '\n/data 磁盘空间：\n'
    df -h /data
    printf '\n日志目录占用：\n'
    du -sh "$LOG_DIR"

    start_or_restart_service

    for attempt in {1..30}; do
        if systemctl is-active --quiet "$SERVICE_NAME" && port_is_listening; then
            printf '\nprod 服务启动成功。\n'
            printf '状态：active\n'
            printf '端口：18080\n'
            printf 'JAR：%s\n' "$JAR_FILE"
            printf '日志：%s\n' "$LOG_FILE"
            printf '持续查看文件日志：sudo tail -F %s\n' "$LOG_FILE"
            printf '持续查看 systemd 日志：sudo journalctl -fu %s\n' "$SERVICE_NAME"
            exit 0
        fi
        sleep 2
    done

    printf '错误：服务未在 60 秒内达到 active 且监听 18080。\n' >&2
    show_diagnostics
    exit 1
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
