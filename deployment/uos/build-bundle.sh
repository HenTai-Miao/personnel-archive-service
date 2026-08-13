#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SOURCE_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
STAGING_PREFIX="personnel-archive-uos-bundle"
PRODUCT_PREFIX="人员档案数据服务-统信UOS离线部署"
STAGING_ROOT=""

fail() {
    printf '错误：%s\n' "$1" >&2
    return 1
}

usage() {
    printf '用法：%s <已验证的可执行JAR> <输出目录> [YYYYMMDD]\n' "$0" >&2
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        fail "缺少制作离线包所需命令：$1"
        return 1
    fi
}

checksum_value() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
    else
        shasum -a 256 "$file" | awk '{print $1}'
    fi
}

write_checksum_line() {
    local base_directory="$1"
    local relative_path="$2"
    local digest
    digest="$(checksum_value "${base_directory}/${relative_path#./}")"
    printf '%s  %s\n' "$digest" "$relative_path"
}

cleanup() {
    local status="$1"
    if [[ -n "${STAGING_ROOT:-}" ]]; then
        case "$STAGING_ROOT" in
            "${TMPDIR:-/tmp}/${STAGING_PREFIX}."*)
                rm -rf -- "$STAGING_ROOT"
                ;;
            *)
                printf '警告：临时目录不符合安全前缀，未自动清理。\n' >&2
                ;;
        esac
    fi
    return "$status"
}

validate_executable_jar() {
    local jar_file="$1"
    local entries
    local required_entry
    local manifest
    entries="$(jar tf "$jar_file")"
    for required_entry in \
        "META-INF/MANIFEST.MF" \
        "org/springframework/boot/loader/launch/JarLauncher.class" \
        "BOOT-INF/classes/application-prod.yml" \
        "BOOT-INF/classes/db/migration/V1__initial_schema.sql" \
        "BOOT-INF/classes/db/migration/V14__canonicalize_open_ended_range_text.class" \
        "BOOT-INF/classes/excel/personnel-archive-template-v1.xlsx"; do
        if ! grep -Fxq "$required_entry" <<< "$entries"; then
            fail "JAR 缺少必需内容，拒绝制作离线包。"
            return 1
        fi
    done
    manifest="$(unzip -p "$jar_file" META-INF/MANIFEST.MF)"
    if ! grep -Eq '^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\r?$' <<< "$manifest" || \
        ! grep -Eq '^Start-Class: com\.company\.personnelarchive\.PersonnelArchiveApplication\r?$' <<< "$manifest"; then
        fail "JAR 不是预期的 Spring Boot 可执行包。"
        return 1
    fi
}

sanitize_production_jar() {
    local jar_file="$1"
    local development_profile="BOOT-INF/classes/application-dev.yml"
    if jar tf "$jar_file" | grep -Fxq "$development_profile"; then
        zip -dq "$jar_file" "$development_profile"
    fi
    if jar tf "$jar_file" | grep -Fxq "$development_profile"; then
        fail "生产 JAR 仍包含开发 profile，已拒绝打包。"
        return 1
    fi
    validate_executable_jar "$jar_file"
}

copy_allowlisted_payload() {
    local bundle_directory="$1"
    install -d -m 0755 \
        "$bundle_directory/service" \
        "$bundle_directory/mysql" \
        "$bundle_directory/docs"
    install -m 0644 "$JAR_FILE" "$bundle_directory/personnel-archive-service.jar"
    install -m 0755 "$SCRIPT_DIR/install.sh" "$bundle_directory/install.sh"
    install -m 0755 "$SCRIPT_DIR/start-prod.sh" "$bundle_directory/start-prod.sh"
    install -m 0644 \
        "$SCRIPT_DIR/service/personnel-archive-service.service.template" \
        "$bundle_directory/service/personnel-archive-service.service.template"
    install -m 0644 \
        "$SCRIPT_DIR/mysql/01-确认应用账号授权.sql" \
        "$bundle_directory/mysql/01-确认应用账号授权.sql"
    install -m 0644 "$SCRIPT_DIR/docs/01-安装前检查.txt" "$bundle_directory/docs/01-安装前检查.txt"
    install -m 0644 "$SCRIPT_DIR/docs/02-数据库授权.txt" "$bundle_directory/docs/02-数据库授权.txt"
    install -m 0644 "$SCRIPT_DIR/docs/03-离线安装与验证.txt" "$bundle_directory/docs/03-离线安装与验证.txt"
    install -m 0644 "$SCRIPT_DIR/docs/04-日常运维与日志排查.txt" "$bundle_directory/docs/04-日常运维与日志排查.txt"
    install -m 0644 "$SCRIPT_DIR/docs/05-故障恢复.txt" "$bundle_directory/docs/05-故障恢复.txt"
}

write_build_info() {
    local output_file="$1"
    local jar_digest="$2"
    local commit="unknown"
    if command -v git >/dev/null 2>&1; then
        commit="$(git -C "$SOURCE_ROOT" rev-parse HEAD 2>/dev/null || printf 'unknown')"
    fi
    {
        printf 'Product=人员档案数据服务\n'
        printf 'ServiceArtifact=personnel-archive-service.jar\n'
        printf 'ApplicationVersion=0.1.0-SNAPSHOT\n'
        printf 'RequiredJava=21\n'
        printf 'ProductionDatabase=MySQL 8 on 127.0.0.1:3306/personnel_archive\n'
        printf 'FlywayMigrations=V1-V14\n'
        printf 'BundleDate=%s\n' "$BUNDLE_DATE"
        printf 'SourceCommit=%s\n' "$commit"
        printf 'JarSHA256=%s\n' "$jar_digest"
        printf 'ContainsFixedProductionDatabaseCredential=yes\n'
        printf 'ContainsFixedProductionSecurityKeys=yes\n'
        printf 'ContainsPersonnelData=no\n'
    } > "$output_file"
}

write_inner_manifest() {
    local bundle_directory="$1"
    local manifest_file="$bundle_directory/SHA256SUMS"
    local manifest_entries=(
        "./personnel-archive-service.jar"
        "./install.sh"
        "./start-prod.sh"
        "./service/personnel-archive-service.service.template"
        "./mysql/01-确认应用账号授权.sql"
        "./docs/01-安装前检查.txt"
        "./docs/02-数据库授权.txt"
        "./docs/03-离线安装与验证.txt"
        "./docs/04-日常运维与日志排查.txt"
        "./docs/05-故障恢复.txt"
        "./BUILD-INFO.txt"
    )
    : > "$manifest_file"
    for relative_path in "${manifest_entries[@]}"; do
        write_checksum_line "$bundle_directory" "$relative_path" >> "$manifest_file"
    done
}

select_collision_safe_name() {
    local suffix
    local candidate
    for suffix in "" {1..9999}; do
        if [[ -z "$suffix" ]]; then
            candidate="${PRODUCT_PREFIX}-${BUNDLE_DATE}"
        else
            candidate="${PRODUCT_PREFIX}-${BUNDLE_DATE}-${suffix}"
        fi
        if [[ ! -e "${OUTPUT_DIRECTORY}/${candidate}" && \
              ! -e "${OUTPUT_DIRECTORY}/${candidate}.tar.gz" && \
              ! -e "${OUTPUT_DIRECTORY}/${candidate}.tar.gz.sha256" ]]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    fail "输出目录中的防覆盖名称已用尽。"
    return 1
}

main() {
    local selected_name
    local staging_bundle
    local staging_archive
    local staging_archive_checksum
    local jar_digest
    local final_directory
    local final_archive
    local final_archive_checksum
    local archive_digest

    if [[ "$#" -lt 2 || "$#" -gt 3 ]]; then
        usage
        return 1
    fi
    require_command jar
    require_command unzip
    require_command zip
    require_command tar
    require_command awk
    require_command grep
    require_command install
    if ! command -v sha256sum >/dev/null 2>&1; then
        require_command shasum
    fi

    if [[ ! -f "$1" || -L "$1" ]]; then
        fail "JAR 必须是已验证的普通文件。"
        return 1
    fi
    if [[ ! -d "$2" || -L "$2" ]]; then
        fail "输出目录必须已经存在且不能是符号链接。"
        return 1
    fi
    JAR_FILE="$(cd -- "$(dirname -- "$1")" && pwd -P)/$(basename -- "$1")"
    OUTPUT_DIRECTORY="$(cd -- "$2" && pwd -P)"
    BUNDLE_DATE="${3:-$(date +%Y%m%d)}"
    if [[ ! "$BUNDLE_DATE" =~ ^[0-9]{8}$ ]]; then
        fail "日期必须是 YYYYMMDD。"
        return 1
    fi

    validate_executable_jar "$JAR_FILE"
    selected_name="$(select_collision_safe_name)"
    STAGING_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/${STAGING_PREFIX}.XXXXXX")"
    trap 'cleanup $?' EXIT
    staging_bundle="${STAGING_ROOT}/${selected_name}"
    staging_archive="${STAGING_ROOT}/${selected_name}.tar.gz"
    staging_archive_checksum="${STAGING_ROOT}/${selected_name}.tar.gz.sha256"

    copy_allowlisted_payload "$staging_bundle"
    sanitize_production_jar "$staging_bundle/personnel-archive-service.jar"
    jar_digest="$(checksum_value "$staging_bundle/personnel-archive-service.jar")"
    write_build_info "$staging_bundle/BUILD-INFO.txt" "$jar_digest"
    write_inner_manifest "$staging_bundle"

    (
        cd "$staging_bundle"
        if command -v sha256sum >/dev/null 2>&1; then
            sha256sum --check SHA256SUMS >/dev/null
        else
            shasum -a 256 --check SHA256SUMS >/dev/null
        fi
    )
    COPYFILE_DISABLE=1 tar -C "$STAGING_ROOT" -czf "$staging_archive" "$selected_name"
    tar -tzf "$staging_archive" >/dev/null
    archive_digest="$(checksum_value "$staging_archive")"
    printf '%s  %s\n' "$archive_digest" "${selected_name}.tar.gz" > "$staging_archive_checksum"

    final_directory="${OUTPUT_DIRECTORY}/${selected_name}"
    final_archive="${OUTPUT_DIRECTORY}/${selected_name}.tar.gz"
    final_archive_checksum="${OUTPUT_DIRECTORY}/${selected_name}.tar.gz.sha256"
    mv -- "$staging_bundle" "$final_directory"
    mv -- "$staging_archive" "$final_archive"
    mv -- "$staging_archive_checksum" "$final_archive_checksum"

    tar -tzf "$final_archive" >/dev/null
    if command -v sha256sum >/dev/null 2>&1; then
        (cd "$OUTPUT_DIRECTORY" && sha256sum --check "${selected_name}.tar.gz.sha256" >/dev/null)
    else
        (cd "$OUTPUT_DIRECTORY" && shasum -a 256 --check "${selected_name}.tar.gz.sha256" >/dev/null)
    fi

    printf '离线目录：%s\n' "$final_directory"
    printf '压缩包：%s\n' "$final_archive"
    printf '压缩包校验：%s\n' "$final_archive_checksum"
}

main "$@"
