/* Excel V1 协议常量：集中定义工作表、标签、合并区域、容量和模板版本。 */
package com.company.personnelarchive.archiveimport;

import java.util.List;
import java.util.Map;

public final class ExcelTemplateV1 {
    public static final String VERSION = "V1";
    public static final String MAIN_SHEET = "基本情况";
    public static final String AGE_SHEET = "家庭成员年龄计算";
    public static final int MAIN_EDUCATION_CAPACITY = 5;
    public static final int WORK_EXPERIENCE_CAPACITY = 16;
    public static final int REWARD_PUNISHMENT_CAPACITY = 4;
    public static final int FAMILY_MEMBER_CAPACITY = 6;

    public static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("A1", "个人基本情况表"), Map.entry("A2", "员工号："), Map.entry("A3", "姓名"),
        Map.entry("C3", "性别"), Map.entry("E3", "出生年月"), Map.entry("G3", "民族"),
        Map.entry("A4", "籍贯"), Map.entry("C4", "出生地"), Map.entry("E4", "政治面貌"),
        Map.entry("A5", "任职单位及部门"), Map.entry("A6", "职务"), Map.entry("E6", "职称/技能"),
        Map.entry("A7", "身份证号"), Map.entry("A13", "主要学历"), Map.entry("A18", "工作经历"),
        Map.entry("A34", "奖惩情况"), Map.entry("A38", "重要社会关系\n家庭主要成员及")
    );
    public static final List<String> REQUIRED_MERGES = List.of(
        "B5:D5", "B6:D6", "B7:D7", "A13:A17", "A18:A33", "A34:A37", "A38:A44",
        "F8:I9", "F10:I11", "F39:I39", "F44:I44", "A45:I45", "B46:I46"
    );
    public static final Map<String, String> AGE_LABELS = Map.of(
        "A1", "重要社会关系\n家庭主要成员及", "B1", "称谓", "C1", "姓名", "D1", "出生年月"
    );

    private ExcelTemplateV1() {}
}
