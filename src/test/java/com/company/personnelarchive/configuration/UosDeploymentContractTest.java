package com.company.personnelarchive.configuration;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

class UosDeploymentContractTest {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path INSTALLER = ROOT.resolve("deployment/uos/install.sh");
    private static final Path STARTER = ROOT.resolve("deployment/uos/start-prod.sh");
    private static final Path BUILDER = ROOT.resolve("deployment/uos/build-bundle.sh");
    private static final Path UNIT = ROOT.resolve(
        "deployment/uos/service/personnel-archive-service.service.template");
    private static final Path SQL = ROOT.resolve("deployment/uos/mysql/01-确认应用账号授权.sql");
    private static final List<Path> DOCS = List.of(
        ROOT.resolve("deployment/uos/docs/01-安装前检查.txt"),
        ROOT.resolve("deployment/uos/docs/02-数据库授权.txt"),
        ROOT.resolve("deployment/uos/docs/03-离线安装与验证.txt"),
        ROOT.resolve("deployment/uos/docs/04-日常运维与日志排查.txt"),
        ROOT.resolve("deployment/uos/docs/05-故障恢复.txt")
    );
    private static final List<Path> PAYLOAD = payload();

    @Test
    void sourcePayloadExistsAndScriptsUseStrictNonTracingBash() throws IOException {
        assertThat(PAYLOAD).allSatisfy(path -> assertThat(path).isRegularFile());
        assertThat(INSTALLER).isExecutable();
        assertThat(STARTER).isExecutable();
        assertThat(BUILDER).isExecutable();
        for (Path script : List.of(INSTALLER, STARTER, BUILDER)) {
            String text = Files.readString(script, StandardCharsets.UTF_8);
            assertThat(text).startsWith("#!/usr/bin/env bash\n").contains("set -Eeuo pipefail");
            assertThat(text).doesNotContain("set -x", "set -o xtrace");
        }
    }

    @Test
    void payloadHasNoNetworkCommandOrLiteralProductionSecret() throws IOException {
        Pattern networkCommand = Pattern.compile(
            "(?m)(?:^|[;&|]\\s*)(?:sudo\\s+)?(?:apt|apt-get|yum|dnf|curl|wget|nc|ncat|ssh|scp|rsync)(?:\\s|$)");
        Pattern literalSecret = Pattern.compile(
            "(?m)^\\s*(?:export\\s+)?(?:PA_DB_PASSWORD|PA_TOKEN_PEPPER|PA_IDENTITY_HMAC_KEY|"
                + "PA_IDENTITY_ENCRYPTION_KEY)=(?!\\s*(?:$|\\$|<))\\S.*$");
        for (Path path : PAYLOAD) {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            assertThat(networkCommand.matcher(text).find()).as(path.toString()).isFalse();
            assertThat(literalSecret.matcher(text).find()).as(path.toString()).isFalse();
        }
    }

    @Test
    void installerContainsTheRootAndBoundedReadinessContracts() throws IOException {
        String text = Files.readString(INSTALLER, StandardCharsets.UTF_8);
        assertThat(text)
            .contains("EUID", "sha256sum --check SHA256SUMS", "JAVA_MAJOR", "21",
                "getent group", "useradd --system", "DATA_ROOT=\"/data\"",
                "INSTALL_DIR=\"${DATA_ROOT}/personnel-archive-service\"",
                "umask 077", "mktemp",
                "--no-start", "systemctl daemon-reload", "systemctl is-active --quiet",
                "ss -ltn", "for attempt in",
                "SERVICE_LOG_PATTERN=\"${LOG_DIR}/log.YYYY-MM-DD.log\"",
                "[[ \"${BASH_SOURCE[0]}\" == \"$0\" ]]", "main \"$@\"")
            .doesNotContain("set -x", "set -o xtrace", "eval ", "envsubst", "source \"$ENV_FILE\"",
                ". \"$ENV_FILE\"", "cat \"$ENV_FILE\"", "/opt/personnel-archive-service",
                "IFS= read -r -s", "--reconfigure", "read_database_password",
                "rewrite_environment_password", "PA_DB_PASSWORD=", "PA_DB_URL=", "PA_DB_USERNAME=",
                "PA_LOG_FILE=", "PA_LOG_ARCHIVE_PATTERN=", "PA_LOG_MAX_FILE_SIZE=",
                "PA_LOG_MAX_HISTORY=", "PA_LOG_TOTAL_SIZE_CAP=", "PA_TOKEN_PEPPER=",
                "PA_IDENTITY_HMAC_KEY=", "PA_IDENTITY_ENCRYPTION_KEY=", "openssl rand",
                "EnvironmentFile=", "personnel-archive-service.env");
        for (int step = 1; step <= 9; step++) {
            assertThat(text).contains("[步骤 " + step + "/9]");
        }
    }

    @Test
    void unitUsesTheFixedNonRootHardenedLayout() throws IOException {
        String text = Files.readString(UNIT, StandardCharsets.UTF_8);
        assertThat(text).contains(
            "User=personnel-archive",
            "Group=personnel-archive",
            "Documentation=file:/data/personnel-archive-service/log/",
            "ConditionPathIsReadWrite=/data/personnel-archive-service/log",
            "ConditionPathExists=/data/personnel-archive-service/personnel-archive-service.jar",
            "WorkingDirectory=/data/personnel-archive-service",
            "Environment=SPRING_PROFILES_ACTIVE=prod",
            "Environment=JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai",
            "ExecStart=@JAVA_BIN@ -jar /data/personnel-archive-service/personnel-archive-service.jar",
            "Restart=on-failure",
            "SuccessExitStatus=143",
            "UMask=0027",
            "NoNewPrivileges=true",
            "PrivateTmp=true",
            "ProtectHome=true",
            "ProtectSystem=strict",
            "ReadWritePaths=/data/personnel-archive-service/log",
            "StandardOutput=journal",
            "StandardError=journal"
        ).doesNotContain("User=root", "bash -c", "sh -c", "/opt/personnel-archive-service",
            "EnvironmentFile=", "PA_TOKEN_PEPPER", "PA_IDENTITY_HMAC_KEY",
            "PA_IDENTITY_ENCRYPTION_KEY");
        assertThat(Files.readAllLines(UNIT, StandardCharsets.UTF_8))
            .filteredOn(line -> line.equals("PrivateTmp=true")).hasSize(1);
    }

    @Test
    void prodLauncherBootstrapsAndStartsTheFixedDataServiceWithBoundedDiagnostics() throws IOException {
        String text = Files.readString(STARTER, StandardCharsets.UTF_8);
        assertThat(text)
            .contains("EUID", "umask 077", "/run/systemd/system",
                "SERVICE_NAME=\"personnel-archive-service.service\"",
                "UNIT_FILE=\"/etc/systemd/system/${SERVICE_NAME}\"",
                "JAR_FILE=\"/data/personnel-archive-service/personnel-archive-service.jar\"",
                "LOG_DIR=\"/data/personnel-archive-service/log\"",
                "LOG_FILE=\"${LOG_DIR}/log.$(date +%F).log\"",
                "APP_USER=\"personnel-archive\"", "APP_GROUP=\"personnel-archive\"",
                "JAVA_MAJOR", "getent group", "groupadd --system", "useradd --system",
                "render_service_unit", "install_service_unit", "install -d",
                "WorkingDirectory=/data/personnel-archive-service",
                "-jar /data/personnel-archive-service/personnel-archive-service.jar",
                "\"$JAVA_BIN\" -version", "df -h /data", "du -sh", "systemctl enable --now",
                "systemctl daemon-reload", "systemctl restart",
                "systemctl is-active --quiet", "ss -ltn", "for attempt in {1..30}", "sleep 2",
                "systemctl status", "journalctl -u", "tail -n 100", "exit 1")
            .doesNotContain("set -x", "set -o xtrace", "EnvironmentFile=",
                "personnel-archive-service.env", "/opt/personnel-archive-service", "java -jar",
                "PA_DB_PASSWORD", "PA_TOKEN_PEPPER", "PA_IDENTITY_HMAC_KEY",
                "PA_IDENTITY_ENCRYPTION_KEY");
    }

    @Test
    void prodLauncherRendersASelfContainedServiceUnit(@TempDir Path temporary) throws Exception {
        Path output = temporary.resolve("personnel-archive-service.service");
        CommandResult result = run(List.of("/bin/bash", "-c",
            "set -Eeuo pipefail; source \"$SCRIPT_PATH\"; "
                + "render_service_unit \"$OUTPUT_FILE\" /opt/java21/bin/java"),
            Map.of("SCRIPT_PATH", STARTER.toString(), "OUTPUT_FILE", output.toString()));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(Files.readString(output, StandardCharsets.UTF_8))
            .contains("User=personnel-archive", "Group=personnel-archive",
                "WorkingDirectory=/data/personnel-archive-service",
                "Environment=SPRING_PROFILES_ACTIVE=prod",
                "Environment=JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai",
                "ExecStart=/opt/java21/bin/java -jar /data/personnel-archive-service/personnel-archive-service.jar",
                "ReadWritePaths=/data/personnel-archive-service/log")
            .doesNotContain("EnvironmentFile=", "PA_TOKEN_PEPPER", "PA_IDENTITY_HMAC_KEY",
                "PA_IDENTITY_ENCRYPTION_KEY");
    }

    @Test
    void mysqlHandoffVerifiesTheFixedAccountGrantWithoutChangingItsPassword() throws IOException {
        String sql = Files.readString(SQL, StandardCharsets.UTF_8);
        assertThat(sql)
            .contains("GRANT ALL PRIVILEGES", "ON `personnel_archive`.*",
                "SHOW GRANTS FOR 'personnel_archive'@'127.0.0.1'")
            .doesNotContain("ALTER USER", "IDENTIFIED BY", "CREATE USER", "FLUSH PRIVILEGES",
                "root password", "root密码");

        String documents = readAll(DOCS);
        assertThat(documents)
            .contains("mysql -u root -p", "安装前", "不要粘贴到聊天", "不要保存到普通文本文件",
                "127.0.0.1", "flyway_schema_history", "V1—V15", "version 15",
                "/data/personnel-archive-service", "每天一份", "log.YYYY-MM-DD.log", "不压缩",
                "不会自动删除", "启动prod环境.sh", "只需要", "固定在 prod")
            .doesNotContain("/opt/personnel-archive-service", "--reconfigure", "20MB", "20 MB",
                "保留 30 天", "1GB", "1 GB", "隐藏输入两次", "轮换应用密码",
                "personnel-archive-service.YYYY-MM-DD.log.gz",
                "/data/personnel-archive-service/log/personnel-archive-service.log",
                "/etc/personnel-archive-service/personnel-archive-service.env",
                "目标电脑本地生成三项安全密钥");
    }

    @Test
    void scriptsPassBashSyntaxValidation() throws Exception {
        for (Path script : List.of(INSTALLER, STARTER, BUILDER)) {
            CommandResult result = run(List.of("/bin/bash", "-n", script.toString()), Map.of());
            assertThat(result.exitCode()).as(result.output()).isZero();
        }
    }

    @Test
    void builderUsesAllowlistedStagingChecksumsAndCollisionSuffixes() throws IOException {
        String text = Files.readString(BUILDER, StandardCharsets.UTF_8);
        assertThat(text)
            .contains("mktemp -d", "STAGING_PREFIX", "personnel-archive-service.jar", "SHA256SUMS",
                "start-prod.sh", "BUILD-INFO.txt", ".tar.gz.sha256", "tar -tzf", "for suffix in", "zip -d",
                "BOOT-INF/classes/application-dev.yml", "COPYFILE_DISABLE=1",
                "BOOT-INF/classes/db/migration/V15__rotate_default_admin_credentials.sql",
                "FlywayMigrations=V1-V15")
            .doesNotContain("cp -R deployment", "cp -a deployment", ".env",
                "新建 文本文档 (2).txt", "mvn ", "./mvnw", "git clean", "git reset", "git checkout");
    }

    @Test
    void builderRejectsAJarWithoutV15(@TempDir Path temporary) throws Exception {
        Path jar = temporary.resolve("missing-v15.jar");
        writeFakeExecutableJar(jar, false);

        CommandResult result = run(
            List.of(BUILDER.toString(), jar.toString(), temporary.toString(), "20260814"), Map.of());

        assertThat(result.exitCode()).as(result.output()).isNotZero();
        try (var files = Files.list(temporary)) {
            assertThat(files.noneMatch(path -> path.getFileName().toString()
                .startsWith("人员档案数据服务-统信UOS离线部署"))).isTrue();
        }
    }

    @Test
    void builderCreatesVerifiedArtifactsWithoutOverwriting(@TempDir Path temporary) throws Exception {
        Path jar = temporary.resolve("verified.jar");
        writeFakeExecutableJar(jar, true);

        CommandResult first = run(List.of(BUILDER.toString(), jar.toString(), temporary.toString(), "20260813"),
            Map.of());
        assertThat(first.exitCode()).as(first.output()).isZero();
        Path firstDirectory = temporary.resolve("人员档案数据服务-统信UOS离线部署-20260813");
        assertBundleArtifacts(firstDirectory);

        CommandResult second = run(List.of(BUILDER.toString(), jar.toString(), temporary.toString(), "20260813"),
            Map.of());
        assertThat(second.exitCode()).as(second.output()).isZero();
        Path secondDirectory = temporary.resolve("人员档案数据服务-统信UOS离线部署-20260813-1");
        assertBundleArtifacts(secondDirectory);
        assertThat(firstDirectory).isDirectory();
    }

    private static void assertBundleArtifacts(Path directory) throws IOException {
        assertThat(directory).isDirectory();
        assertThat(directory.resolve("personnel-archive-service.jar")).isRegularFile();
        assertThat(directory.resolve("install.sh")).isRegularFile().isExecutable();
        assertThat(directory.resolve("start-prod.sh")).isRegularFile().isExecutable();
        assertThat(directory.resolve("SHA256SUMS")).isRegularFile();
        assertThat(directory.resolve("BUILD-INFO.txt")).isRegularFile();
        assertThat(Path.of(directory + ".tar.gz")).isRegularFile();
        assertThat(Path.of(directory + ".tar.gz.sha256")).isRegularFile();
        assertTarHasNoAppleMetadata(Path.of(directory + ".tar.gz"));
        try (ZipFile jar = new ZipFile(directory.resolve("personnel-archive-service.jar").toFile())) {
            assertThat(jar.getEntry("BOOT-INF/classes/application-dev.yml")).isNull();
            assertThat(jar.getEntry("BOOT-INF/classes/application-prod.yml")).isNotNull();
            assertThat(jar.getEntry(
                "BOOT-INF/classes/db/migration/V15__rotate_default_admin_credentials.sql")).isNotNull();
        }
        assertThat(Files.readString(directory.resolve("BUILD-INFO.txt"), StandardCharsets.UTF_8))
            .contains("FlywayMigrations=V1-V15");
        String tree = Files.readString(directory.resolve("SHA256SUMS"), StandardCharsets.UTF_8);
        assertThat(tree).contains("  ./personnel-archive-service.jar", "  ./install.sh", "  ./start-prod.sh",
            "  ./service/personnel-archive-service.service.template",
            "  ./mysql/01-确认应用账号授权.sql", "  ./docs/05-故障恢复.txt", "  ./BUILD-INFO.txt")
            .doesNotContain("SHA256SUMS  ./SHA256SUMS", ".env", ".git", "target/");
    }

    private static void assertTarHasNoAppleMetadata(Path archive) throws IOException {
        try (InputStream file = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                assertThat(entry.getName()).doesNotContain("/._", "__MACOSX").doesNotStartWith("._");
            }
        }
    }

    private static void writeFakeExecutableJar(Path jar, boolean includeV15) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,
            "org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class",
            "com.company.personnelarchive.PersonnelArchiveApplication");
        List<String> entries = new ArrayList<>(List.of(
                "org/springframework/boot/loader/launch/JarLauncher.class",
                "BOOT-INF/classes/application-prod.yml",
                "BOOT-INF/classes/application-dev.yml",
                "BOOT-INF/classes/db/migration/V1__initial_schema.sql",
                "BOOT-INF/classes/db/migration/V14__canonicalize_open_ended_range_text.class",
                "BOOT-INF/classes/excel/personnel-archive-template-v1.xlsx"));
        if (includeV15) {
            entries.add("BOOT-INF/classes/db/migration/V15__rotate_default_admin_credentials.sql");
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String entry : entries) {
                output.putNextEntry(new JarEntry(entry));
                output.write(("fixture:" + entry).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static String readAll(List<Path> paths) throws IOException {
        StringBuilder result = new StringBuilder();
        for (Path path : paths) result.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
        return result.toString();
    }

    private static CommandResult run(List<String> command, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(ROOT.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.waitFor(), output);
    }

    private static List<Path> payload() {
        List<Path> paths = new ArrayList<>(List.of(INSTALLER, STARTER, BUILDER, UNIT, SQL));
        paths.addAll(DOCS);
        return List.copyOf(paths);
    }

    private record CommandResult(int exitCode, String output) {}
}
