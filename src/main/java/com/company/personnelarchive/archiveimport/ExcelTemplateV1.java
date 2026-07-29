/* Excel V1 协议常量：集中定义工作表、语义标签、容量和模板版本。 */
package com.company.personnelarchive.archiveimport;

import java.util.Map;

public final class ExcelTemplateV1 {
    public static final String VERSION = "V1";
    public static final String MAIN_SHEET = "基本情况";
    public static final String AGE_SHEET = "家庭成员年龄计算";
    public static final int MAIN_EDUCATION_CAPACITY = 5;
    public static final int WORK_EXPERIENCE_CAPACITY = 16;
    public static final int REWARD_PUNISHMENT_CAPACITY = 4;
    public static final int BASE_FAMILY_ROWS = 6;

    static final String EDUCATION_SECTION = "主要学历";
    static final String WORK_SECTION = "工作经历";
    static final String REWARD_SECTION = "奖惩情况";
    static final String FAMILY_SECTION = "重要社会关系家庭主要成员及";

    static final Map<String, String> SCALAR_LABELS = Map.ofEntries(
        Map.entry("employeeNo", "员工号"), Map.entry("name", "姓名"), Map.entry("gender", "性别"),
        Map.entry("birthDate", "出生年月"), Map.entry("ethnicity", "民族"),
        Map.entry("nativePlace", "籍贯"), Map.entry("birthPlace", "出生地"),
        Map.entry("politicalStatus", "政治面貌"), Map.entry("politicalJoinDate", "入党团时间"),
        Map.entry("organizationDepartment", "任职单位及部门"), Map.entry("workStartDate", "参加工作时间"),
        Map.entry("dispatchDate", "派遣至招标公司时间"), Map.entry("position", "职务"),
        Map.entry("titleSkill", "职称/技能"), Map.entry("titleObtainedDate", "取得时间"),
        Map.entry("identityCard", "身份证号"), Map.entry("maritalStatus", "婚姻状况"),
        Map.entry("healthStatus", "健康状况"), Map.entry("address", "通信地址"),
        Map.entry("postalCode", "邮政编码"), Map.entry("phone", "电话")
    );

    private ExcelTemplateV1() {}
}
