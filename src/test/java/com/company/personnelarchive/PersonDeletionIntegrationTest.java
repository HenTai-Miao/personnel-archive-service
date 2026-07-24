/* 真实 MySQL 档案删除集成测试：覆盖角色授权、长原因、历史保留、查询过滤与审计。 */
package com.company.personnelarchive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PersonDeletionIntegrationTest {
    private static final String ADMIN = "deletion_admin";
    private static final String EDITOR = "deletion_editor";
    private static final String VIEWER = "deletion_viewer";
    private static final String PASSWORD = "deletion-test-password";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.3")
        .withDatabaseName("personnel_archive")
        .withUsername("deletion_user")
        .withPassword("deletion_password");

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
    void prepareClientAndUsers() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ensureUser(ADMIN, "ADMIN");
        ensureUser(EDITOR, "ARCHIVE_EDITOR");
        ensureUser(VIEWER, "VIEWER");
    }

    @Test
    void editorSoftDeletesLongReasonWhilePreservingHistoryAndHidingActiveQueries() throws Exception {
        long editorId = userId(EDITOR);
        long personId = insertPerson("DELETE-LONG-001", "待删除甲", editorId);
        jdbc.update("INSERT INTO pa_employment (tenant_id,person_id,organization_name) VALUES (1,?,'测试单位')",
            personId);
        jdbc.update("""
            INSERT INTO pa_family_member
            (tenant_id,person_id,relationship_name,member_name,normalized_member_name,sort_order)
            VALUES (1,?,'测试关系','删除检索成员','删除检索成员',1)
            """, personId);
        jdbc.update("""
            INSERT INTO pa_person_photo
            (tenant_id,person_id,photo_data,mime_type,byte_size,width_pixels,height_pixels,sha256)
            VALUES (1,?,?,'image/png',3,1,1,?)
            """, personId, new byte[]{1, 2, 3}, new byte[32]);
        String reason = "误导入：经复核该文件属于错误批次。".repeat(30);
        assertThat(reason.length()).isGreaterThan(255).isLessThanOrEqualTo(10_000);

        mvc.perform(post("/api/v1/persons/{personId}/deletion", personId)
                .header(HttpHeaders.AUTHORIZATION, bearer(login(EDITOR)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("reason", reason))))
            .andExpect(status().isNoContent());

        Map<String, Object> deleted = jdbc.queryForMap("""
            SELECT is_deleted,deleted_at,deleted_by,delete_reason,replaced_by_person_id,updated_by
            FROM pa_person WHERE id=?
            """, personId);
        assertThat(deleted.get("is_deleted")).isEqualTo(true);
        assertThat(deleted.get("deleted_at")).isNotNull();
        assertThat(((Number) deleted.get("deleted_by")).longValue()).isEqualTo(editorId);
        assertThat(deleted.get("delete_reason")).isEqualTo(reason);
        assertThat(deleted.get("replaced_by_person_id")).isNull();
        assertThat(((Number) deleted.get("updated_by")).longValue()).isEqualTo(editorId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pa_employment WHERE person_id=?", Integer.class, personId))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pa_family_member WHERE person_id=?", Integer.class, personId))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pa_person_photo WHERE person_id=?", Integer.class, personId))
            .isEqualTo(1);

        String editorToken = login(EDITOR);
        mvc.perform(get("/api/v1/persons/{personId}", personId)
                .header(HttpHeaders.AUTHORIZATION, bearer(editorToken)))
            .andExpect(status().isNotFound());
        JsonNode list = responseJson(get("/api/v1/persons")
            .param("employeeNo", "DELETE-LONG-001")
            .header(HttpHeaders.AUTHORIZATION, bearer(editorToken)));
        assertThat(list.at("/totalElements").asLong()).isZero();
        JsonNode family = responseJson(get("/api/v1/family-members/search")
            .param("name", "删除检索成员")
            .header(HttpHeaders.AUTHORIZATION, bearer(editorToken)));
        assertThat(family.at("/totalElements").asLong()).isZero();

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_operation_log
            WHERE action_code='DELETE' AND subject_person_id=? AND operator_user_id=? AND outcome='SUCCESS'
            """, Integer.class, personId, editorId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_operation_log
            WHERE subject_person_id=? AND (
                COALESCE(error_code,'') LIKE CONCAT('%',?,'%')
                OR COALESCE(CAST(safe_metadata AS CHAR),'') LIKE CONCAT('%',?,'%'))
            """, Integer.class, personId, reason, reason)).isZero();

        JsonNode missing = responseJson(post("/api/v1/persons/{personId}/deletion", personId)
            .header(HttpHeaders.AUTHORIZATION, bearer(editorToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of("reason", "重复删除"))), 404);
        assertThat(missing.at("/code").asText()).isEqualTo("NOT_FOUND");
        assertThat(missing.at("/message").asText()).isEqualTo("档案不存在或已删除");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_operation_log WHERE action_code='DELETE' AND subject_person_id=?
            """, Integer.class, personId)).isEqualTo(1);

        long replacementId = insertPerson("DELETE-LONG-001", "重新导入甲", editorId);
        assertThat(replacementId).isNotEqualTo(personId);
    }

    @Test
    void adminAndEditorCanDeleteButViewerAndAnonymousCannot() throws Exception {
        long adminPersonId = insertPerson("DELETE-ROLE-ADMIN", "权限甲", userId(ADMIN));
        long editorPersonId = insertPerson("DELETE-ROLE-EDITOR", "权限乙", userId(EDITOR));
        long viewerPersonId = insertPerson("DELETE-ROLE-VIEWER", "权限丙", userId(VIEWER));

        delete(adminPersonId, login(ADMIN), "管理员删除").andExpect(status().isNoContent());
        delete(editorPersonId, login(EDITOR), "编辑员删除").andExpect(status().isNoContent());
        delete(viewerPersonId, login(VIEWER), "查看员无权删除").andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/persons/{personId}/deletion", viewerPersonId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("reason", "匿名无权删除"))))
            .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT is_deleted FROM pa_person WHERE id=?", Integer.class, viewerPersonId))
            .isZero();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_operation_log
            WHERE action_code='DELETE' AND subject_person_id IN (?,?,?)
            """, Integer.class, adminPersonId, editorPersonId, viewerPersonId)).isEqualTo(2);
    }

    @Test
    void rejectsBlankAndOversizedReasonsWithoutDeletingTheArchive() throws Exception {
        long personId = insertPerson("DELETE-VALIDATION", "校验甲", userId(EDITOR));
        String token = login(EDITOR);

        delete(personId, token, " \n\t").andExpect(status().isBadRequest());
        delete(personId, token, "原".repeat(10_001)).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT is_deleted FROM pa_person WHERE id=?", Integer.class, personId))
            .isZero();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_operation_log WHERE action_code='DELETE' AND subject_person_id=?
            """, Integer.class, personId)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions delete(long personId, String token, String reason)
        throws Exception {
        return mvc.perform(post("/api/v1/persons/{personId}/deletion", personId)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(Map.of("reason", reason))));
    }

    private JsonNode responseJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
        throws Exception {
        return responseJson(request, 200);
    }

    private JsonNode responseJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  int expectedStatus) throws Exception {
        var result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private long insertPerson(String employeeNo, String name, long operatorId) {
        jdbc.update("""
            INSERT INTO pa_person
            (tenant_id,employee_no,person_name,normalized_name,template_version,created_by,updated_by)
            VALUES (1,?,?,?,'V1',?,?)
            """, employeeNo, name, name, operatorId, operatorId);
        return jdbc.queryForObject("SELECT id FROM pa_person WHERE employee_no=? AND is_deleted=0",
            Long.class, employeeNo);
    }

    private void ensureUser(String username, String role) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE normalized_username=?", Integer.class, username) > 0) {
            return;
        }
        jdbc.update("""
            INSERT INTO sys_user (tenant_id,username,normalized_username,password_hash,display_name)
            VALUES (1,?,?,?,?)
            """, username, username, passwordEncoder.encode(PASSWORD), username);
        long roleId = jdbc.queryForObject(
            "SELECT id FROM sys_role WHERE tenant_id=1 AND role_code=?", Long.class, role);
        jdbc.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (1,?,?)",
            userId(username), roleId);
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE normalized_username=?", Long.class, username);
    }

    private String login(String username) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("username", username, "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).at("/accessToken").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
