package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

import static org.assertj.core.api.Assertions.assertThat;

class UosDeploymentContractTest {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path INSTALLER = ROOT.resolve("deployment/uos/install.sh");
    private static final Path BUILDER = ROOT.resolve("deployment/uos/build-bundle.sh");
    private static final Path UNIT = ROOT.resolve(
        "deployment/uos/service/personnel-archive-service.service.template");
    private static final Path SQL = ROOT.resolve("deployment/uos/mysql/01-轮换应用密码并授权.sql");
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
        assertThat(BUILDER).isExecutable();
        for (Path script : List.of(INSTALLER, BUILDER)) {
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
    void installerContainsTheRootSecretAndBoundedReadinessContracts() throws IOException {
        String text = Files.readString(INSTALLER, StandardCharsets.UTF_8);
        assertThat(text)
            .contains("EUID", "sha256sum --check SHA256SUMS", "JAVA_MAJOR", "21",
                "getent group", "useradd --system", "IFS= read -r -s", "openssl rand -base64 48",
                "openssl rand -base64 32", "umask 077", "mktemp", "chmod 0600", "chown root:root",
                "--reconfigure", "--no-start", "systemctl daemon-reload", "systemctl is-active --quiet",
                "ss -ltn", "for attempt in", "rewrite_environment_password", "systemd_quote",
                "PA_TOKEN_PEPPER=", "PA_IDENTITY_HMAC_KEY=", "PA_IDENTITY_ENCRYPTION_KEY=",
                "[[ \"${BASH_SOURCE[0]}\" == \"$0\" ]]", "main \"$@\"")
            .doesNotContain("set -x", "set -o xtrace", "eval ", "envsubst", "source \"$ENV_FILE\"",
                ". \"$ENV_FILE\"", "cat \"$ENV_FILE\"");
        assertThat(text.split("openssl rand -base64", -1).length - 1).isGreaterThanOrEqualTo(3);
        assertThat(text).contains("$'\\r'", "$'\\n'");
        for (int step = 1; step <= 10; step++) {
            assertThat(text).contains("[步骤 " + step + "/10]");
        }
    }

    @Test
    void unitUsesTheFixedNonRootHardenedLayout() throws IOException {
        String text = Files.readString(UNIT, StandardCharsets.UTF_8);
        assertThat(text).contains(
            "User=personnel-archive",
            "Group=personnel-archive",
            "WorkingDirectory=/opt/personnel-archive-service",
            "EnvironmentFile=/etc/personnel-archive-service/personnel-archive-service.env",
            "ExecStart=@JAVA_BIN@ -jar /opt/personnel-archive-service/personnel-archive-service.jar",
            "Restart=on-failure",
            "SuccessExitStatus=143",
            "UMask=0027",
            "NoNewPrivileges=true",
            "PrivateTmp=true",
            "ProtectHome=true",
            "ProtectSystem=strict",
            "ReadWritePaths=/opt/personnel-archive-service/log",
            "StandardOutput=journal",
            "StandardError=journal"
        ).doesNotContain("User=root", "bash -c", "sh -c");
        assertThat(Files.readAllLines(UNIT, StandardCharsets.UTF_8))
            .filteredOn(line -> line.equals("PrivateTmp=true")).hasSize(1);
    }

    @Test
    void mysqlHandoffRotatesAndGrantsWithoutEmbeddingCredentials() throws IOException {
        String sql = Files.readString(SQL, StandardCharsets.UTF_8);
        assertThat(sql)
            .contains("ALTER USER 'personnel_archive'@'127.0.0.1'", "IDENTIFIED BY RANDOM PASSWORD",
                "PASSWORD EXPIRE NEVER", "GRANT ALL PRIVILEGES", "ON `personnel_archive`.*",
                "SHOW GRANTS FOR 'personnel_archive'@'127.0.0.1'")
            .doesNotContain("CREATE USER", "FLUSH PRIVILEGES", "IDENTIFIED BY '", "root password", "root密码");

        String documents = readAll(DOCS);
        assertThat(documents)
            .contains("mysql -u root -p", "安装前", "不要粘贴到聊天", "不要保存到普通文本文件",
                "127.0.0.1", "flyway_schema_history", "version", "14");
    }

    @Test
    void scriptsPassBashSyntaxValidation() throws Exception {
        for (Path script : List.of(INSTALLER, BUILDER)) {
            CommandResult result = run(List.of("/bin/bash", "-n", script.toString()), Map.of());
            assertThat(result.exitCode()).as(result.output()).isZero();
        }
    }

    @Test
    void passwordRewriteEscapesSystemdValueAndPreservesEveryKey(@TempDir Path temporary) throws Exception {
        String oldPassword = "old-password-secret-sentinel";
        String tokenKey = "token-key-secret-sentinel";
        String hmacKey = "hmac-key-secret-sentinel";
        String encryptionKey = "encryption-key-secret-sentinel";
        String replacement = "space # dollar$ equals= quote\" slash\\";
        Path input = temporary.resolve("existing.env");
        Path output = temporary.resolve("rewritten.env");
        String fixture = String.join("\n",
            "SPRING_PROFILES_ACTIVE=prod",
            "PA_DB_PASSWORD=\"" + oldPassword + "\"",
            "PA_TOKEN_PEPPER=\"" + tokenKey + "\"",
            "CUSTOM_SETTING=keep-this-line-byte-for-byte # comment",
            "PA_IDENTITY_HMAC_KEY=\"" + hmacKey + "\"",
            "PA_IDENTITY_ENCRYPTION_KEY=\"" + encryptionKey + "\"") + "\n";
        Files.writeString(input, fixture, StandardCharsets.UTF_8);

        CommandResult result = run(List.of("/bin/bash", "-c",
            "set -Eeuo pipefail; source \"$SCRIPT_PATH\"; "
                + "rewrite_environment_password \"$INPUT_FILE\" \"$OUTPUT_FILE\" \"$TEST_PASSWORD\""),
            Map.of("SCRIPT_PATH", INSTALLER.toString(), "INPUT_FILE", input.toString(),
                "OUTPUT_FILE", output.toString(), "TEST_PASSWORD", replacement));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).doesNotContain(oldPassword, replacement, tokenKey, hmacKey, encryptionKey);
        String rewritten = Files.readString(output, StandardCharsets.UTF_8);
        assertThat(rewritten).contains(
            "PA_DB_PASSWORD=" + systemdQuote(replacement) + "\n",
            "PA_TOKEN_PEPPER=\"" + tokenKey + "\"\n",
            "CUSTOM_SETTING=keep-this-line-byte-for-byte # comment\n",
            "PA_IDENTITY_HMAC_KEY=\"" + hmacKey + "\"\n",
            "PA_IDENTITY_ENCRYPTION_KEY=\"" + encryptionKey + "\"\n");
        assertThat(rewritten.lines().filter(line -> line.startsWith("PA_DB_PASSWORD=")).count()).isEqualTo(1);
    }

    @Test
    void passwordRewriteRejectsLineBreaksWithoutPrintingSecrets(@TempDir Path temporary) throws Exception {
        Path input = temporary.resolve("existing.env");
        Path output = temporary.resolve("rejected.env");
        Files.writeString(input, String.join("\n",
            "PA_DB_PASSWORD=\"old\"",
            "PA_TOKEN_PEPPER=\"token-key-secret-sentinel\"",
            "PA_IDENTITY_HMAC_KEY=\"hmac-key-secret-sentinel\"",
            "PA_IDENTITY_ENCRYPTION_KEY=\"encryption-key-secret-sentinel\"") + "\n",
            StandardCharsets.UTF_8);
        String rejected = "line-one-secret-sentinel\nline-two-secret-sentinel";

        CommandResult result = run(List.of("/bin/bash", "-c",
            "set -Eeuo pipefail; source \"$SCRIPT_PATH\"; "
                + "rewrite_environment_password \"$INPUT_FILE\" \"$OUTPUT_FILE\" \"$TEST_PASSWORD\""),
            Map.of("SCRIPT_PATH", INSTALLER.toString(), "INPUT_FILE", input.toString(),
                "OUTPUT_FILE", output.toString(), "TEST_PASSWORD", rejected));

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).doesNotContain("line-one-secret-sentinel", "line-two-secret-sentinel",
            "token-key-secret-sentinel", "hmac-key-secret-sentinel", "encryption-key-secret-sentinel");
    }

    @Test
    void builderUsesAllowlistedStagingChecksumsAndCollisionSuffixes() throws IOException {
        String text = Files.readString(BUILDER, StandardCharsets.UTF_8);
        assertThat(text)
            .contains("mktemp -d", "STAGING_PREFIX", "personnel-archive-service.jar", "SHA256SUMS",
                "BUILD-INFO.txt", ".tar.gz.sha256", "tar -tzf", "for suffix in")
            .doesNotContain("cp -R deployment", "cp -a deployment", ".env", "application-dev.yml",
                "新建 文本文档 (2).txt", "mvn ", "./mvnw", "git clean", "git reset", "git checkout");
    }

    @Test
    void builderCreatesVerifiedArtifactsWithoutOverwriting(@TempDir Path temporary) throws Exception {
        Path jar = temporary.resolve("verified.jar");
        writeFakeExecutableJar(jar);

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
        assertThat(directory.resolve("SHA256SUMS")).isRegularFile();
        assertThat(directory.resolve("BUILD-INFO.txt")).isRegularFile();
        assertThat(Path.of(directory + ".tar.gz")).isRegularFile();
        assertThat(Path.of(directory + ".tar.gz.sha256")).isRegularFile();
        String tree = Files.readString(directory.resolve("SHA256SUMS"), StandardCharsets.UTF_8);
        assertThat(tree).contains("  ./personnel-archive-service.jar", "  ./install.sh",
            "  ./service/personnel-archive-service.service.template",
            "  ./mysql/01-轮换应用密码并授权.sql", "  ./docs/05-故障恢复.txt", "  ./BUILD-INFO.txt")
            .doesNotContain("SHA256SUMS  ./SHA256SUMS", ".env", ".git", "target/");
    }

    private static void writeFakeExecutableJar(Path jar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,
            "org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class",
            "com.company.personnelarchive.PersonnelArchiveApplication");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (String entry : List.of(
                "org/springframework/boot/loader/launch/JarLauncher.class",
                "BOOT-INF/classes/application-prod.yml",
                "BOOT-INF/classes/db/migration/V1__initial_schema.sql",
                "BOOT-INF/classes/db/migration/V14__canonicalize_open_ended_range_text.class",
                "BOOT-INF/classes/excel/personnel-archive-template-v1.xlsx")) {
                output.putNextEntry(new JarEntry(entry));
                output.write(("fixture:" + entry).getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static String systemdQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
        List<Path> paths = new ArrayList<>(List.of(INSTALLER, BUILDER, UNIT, SQL));
        paths.addAll(DOCS);
        return List.copyOf(paths);
    }

    private record CommandResult(int exitCode, String output) {}
}
