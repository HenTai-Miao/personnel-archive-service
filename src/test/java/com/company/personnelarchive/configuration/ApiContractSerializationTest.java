/* API 序列化契约测试：锁定标识符、日期、错误和分页的对外 JSON 形状。 */
package com.company.personnelarchive.configuration;

import com.company.personnelarchive.archiveimport.ImportDtos;
import com.company.personnelarchive.auth.AuthService;
import com.company.personnelarchive.common.ApiError;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.person.PersonDtos;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiContractSerializationTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void bigintIdsAndClientFieldNamesAreStable() throws Exception {
        var duplicate = new ImportDtos.DuplicateSummary("测试甲", "0012", "E-1", "E-2", "2026-07-22T00:00:00Z");
        var item = new ImportDtos.ItemResult(9_007_199_254_740_993L, "fixture.xlsx", "PENDING_DECISION",
            "DUPLICATE_REQUIRES_DECISION", "待确认", duplicate);
        var json = mapper.readTree(mapper.writeValueAsBytes(item));
        assertThat(json.get("itemId").isString()).isTrue();
        assertThat(json.get("itemId").asText()).isEqualTo("9007199254740993");
        assertThat(json.get("fileName").asText()).isEqualTo("fixture.xlsx");
        assertThat(json.get("code").asText()).isEqualTo("DUPLICATE_REQUIRES_DECISION");
        assertThat(json.get("duplicate").get("existingEmployeeNo").asText()).isEqualTo("E-1");
        assertThat(json).hasSize(6);
        assertThat(json.get("duplicate")).hasSize(5);
        var batchJson = mapper.readTree(mapper.writeValueAsBytes(new ImportDtos.BatchResponse(5, List.of(item))));
        assertThat(batchJson).hasSize(2);
        assertThat(batchJson.get("batchId").isString()).isTrue();
        assertThat(batchJson.get("batchId").asText()).isEqualTo("5");
        var completed = mapper.readTree(mapper.writeValueAsBytes(new ImportDtos.ItemResult(
            10, "completed.xlsx", "IMPORTED", "IMPORTED", "导入成功", null)));
        assertThat(completed.has("duplicate")).isFalse();
    }

    @Test
    void authAndDetailMatchRendererShape() throws Exception {
        var auth = new AuthService.TokenResponse("access", "refresh", 900,
            new AuthService.UserResponse("7", "tester", "测试用户", Set.of("VIEWER")));
        var educationSummary = new PersonDtos.EducationSummary("全日制大学", "档案学", "本科", "学士");
        var detail = new PersonDtos.Detail(
            "7", "E-1", "测试甲", "1234***********012",
            new PersonDtos.PersonalInfo("女", "2000-01", "汉族", "湖南", "长沙", "中共党员",
                "2021-07", "已婚", "健康", "测试地址", "410000", "0000*002"),
            new PersonDtos.EmploymentDetails("测试单位", "测试部门", "2020-01", "2021-01",
                "测试职务", "测试技能", "2022-01"),
            new PersonDtos.EducationDetails(educationSummary, new PersonDtos.EducationSummary("", "", "", "")),
            List.of(new PersonDtos.OrderedText(1, "2020-09—2024-06", "示例大学")),
            List.of(new PersonDtos.OrderedText(1, "2024-07—至今", "测试单位")),
            List.of(new PersonDtos.OrderedText(1, "2025-01", "测试奖励")),
            List.of(new PersonDtos.FamilyMember("测试乙", "子女", "2000-01", "35", "群众", "测试单位")),
            new PersonDtos.DeclarationInfo("测试甲", "2026-07-22"),
            new PersonDtos.PhotoMetadata("image/png", "fixture.png", 128, 3, 4),
            new PersonDtos.ArchiveMetadata("V1", 2, "2026-07-22T03:00:00Z"));
        assertThat(mapper.readTree(mapper.writeValueAsBytes(auth))).hasSize(4);
        var json = mapper.readTree(mapper.writeValueAsBytes(detail));
        assertThat(json.at("/personal/gender").asText()).isEqualTo("女");
        assertThat(json.at("/personal/birthDate").asText()).isEqualTo("2000-01");
        assertThat(json.at("/employment/workStartDate").asText()).isEqualTo("2020-01");
        assertThat(json.at("/education/fullTime/institutionName").asText()).isEqualTo("全日制大学");
        assertThat(json.at("/mainEducation/0/rightText").asText()).isEqualTo("示例大学");
        assertThat(json.at("/workExperiences/0/leftText").asText()).isEqualTo("2024-07—至今");
        assertThat(json.at("/workExperiences/0/rightText").asText()).isEqualTo("测试单位");
        assertThat(json.at("/rewardsPunishments/0/rightText").asText()).isEqualTo("测试奖励");
        assertThat(json.at("/familyMembers/0/politicalStatus").asText()).isEqualTo("群众");
        assertThat(json.at("/familyMembers/0/birthDate").asText()).isEqualTo("2000-01");
        assertThat(json.at("/familyMembers/0/age").asText()).isEqualTo("35");
        assertThat(json.at("/declaration/signer").asText()).isEqualTo("测试甲");
        assertThat(json.at("/photo/mimeType").asText()).isEqualTo("image/png");
        assertThat(json.at("/personal/phoneNumberMasked").asText()).isEqualTo("0000*002");
        assertThat(json.at("/personal/phoneNumber").isMissingNode()).isTrue();
        assertThat(json.at("/personal/birthDateYear").isMissingNode()).isTrue();
        assertThat(json.at("/familyMembers/0/ageValue").isMissingNode()).isTrue();
        assertThat(json).hasSize(14);

        var sensitive = mapper.readTree(mapper.writeValueAsBytes(
            new PersonDtos.SensitiveValue("123456789012345678")));
        assertThat(sensitive).hasSize(1);
        assertThat(sensitive.at("/value").asText()).isEqualTo("123456789012345678");
    }

    @Test
    void personListMatchesTheRendererPaginationShape() throws Exception {
        var item = new PersonDtos.PersonListItem("7", "E-1", "测试甲", "测试单位", "测试部门",
            "测试职务", "测试技能", "2026-07-22T03:00:00Z");
        var page = new PersonDtos.PersonListPage(List.of(item), 0, 20, 1, 1);
        var json = mapper.readTree(mapper.writeValueAsBytes(page));
        assertThat(json).hasSize(5);
        assertThat(json.at("/content/0/id").asText()).isEqualTo("7");
        assertThat(json.at("/content/0/updatedAt").asText()).isEqualTo("2026-07-22T03:00:00Z");
    }

    @Test
    void errorEnvelopeMatchesTheStrictRendererSchema() throws Exception {
        var json = mapper.readTree(mapper.writeValueAsBytes(ApiError.of(
            ErrorCode.STALE_IMPORT_DECISION, ErrorCode.STALE_IMPORT_DECISION.message())));
        assertThat(json.get("code").asText()).isEqualTo("STALE_IMPORT_DECISION");
        assertThat(json.get("timestamp").asText()).endsWith("Z");
        assertThat(json.has("details")).isFalse();
        assertThat(json).hasSize(3);

        var internal = mapper.readTree(mapper.writeValueAsBytes(ApiError.withRequestId(
            ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message(),
            "4eb52f0a-ec4c-427f-aa14-482238308eb5")));
        assertThat(internal).hasSize(4);
        assertThat(internal.at("/details/requestId").asText())
            .isEqualTo("4eb52f0a-ec4c-427f-aa14-482238308eb5");
    }
}
