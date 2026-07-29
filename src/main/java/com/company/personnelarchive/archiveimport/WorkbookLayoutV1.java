package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the V1 workbook structure from exact labels instead of historical row numbers.
 * Business values are deliberately not interpreted here; this class only establishes safe coordinates.
 */
record WorkbookLayoutV1(
    XSSFSheet main,
    XSSFSheet helper,
    int educationRow,
    int workRow,
    int rewardRow,
    int familyHeaderRow,
    int declarationValueRow,
    int helperHeaderRow,
    Map<String, CellAddress> scalarValues
) {
    private static final int TOP_LAST_ROW = 11;
    private static final int MAX_LAYOUT_ROW = 10_000;
    private static final int MAIN_LAST_COLUMN = 8;
    private static final int HELPER_LAST_COLUMN = 4;

    static WorkbookLayoutV1 resolve(XSSFWorkbook workbook, DataFormatter formatter) {
        XSSFSheet main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
        XSSFSheet helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
        if (main == null || helper == null) {
            throw invalid("缺少 V1 工作表");
        }

        requireUnique(main, formatter, "个人基本情况表", 0, TOP_LAST_ROW, MAIN_LAST_COLUMN);
        Map<String, CellAddress> scalarValues = new HashMap<>();
        ExcelTemplateV1.SCALAR_LABELS.forEach((key, label) -> {
            CellAddress labelAddress = requireUnique(main, formatter, label, 0, TOP_LAST_ROW, MAIN_LAST_COLUMN);
            if (labelAddress.getColumn() >= MAIN_LAST_COLUMN) {
                throw invalid("模板标签没有对应值区域：" + label);
            }
            scalarValues.put(key, new CellAddress(labelAddress.getRow(), labelAddress.getColumn() + 1));
        });
        resolveEducationSummary(main, formatter, scalarValues);

        int educationRow = requireUnique(main, formatter, ExcelTemplateV1.EDUCATION_SECTION,
            0, MAX_LAYOUT_ROW, MAIN_LAST_COLUMN).getRow();
        int workRow = requireUnique(main, formatter, ExcelTemplateV1.WORK_SECTION,
            0, MAX_LAYOUT_ROW, MAIN_LAST_COLUMN).getRow();
        int rewardRow = requireUnique(main, formatter, ExcelTemplateV1.REWARD_SECTION,
            0, MAX_LAYOUT_ROW, MAIN_LAST_COLUMN).getRow();
        int familyHeaderRow = requireUnique(main, formatter, ExcelTemplateV1.FAMILY_SECTION,
            0, MAX_LAYOUT_ROW, MAIN_LAST_COLUMN).getRow();
        int declarationValueRow = requireUniqueContaining(main, formatter, List.of("承诺人", "时间"),
            familyHeaderRow + 1, MAX_LAYOUT_ROW, MAIN_LAST_COLUMN).getRow();

        if (!(educationRow < workRow && workRow < rewardRow && rewardRow < familyHeaderRow
            && familyHeaderRow < declarationValueRow)) {
            throw invalid("模板区块顺序不正确");
        }
        requireAt(main, formatter, familyHeaderRow, 1, "称谓");
        requireAt(main, formatter, familyHeaderRow, 2, "姓名");
        String familyDateHeader = normalized(cellText(main, familyHeaderRow, 3, formatter));
        if (!familyDateHeader.equals("年龄") && !familyDateHeader.equals("出生年月")) {
            throw invalid("家庭成员表头不匹配：D" + (familyHeaderRow + 1));
        }
        requireAt(main, formatter, familyHeaderRow, 4, "政治面貌");
        requireAt(main, formatter, familyHeaderRow, 5, "工作单位及职务");

        int helperHeaderRow = requireUnique(helper, formatter, ExcelTemplateV1.FAMILY_SECTION,
            0, MAX_LAYOUT_ROW, HELPER_LAST_COLUMN).getRow();
        requireAt(helper, formatter, helperHeaderRow, 1, "称谓");
        requireAt(helper, formatter, helperHeaderRow, 2, "姓名");
        requireAt(helper, formatter, helperHeaderRow, 3, "出生年月");

        return new WorkbookLayoutV1(main, helper, educationRow, workRow, rewardRow, familyHeaderRow,
            declarationValueRow, helperHeaderRow, Map.copyOf(scalarValues));
    }

    CellAddress scalar(String key) {
        CellAddress address = scalarValues.get(key);
        if (address == null) throw new IllegalArgumentException("Unknown scalar key: " + key);
        return address;
    }

    private static void resolveEducationSummary(XSSFSheet sheet, DataFormatter formatter,
                                                Map<String, CellAddress> values) {
        CellAddress fullTime = requireUnique(sheet, formatter, "全日制", 0, TOP_LAST_ROW, MAIN_LAST_COLUMN);
        CellAddress inService = requireUnique(sheet, formatter, "在职", 0, TOP_LAST_ROW, MAIN_LAST_COLUMN);
        if (fullTime.getColumn() >= MAIN_LAST_COLUMN || inService.getColumn() >= MAIN_LAST_COLUMN) {
            throw invalid("学历标签没有对应值区域");
        }
        values.put("fullTimeEducation", rightOf(fullTime));
        values.put("inServiceEducation", rightOf(inService));
        values.put("fullTimeDegree", new CellAddress(fullTime.getRow() + 1, fullTime.getColumn() + 1));
        values.put("inServiceDegree", new CellAddress(inService.getRow() + 1, inService.getColumn() + 1));
        values.put("fullTimeSchool", valueRightOfLabelOnRow(sheet, formatter, "毕业院校", fullTime.getRow()));
        values.put("inServiceSchool", valueRightOfLabelOnRow(sheet, formatter, "毕业院校", inService.getRow()));
        values.put("fullTimeMajor", valueRightOfLabelOnRow(sheet, formatter, "系及专业", fullTime.getRow() + 1));
        values.put("inServiceMajor", valueRightOfLabelOnRow(sheet, formatter, "系及专业", inService.getRow() + 1));
    }

    private static CellAddress valueRightOfLabelOnRow(XSSFSheet sheet, DataFormatter formatter,
                                                       String label, int row) {
        CellAddress address = requireUnique(sheet, formatter, label, row, row, MAIN_LAST_COLUMN);
        if (address.getColumn() >= MAIN_LAST_COLUMN) throw invalid("模板标签没有对应值区域：" + label);
        return rightOf(address);
    }

    private static CellAddress rightOf(CellAddress address) {
        return new CellAddress(address.getRow(), address.getColumn() + 1);
    }

    private static CellAddress requireUnique(XSSFSheet sheet, DataFormatter formatter, String expected,
                                             int firstRow, int lastRow, int lastColumn) {
        String target = normalized(expected);
        List<CellAddress> matches = new ArrayList<>();
        int effectiveLast = Math.min(Math.min(lastRow, MAX_LAYOUT_ROW), sheet.getLastRowNum());
        for (int rowIndex = Math.max(0, firstRow); rowIndex <= effectiveLast; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int column = 0; column <= lastColumn; column++) {
                Cell cell = row.getCell(column);
                if (cell != null && target.equals(normalized(formatter.formatCellValue(cell)))) {
                    matches.add(new CellAddress(rowIndex, column));
                }
            }
        }
        if (matches.size() != 1) {
            throw invalid(matches.isEmpty() ? "缺少模板标签：" + expected : "模板标签重复：" + expected);
        }
        return matches.getFirst();
    }

    private static CellAddress requireUniqueContaining(XSSFSheet sheet, DataFormatter formatter,
                                                        List<String> fragments, int firstRow,
                                                        int lastRow, int lastColumn) {
        List<CellAddress> matches = new ArrayList<>();
        int effectiveLast = Math.min(Math.min(lastRow, MAX_LAYOUT_ROW), sheet.getLastRowNum());
        for (int rowIndex = Math.max(0, firstRow); rowIndex <= effectiveLast; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int column = 0; column <= lastColumn; column++) {
                Cell cell = row.getCell(column);
                if (cell == null) continue;
                String actual = normalized(formatter.formatCellValue(cell));
                if (fragments.stream().map(WorkbookLayoutV1::normalized).allMatch(actual::contains)) {
                    matches.add(new CellAddress(rowIndex, column));
                }
            }
        }
        if (matches.size() != 1) {
            throw invalid(matches.isEmpty() ? "缺少声明区域" : "声明区域重复");
        }
        return matches.getFirst();
    }

    private static void requireAt(XSSFSheet sheet, DataFormatter formatter, int row, int column,
                                  String expected) {
        if (!normalized(expected).equals(normalized(cellText(sheet, row, column, formatter)))) {
            throw invalid("模板表头不匹配：" + new CellAddress(row, column).formatAsString());
        }
    }

    private static String cellText(XSSFSheet sheet, int rowIndex, int column, DataFormatter formatter) {
        Row row = sheet.getRow(rowIndex);
        Cell cell = row == null ? null : row.getCell(column);
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    static String normalized(String value) {
        String compatible = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(compatible.length());
        compatible.codePoints()
            .filter(codePoint -> !Character.isWhitespace(codePoint) && codePoint != ':' && codePoint != '：')
            .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.TEMPLATE_INVALID, message);
    }
}
