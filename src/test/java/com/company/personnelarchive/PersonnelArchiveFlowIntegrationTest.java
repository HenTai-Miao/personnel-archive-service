/* 真实 MySQL 主流程集成测试：覆盖认证、导入、重复处理、查询和导出协作。 */
package com.company.personnelarchive;

import com.company.personnelarchive.archiveimport.ExcelTemplateV1;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(PersonnelArchiveFlowIntegrationTest.FixedClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PersonnelArchiveFlowIntegrationTest {
    private static final String USERNAME = "flow_admin";
    private static final String PASSWORD = "flow-test-password";
    private static final String DECISION_USERNAME = "flow_editor";
    private static final String DECISION_PASSWORD = "flow-editor-password";
    private static final String EMPLOYEE_NO = "FLOW-001";
    private static final String PERSON_NAME = "测试甲";
    private static final String IDENTITY_CARD = "990000200001010012";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("personnel_archive")
        .withUsername("flow_user")
        .withPassword("flow_password");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock integrationTestClock() {
            return Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    private MockMvc mvc;

    @BeforeEach
    void prepareClientAndUser() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE normalized_username=?", Integer.class, USERNAME) == 0) {
            jdbc.update("INSERT INTO sys_user (tenant_id,username,normalized_username,password_hash,display_name) VALUES (1,?,?,?,?)",
                USERNAME, USERNAME, passwordEncoder.encode(PASSWORD), "流程管理员");
            long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE normalized_username=?", Long.class, USERNAME);
            long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE tenant_id=1 AND role_code='ADMIN'", Long.class);
            jdbc.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (1,?,?)", userId, roleId);
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE normalized_username=?", Integer.class, DECISION_USERNAME) == 0) {
            jdbc.update("INSERT INTO sys_user (tenant_id,username,normalized_username,password_hash,display_name) VALUES (1,?,?,?,?)",
                DECISION_USERNAME, DECISION_USERNAME, passwordEncoder.encode(DECISION_PASSWORD), "流程复核员");
            long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE normalized_username=?", Long.class, DECISION_USERNAME);
            long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE tenant_id=1 AND role_code='ARCHIVE_EDITOR'", Long.class);
            jdbc.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (1,?,?)", userId, roleId);
        }
    }

    @Test
    void importsOverwritesSkipsSearchesAndRebuildsWorkbook() throws Exception {
        assertThat(context.getBeansOfType(UserDetailsService.class))
            .as("custom bearer authentication must not expose Spring's generated in-memory user")
            .isEmpty();
        mvc.perform(get("/api/v1/imports/history"))
            .andExpect(status().isUnauthorized());
        String accessToken = login();

        int peopleBeforeValidationBatch = jdbc.queryForObject("SELECT COUNT(*) FROM pa_person", Integer.class);
        JsonNode validationBatch = uploadValidationBatch(accessToken);
        JsonNode invalidItem = itemByFileName(validationBatch, "invalid-fields.xlsx");
        JsonNode validCompanion = itemByFileName(validationBatch, "valid-companion.xlsx");
        assertThat(invalidItem.at("/status").asText()).isEqualTo("FAILED");
        assertThat(invalidItem.at("/code").asText()).isEqualTo("IMPORT_FIELD_VALIDATION_FAILED");
        assertThat(invalidItem.at("/message").asText())
            .contains("出生年月导入失败（基本情况!F3）")
            .contains("家庭成员第 1 行年龄导入失败（基本情况!D39）");
        assertThat(validCompanion.at("/status").asText()).isEqualTo("IMPORTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pa_person", Integer.class))
            .isEqualTo(peopleBeforeValidationBatch + 1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM pa_person WHERE employee_no='FLOW-INVALID'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM pa_person WHERE employee_no='FLOW-VALID-COMPANION'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM pa_family_member f
            JOIN pa_person p ON p.id=f.person_id
            WHERE p.employee_no='FLOW-INVALID'
            """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT result_message FROM pa_import_item WHERE original_filename='invalid-fields.xlsx'",
            String.class))
            .contains("出生年月导入失败（基本情况!F3）", "家庭成员第 1 行年龄导入失败（基本情况!D39）");

        JsonNode imported = upload(accessToken, workbook("00000001", "初次导入"));
        assertThat(imported.at("/items/0/status").asText()).as(imported.toPrettyString()).isEqualTo("IMPORTED");
        long firstPersonId = activePersonId();
        byte[] storedPhotoBytes = jdbc.queryForObject("SELECT photo_data FROM pa_person_photo WHERE person_id=?", byte[].class, firstPersonId);
        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,
                   political_join_date,political_join_date_year,political_join_date_month,political_join_date_day,
                   declaration_date,declaration_date_year,declaration_date_month,declaration_date_day
            FROM pa_person WHERE id=?
            """, firstPersonId))
            .containsEntry("birth_date", "2000")
            .containsEntry("birth_date_year", 2000)
            .containsEntry("birth_date_month", null)
            .containsEntry("birth_date_day", null)
            .containsEntry("political_join_date", "2021-07")
            .containsEntry("political_join_date_year", 2021)
            .containsEntry("political_join_date_month", 7)
            .containsEntry("political_join_date_day", null)
            .containsEntry("declaration_date", "2026-07-22")
            .containsEntry("declaration_date_year", 2026)
            .containsEntry("declaration_date_month", 7)
            .containsEntry("declaration_date_day", 22);
        assertThat(jdbc.queryForMap("""
            SELECT birth_date,birth_date_year,birth_date_month,birth_date_day,age_text,age_value
            FROM pa_family_member WHERE person_id=? AND sort_order=1
            """, firstPersonId))
            .containsEntry("birth_date", "2000-07-23")
            .containsEntry("birth_date_year", 2000)
            .containsEntry("birth_date_month", 7)
            .containsEntry("birth_date_day", 23)
            .containsEntry("age_text", "35")
            .containsEntry("age_value", 35);
        assertThat(jdbc.queryForMap("""
            SELECT age_text,age_value FROM pa_family_member WHERE person_id=? AND sort_order=2
            """, firstPersonId))
            .containsEntry("age_text", "30")
            .containsEntry("age_value", 30);
        assertThat(jdbc.queryForMap("""
            SELECT organization_name,department_name,work_start_date,
                   work_start_date_year,work_start_date_month,work_start_date_day,
                   dispatched_to_bidding_company_date_year,dispatched_to_bidding_company_date_month,
                   dispatched_to_bidding_company_date_day,title_or_skill_acquired_date_year,
                   title_or_skill_acquired_date_month,title_or_skill_acquired_date_day
            FROM pa_employment WHERE person_id=?
            """, firstPersonId))
            .containsEntry("organization_name", "测试单位")
            .containsEntry("department_name", "测试部门")
            .containsEntry("work_start_date", "2020-01-01")
            .containsEntry("work_start_date_year", 2020)
            .containsEntry("work_start_date_month", 1)
            .containsEntry("work_start_date_day", 1)
            .containsEntry("dispatched_to_bidding_company_date_year", 2021)
            .containsEntry("dispatched_to_bidding_company_date_month", null)
            .containsEntry("dispatched_to_bidding_company_date_day", null)
            .containsEntry("title_or_skill_acquired_date_year", 2022)
            .containsEntry("title_or_skill_acquired_date_month", 1)
            .containsEntry("title_or_skill_acquired_date_day", null);
        assertThat(jdbc.queryForMap(
            "SELECT start_date,end_date,start_date_year,start_date_month,start_date_day,end_date_year,end_date_month,end_date_day,raw_text FROM pa_education WHERE person_id=? AND education_type='MAIN'",
            firstPersonId))
            .containsEntry("start_date", "2020-09")
            .containsEntry("start_date_year", 2020)
            .containsEntry("start_date_month", 9)
            .containsEntry("start_date_day", null)
            .containsEntry("end_date", "2024-06")
            .containsEntry("end_date_year", 2024)
            .containsEntry("end_date_month", 6)
            .containsEntry("end_date_day", null)
            .containsEntry("raw_text", "2020-09—2024-06\t示例大学");
        assertThat(jdbc.queryForObject("SELECT start_date FROM pa_work_experience WHERE person_id=?", String.class, firstPersonId))
            .isEqualTo("2024-07");
        assertThat(jdbc.queryForObject("SELECT end_date FROM pa_work_experience WHERE person_id=?", String.class, firstPersonId))
            .isEqualTo("至今");
        assertThat(jdbc.queryForMap("""
            SELECT start_date_year,start_date_month,start_date_day,end_date_year,end_date_month,end_date_day
            FROM pa_work_experience WHERE person_id=?
            """, firstPersonId))
            .containsEntry("start_date_year", 2024)
            .containsEntry("start_date_month", 7)
            .containsEntry("start_date_day", null)
            .containsEntry("end_date_year", null)
            .containsEntry("end_date_month", null)
            .containsEntry("end_date_day", null);
        assertThat(jdbc.queryForObject("SELECT organization_and_position FROM pa_work_experience WHERE person_id=?", String.class, firstPersonId))
            .isEqualTo("初次导入");
        assertThat(jdbc.queryForObject("SELECT raw_text FROM pa_work_experience WHERE person_id=?", String.class, firstPersonId))
            .isEqualTo("2024-07—至今\t初次导入");
        assertThat(jdbc.queryForObject("SELECT occurred_date FROM pa_reward_punishment WHERE person_id=?", String.class, firstPersonId))
            .isEqualTo("2025-01-02");
        assertThat(jdbc.queryForMap("""
            SELECT occurred_date_year,occurred_date_month,occurred_date_day
            FROM pa_reward_punishment WHERE person_id=?
            """, firstPersonId))
            .containsEntry("occurred_date_year", 2025)
            .containsEntry("occurred_date_month", 1)
            .containsEntry("occurred_date_day", 2);
        assertThat(jdbc.queryForObject("SELECT byte_size FROM pa_person_photo WHERE person_id=?", Long.class, firstPersonId))
            .isPositive();
        var storedPhoto = jdbc.queryForMap("SELECT anchor_col1,anchor_row1,anchor_dx1,anchor_dy1,anchor_col2,anchor_row2,anchor_dx2,anchor_dy2,anchor_type FROM pa_person_photo WHERE person_id=?", firstPersonId);
        assertThat(((Number) storedPhoto.get("anchor_col1")).intValue()).isEqualTo(8);
        assertThat(((Number) storedPhoto.get("anchor_row1")).intValue()).isEqualTo(4);
        assertThat(((Number) storedPhoto.get("anchor_col2")).intValue()).isEqualTo(9);
        assertThat(((Number) storedPhoto.get("anchor_row2")).intValue()).isEqualTo(7);
        assertThat(storedPhoto)
            .containsEntry("anchor_dx1", 12_345)
            .containsEntry("anchor_dy1", 23_456)
            .containsEntry("anchor_dx2", 34_567)
            .containsEntry("anchor_dy2", 45_678)
            .containsEntry("anchor_type", "MOVE_DONT_RESIZE");

        var historyResult = mvc.perform(get("/api/v1/imports/history")
                .param("page", "0").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode history = objectMapper.readTree(historyResult.getContentAsByteArray());
        assertThat(history.at("/content/0/itemId").isString()).isTrue();
        assertThat(history.at("/content/0/batchId").isString()).isTrue();
        assertThat(history.at("/content/0/operatorName").asText()).isEqualTo("流程管理员");
        assertThat(history.at("/content/0/fileName").asText()).isEqualTo("fixture.xlsx");
        assertThat(history.at("/content/0/status").asText()).isEqualTo("IMPORTED");
        assertThat(history.at("/content/0/message").asText()).isEqualTo("导入成功");
        assertThat(history.at("/page").asInt()).isZero();
        assertThat(history.at("/size").asInt()).isEqualTo(20);
        assertThat(history.at("/totalElements").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode duplicate = upload(accessToken, workbook("00000002", "覆盖版本"));
        assertThat(duplicate.at("/items/0/status").asText()).isEqualTo("PENDING_DECISION");
        assertThat(duplicate.at("/items/0/duplicate/identityCardLastFour").asText()).isEqualTo("0012");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action_code='IMPORT'", Integer.class))
            .as("a successful duplicate precheck is still an audited import operation")
            .isGreaterThanOrEqualTo(4);
        String overwriteItemId = duplicate.at("/items/0/itemId").asText();
        String decisionAccessToken = login(DECISION_USERNAME, DECISION_PASSWORD);
        JsonNode overwritten = decide(decisionAccessToken, overwriteItemId, "OVERWRITE");
        assertThat(overwritten.get("status").asText()).isEqualTo("OVERWRITTEN");
        var decidedHistoryResult = mvc.perform(get("/api/v1/imports/history")
                .param("page", "0").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn().getResponse();
        JsonNode decidedHistory = objectMapper.readTree(decidedHistoryResult.getContentAsByteArray());
        JsonNode decidedHistoryItem = java.util.stream.StreamSupport.stream(
                decidedHistory.at("/content").spliterator(), false)
            .filter(item -> overwriteItemId.equals(item.get("itemId").asText()))
            .findFirst().orElseThrow();
        assertThat(decidedHistoryItem.get("operatorName").asText()).isEqualTo("流程复核员");
        long secondPersonId = activePersonId();
        assertThat(secondPersonId).isNotEqualTo(firstPersonId);
        assertThat(jdbc.queryForObject("SELECT replaced_by_person_id FROM pa_person WHERE id=?", Long.class, firstPersonId))
            .isEqualTo(secondPersonId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pa_person WHERE employee_no=?", Integer.class, EMPLOYEE_NO))
            .isEqualTo(2);

        JsonNode pendingSkip = upload(accessToken, workbook("00000003", "应被跳过"));
        JsonNode skipped = decide(accessToken, pendingSkip.at("/items/0/itemId").asText(), "SKIP");
        assertThat(skipped.get("status").asText()).isEqualTo("SKIPPED");
        assertThat(activePersonId()).isEqualTo(secondPersonId);
        assertThat(jdbc.queryForObject("SELECT phone_number FROM pa_person WHERE id=?", String.class, secondPersonId))
            .isEqualTo("00000002");

        var listResult = mvc.perform(get("/api/v1/persons")
                .param("name", "测试")
                .param("employeeNo", "FLOW")
                .param("department", "测试部")
                .param("page", "0").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn();
        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsByteArray());
        assertThat(list.at("/totalElements").asLong()).isEqualTo(1);
        assertThat(list.at("/totalPages").asInt()).isEqualTo(1);
        assertThat(list.at("/content/0/id").asText()).isEqualTo(String.valueOf(secondPersonId));
        assertThat(list.at("/content/0/employeeNo").asText()).isEqualTo(EMPLOYEE_NO);
        assertThat(list.at("/content/0/name").asText()).isEqualTo(PERSON_NAME);
        assertThat(list.at("/content/0/organization").asText()).isEqualTo("测试单位");
        assertThat(list.at("/content/0/department").asText()).isEqualTo("测试部门");
        assertThat(list.at("/content/0/updatedAt").asText()).isNotBlank();

        var escapedListResult = mvc.perform(get("/api/v1/persons")
                .param("name", "%")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(escapedListResult.getResponse().getContentAsByteArray())
            .at("/totalElements").asLong()).isZero();

        var searchResult = mvc.perform(get("/api/v1/family-members/search")
                .param("name", "测试乙").param("page", "0").param("size", "20")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn();
        JsonNode search = objectMapper.readTree(searchResult.getResponse().getContentAsByteArray());
        assertThat(search.at("/content/0/personId").asText()).isEqualTo(String.valueOf(secondPersonId));

        var detailResult = mvc.perform(get("/api/v1/persons/{id}", secondPersonId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn();
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsByteArray());
        assertThat(detail.at("/personal/birthDate").asText()).isEqualTo("2000");
        assertThat(detail.at("/personal/politicalJoinDate").asText()).isEqualTo("2021-07");
        assertThat(detail.at("/employment/workStartDate").asText()).isEqualTo("2020-01-01");
        assertThat(detail.at("/employment/dispatchedToBiddingCompanyDate").asText()).isEqualTo("2021");
        assertThat(detail.at("/education/fullTime/institutionName").asText()).isEqualTo("全日制大学");
        assertThat(detail.at("/mainEducation/0/rightText").asText()).isEqualTo("示例大学");
        assertThat(detail.at("/workExperiences/0/leftText").asText()).isEqualTo("2024-07—至今");
        assertThat(detail.at("/workExperiences/0/rightText").asText()).isEqualTo("覆盖版本");
        assertThat(detail.at("/rewardsPunishments/0/rightText").asText()).isEqualTo("测试奖励");
        assertThat(detail.at("/familyMembers/0/politicalStatus").asText()).isEqualTo("测试面貌");
        assertThat(detail.at("/familyMembers/0/birthDate").asText()).isEqualTo("2000-07-23");
        assertThat(detail.at("/familyMembers/0/age").asText()).isEqualTo("35");
        assertThat(detail.at("/familyMembers/1/age").asText()).isEqualTo("30");
        assertThat(detail.at("/declaration/date").asText()).isEqualTo("2026-07-22");
        assertThat(detail.at("/personal").has("birthDateYear")).isFalse();
        assertThat(detail.at("/familyMembers/0").has("ageValue")).isFalse();
        assertThat(detail.at("/photo/mimeType").asText()).isEqualTo("image/png");
        assertThat(detail.at("/identityCardMasked").asText()).isEqualTo("9900***********012");
        assertThat(detail.at("/personal/phoneNumberMasked").asText()).isEqualTo("0000*002");
        assertThat(detail.at("/personal/phoneNumber").isMissingNode()).isTrue();
        var identityResult = mvc.perform(get("/api/v1/persons/{id}/sensitive-value", secondPersonId)
                .param("field", "IDENTITY_CARD")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(identityResult.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(objectMapper.readTree(identityResult.getContentAsByteArray()).at("/value").asText())
            .isEqualTo(IDENTITY_CARD);
        var phoneResult = mvc.perform(get("/api/v1/persons/{id}/sensitive-value", secondPersonId)
                .param("field", "PHONE_NUMBER")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(phoneResult.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(objectMapper.readTree(phoneResult.getContentAsByteArray()).at("/value").asText())
            .isEqualTo("00000002");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE resource_type IN ('PERSON_SENSITIVE_IDENTITY_CARD','PERSON_SENSITIVE_PHONE_NUMBER')", Integer.class))
            .isEqualTo(2);
        var photoResult = mvc.perform(get("/api/v1/persons/{id}/photo", secondPersonId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(photoResult.getContentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(photoResult.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(photoResult.getContentAsByteArray()).isEqualTo(storedPhotoBytes);
        byte[] exported = mvc.perform(get("/api/v1/persons/{id}/archive", secondPersonId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            var main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            assertThat(main.getRow(1).getCell(1).getStringCellValue()).isEqualTo(EMPLOYEE_NO);
            assertThat(main.getRow(11).getCell(7).getStringCellValue()).isEqualTo("00000002");
            assertThat(main.getRow(12).getCell(3).getStringCellValue()).isEqualTo("示例大学");
            assertThat(main.getRow(17).getCell(1).getStringCellValue()).isEqualTo("2024-07—至今");
            assertThat(formatter.formatCellValue(main.getRow(2).getCell(5))).isEqualTo("2000");
            assertThat(formatter.formatCellValue(main.getRow(4).getCell(5))).isEqualTo("2020-01-01");
            assertThat(main.getRow(9).getCell(2).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(formatter.formatCellValue(main.getRow(38).getCell(3))).isEqualTo("35");
            assertThat(formatter.formatCellValue(main.getRow(39).getCell(3))).isEqualTo("30");
            var helperBirthDate = workbook.getSheet(ExcelTemplateV1.AGE_SHEET).getRow(1).getCell(3);
            assertThat(formatter.formatCellValue(helperBirthDate)).isEqualTo("2000-07-23");
            assertThat(workbook.getSheet(ExcelTemplateV1.AGE_SHEET).getRow(1).getCell(4).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(workbook.getAllPictures()).hasSize(1);
            assertThat(workbook.getAllPictures().getFirst().getData()).isEqualTo(storedPhotoBytes);
            var anchor = ((org.apache.poi.xssf.usermodel.XSSFPicture) main.getDrawingPatriarch()
                .getShapes().getFirst()).getClientAnchor();
            assertThat((int) anchor.getCol1()).isEqualTo(8);
            assertThat((int) anchor.getRow1()).isEqualTo(4);
            assertThat(anchor.getDx1()).isEqualTo(12_345);
            assertThat(anchor.getDy1()).isEqualTo(23_456);
            assertThat((int) anchor.getCol2()).isEqualTo(9);
            assertThat((int) anchor.getRow2()).isEqualTo(7);
            assertThat(anchor.getDx2()).isEqualTo(34_567);
            assertThat(anchor.getDy2()).isEqualTo(45_678);
            assertThat(main.getDrawingPatriarch().getCTDrawing().getTwoCellAnchorArray(0).getEditAs().toString())
                .isEqualTo("oneCell");
            assertThat(main.getMergedRegions()).hasSize(73);
        }
        byte[] bundle = mvc.perform(post("/api/v1/persons/exports")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(java.util.Map.of(
                    "personIds", List.of(String.valueOf(secondPersonId))))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bundle))) {
            assertThat(zip.getNextEntry().getName()).endsWith(".xlsx");
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(zip.readAllBytes()))) {
                assertThat(workbook.getSheet(ExcelTemplateV1.MAIN_SHEET).getRow(1).getCell(1)
                    .getStringCellValue()).isEqualTo(EMPLOYEE_NO);
                assertThat(workbook.getAllPictures()).hasSize(1);
            }
            assertThat(zip.getNextEntry()).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE action_code IN ('LOGIN','IMPORT','OVERWRITE','SKIP','VIEW','EXPORT')", Integer.class))
            .isGreaterThanOrEqualTo(8);
    }

    private String login() throws Exception {
        return login(USERNAME, PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(java.util.Map.of("username", username, "password", password))))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("accessToken").asText();
    }

    private JsonNode upload(String token, byte[] workbook) throws Exception {
        var file = new MockMultipartFile("files", "fixture.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
        var result = mvc.perform(multipart("/api/v1/imports").file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode uploadValidationBatch(String token) throws Exception {
        var invalid = new MockMultipartFile("files", "invalid-fields.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            validationWorkbook("FLOW-INVALID", "错误字段档案", true));
        var valid = new MockMultipartFile("files", "valid-companion.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            validationWorkbook("FLOW-VALID-COMPANION", "批次有效档案", false));
        var result = mvc.perform(multipart("/api/v1/imports").file(invalid).file(valid)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static JsonNode itemByFileName(JsonNode batch, String fileName) {
        return java.util.stream.StreamSupport.stream(batch.at("/items").spliterator(), false)
            .filter(item -> fileName.equals(item.at("/fileName").asText()))
            .findFirst()
            .orElseThrow();
    }

    private JsonNode decide(String token, String itemId, String decision) throws Exception {
        var result = mvc.perform(post("/api/v1/imports/{itemId}/decision", itemId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(java.util.Map.of("decision", decision))))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private long activePersonId() {
        return jdbc.queryForObject("SELECT id FROM pa_person WHERE employee_no=? AND is_deleted=0", Long.class, EMPLOYEE_NO);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private byte[] validationWorkbook(String employeeNo, String personName, boolean invalid) throws Exception {
        try (var input = getClass().getResourceAsStream("/excel/personnel-archive-template-v1.xlsx");
             var workbook = new XSSFWorkbook(input); var output = new ByteArrayOutputStream()) {
            var main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            main.getRow(1).getCell(1).setCellValue(employeeNo);
            main.getRow(2).getCell(1).setCellValue(personName);
            if (invalid) {
                main.getRow(2).getCell(5).setCellValue("二〇二四年春");
                main.getRow(38).getCell(1).setCellValue("朋友");
                main.getRow(38).getCell(2).setCellValue("模糊年龄成员");
                main.getRow(38).getCell(3).setCellValue("三十多岁");
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbook(String phone, String workDescription) throws Exception {
        try (var input = getClass().getResourceAsStream("/excel/personnel-archive-template-v1.xlsx");
             var workbook = new XSSFWorkbook(input); var output = new ByteArrayOutputStream()) {
            var main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            main.getRow(1).getCell(1).setCellValue(EMPLOYEE_NO);
            main.getRow(2).getCell(1).setCellValue(PERSON_NAME);
            main.getRow(2).getCell(3).setCellValue("女");
            main.getRow(2).getCell(5).setCellValue("二〇〇〇年");
            main.getRow(2).getCell(7).setCellValue("汉族");
            main.getRow(3).getCell(1).setCellValue("湖南长沙");
            main.getRow(3).getCell(3).setCellValue("湖南长沙");
            main.getRow(3).getCell(5).setCellValue("中共党员");
            main.getRow(3).getCell(7).setCellValue("二〇二一年七月");
            main.getRow(4).getCell(1).setCellValue("测试单位 / 测试部门");
            main.getRow(4).getCell(5).setCellValue("二〇二〇.一.一");
            main.getRow(4).getCell(7).setCellValue("二〇二一年");
            main.getRow(5).getCell(1).setCellValue("测试职务");
            main.getRow(5).getCell(5).setCellValue("测试技能");
            main.getRow(5).getCell(7).setCellValue("二〇二二年一月");
            main.getRow(6).getCell(1).setCellValue(IDENTITY_CARD);
            main.getRow(6).getCell(5).setCellValue("已婚");
            main.getRow(6).getCell(7).setCellValue("健康");
            main.getRow(7).getCell(2).setCellValue("本科");
            main.getRow(7).getCell(5).setCellValue("全日制大学");
            main.getRow(8).getCell(2).setCellValue("学士");
            main.getRow(8).getCell(5).setCellValue("档案学");
            main.getRow(11).getCell(1).setCellValue("测试地址");
            main.getRow(11).getCell(5).setCellValue("410000");
            main.getRow(11).getCell(7).setCellValue(phone);
            main.getRow(12).getCell(1).setCellValue("二〇二〇年九月至二〇二四年六月");
            main.getRow(12).getCell(3).setCellValue("示例大学");
            main.getRow(17).getCell(1).setCellValue("二〇二四年七月至今");
            main.getRow(17).getCell(3).setCellValue(workDescription);
            main.getRow(33).getCell(1).setCellValue("二〇二五年一月二日");
            main.getRow(33).getCell(3).setCellValue("测试奖励");
            main.getRow(38).getCell(1).setCellValue("测试称谓");
            main.getRow(38).getCell(2).setCellValue("测试乙");
            main.getRow(38).getCell(3).setCellValue("三十五岁");
            main.getRow(38).getCell(4).setCellValue("测试面貌");
            main.getRow(38).getCell(5).setCellValue("测试单位及职务");
            main.getRow(39).getCell(1).setCellValue("朋友");
            main.getRow(39).getCell(2).setCellValue("模糊年龄成员");
            main.getRow(39).getCell(3).setCellValue("30岁");
            main.getRow(39).getCell(4).setCellValue("群众");
            main.getRow(39).getCell(5).setCellValue("测试单位");
            main.getRow(45).getCell(1).setCellValue("承诺人：测试甲  时间：二〇二六年七月二十二日");
            var helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
            helper.getRow(1).getCell(1).setCellValue("测试称谓");
            helper.getRow(1).getCell(2).setCellValue("测试乙");
            helper.getRow(1).getCell(3).setCellValue("二〇〇〇年七月二十三日");
            var image = new BufferedImage(3, 4, BufferedImage.TYPE_INT_RGB);
            var imageBytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", imageBytes);
            int picture = workbook.addPicture(imageBytes.toByteArray(), Workbook.PICTURE_TYPE_PNG);
            var anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(8); anchor.setRow1(4); anchor.setDx1(12_345); anchor.setDy1(23_456);
            anchor.setCol2(9); anchor.setRow2(7); anchor.setDx2(34_567); anchor.setDy2(45_678);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
            main.createDrawingPatriarch().createPicture(anchor, picture);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
