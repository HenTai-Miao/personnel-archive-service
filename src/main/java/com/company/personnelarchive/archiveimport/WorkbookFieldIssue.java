package com.company.personnelarchive.archiveimport;

/** 可安全返回给导入用户的单个年龄或日期问题，不携带单元格原值。 */
public record WorkbookFieldIssue(
    String issueCode,
    String fieldName,
    String sheetName,
    String cellAddress,
    String guidance
) {
    public WorkbookFieldIssue {
        issueCode = required(issueCode, "issueCode");
        fieldName = required(fieldName, "fieldName");
        sheetName = required(sheetName, "sheetName");
        cellAddress = required(cellAddress, "cellAddress");
        guidance = required(guidance, "guidance");
    }

    public String safeMessage() {
        return fieldName + "导入失败（" + sheetName + "!" + cellAddress + "）：" + guidance;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.strip();
    }
}
