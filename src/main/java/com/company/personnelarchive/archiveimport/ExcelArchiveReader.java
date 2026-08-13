package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.usermodel.XSSFPictureData;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

@Component
/**
 * V1 人员档案工作簿的受信边界。
 *
 * <p>上传内容按敌对输入处理：先限制 ZIP 解压，再用精确标签解析工作表结构，最后映射
 * 用户数据。不能依赖模板内已损坏的命名范围，也不能根据模糊文字或剩余行猜测字段归属。</p>
 */
public class ExcelArchiveReader {
    private static final byte[] ZIP_SIGNATURE = {0x50, 0x4b, 0x03, 0x04};
    private static final Pattern DECLARATION_TIME = Pattern.compile("时间[：:]\\s*(.*?)\\s*$");
    private static final Pattern EMPTY_DECLARATION_DATE =
        Pattern.compile("^[\\s\\p{Zs}]*年[\\s\\p{Zs}]*月[\\s\\p{Zs}]*日[\\s\\p{Zs}]*$");
    private static final String DATE_GUIDANCE =
        "必须填写明确且有效的日期，例如 2024、2024-05 或 2024-05-03";
    private static final String DATE_RANGE_GUIDANCE =
        "必须填写明确且有效的日期或日期区间，例如 2024、1996.09-1999.06 或 2019.03至今";
    private static final String AGE_GUIDANCE = "必须填写精确年龄，例如 35 或 三十五岁";
    private static final Pattern AGE_FORMULA = Pattern.compile(
        "^\\s*DATEDIF\\s*\\(\\s*(?:'" + Pattern.quote(ExcelTemplateV1.AGE_SHEET) + "'|"
            + Pattern.quote(ExcelTemplateV1.AGE_SHEET)
            + ")!\\$?D\\$?(\\d+)\\s*,\\s*TODAY\\s*\\(\\s*\\)\\s*,\\s*\"y\"\\s*\\)\\s*$",
        Pattern.CASE_INSENSITIVE);
    static {
        // Apache POI 的限制是进程级设置，必须在打开任何用户工作簿之前完成，以抵御 ZIP 炸弹。
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(32L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(8L * 1024 * 1024);
    }

    private final ArchiveProperties properties;
    private final Clock clock;
    private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

    public ExcelArchiveReader(ArchiveProperties properties) {
        this(properties, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    @Autowired
    public ExcelArchiveReader(ArchiveProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // 除模板的精确年龄公式外，公式仍只读文件缓存，避免执行不受信任的任意表达式。
        formatter.setUseCachedValuesForFormulaCells(true);
    }

    public ArchiveDocument read(InputStream source, long contentLength, String originalFilename) {
        // 扩展名只用于早期反馈；ZIP 签名和模板结构才是后续的真实内容校验。
        if (contentLength > properties.excel().maxFileBytes()) throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        try (BufferedInputStream input = new BufferedInputStream(source)) {
            input.mark(8);
            byte[] signature = input.readNBytes(4);
            input.reset();
            if (!java.util.Arrays.equals(signature, ZIP_SIGNATURE)) throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
            try (XSSFWorkbook workbook = new XSSFWorkbook(input)) {
                // 先确认精确标签及区块顺序，避免在非业务模板中读取看似相同位置的无关数据。
                validateStructure(workbook);
                return map(workbook, originalFilename);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "无法读取或校验 Excel 文件");
        }
    }

    public void validateStructure(XSSFWorkbook workbook) {
        WorkbookLayoutV1.resolve(workbook, formatter);
    }

    private ArchiveDocument map(XSSFWorkbook workbook, String filename) {
        WorkbookLayoutV1 layout = WorkbookLayoutV1.resolve(workbook, formatter);
        XSSFSheet sheet = layout.main();
        WorkbookFieldIssues issues = new WorkbookFieldIssues();
        List<ArchiveDocument.OrderedText> education = readTextRows(
            sheet, layout.educationRow(), layout.workRow(), "主要学历", issues);
        List<ArchiveDocument.OrderedText> work = readTextRows(
            sheet, layout.workRow(), layout.rewardRow(), "工作经历", issues);
        List<ArchiveDocument.OrderedText> rewards = readTextRows(
            sheet, layout.rewardRow(), layout.familyHeaderRow(), "奖惩情况", issues);
        List<ArchiveDocument.FamilyMember> family = readFamily(layout, issues);
        ArchiveDocument.Photo photo = extractPhoto(workbook, filename);
        String declaration = joinNonBlank(sheet, layout.declarationValueRow(), 0, 8);
        ArchiveDocument document = new ArchiveDocument(
            text(sheet, layout.scalar("employeeNo")), text(sheet, layout.scalar("name")),
            text(sheet, layout.scalar("identityCard")), text(sheet, layout.scalar("gender")),
            parseDate(sheet, layout.scalar("birthDate"), "出生年月", issues, true),
            text(sheet, layout.scalar("ethnicity")),
            text(sheet, layout.scalar("nativePlace")), text(sheet, layout.scalar("birthPlace")),
            text(sheet, layout.scalar("politicalStatus")),
            parseDate(sheet, layout.scalar("politicalJoinDate"), "入党团时间", issues, false),
            text(sheet, layout.scalar("maritalStatus")), text(sheet, layout.scalar("healthStatus")),
            text(sheet, layout.scalar("address")), text(sheet, layout.scalar("postalCode")),
            text(sheet, layout.scalar("phone")),
            new ArchiveDocument.Employment(text(sheet, layout.scalar("organizationDepartment")),
                parseDate(sheet, layout.scalar("workStartDate"), "参加工作时间", issues, false),
                parseDate(sheet, layout.scalar("dispatchDate"), "派遣时间", issues, false),
                text(sheet, layout.scalar("position")), text(sheet, layout.scalar("titleSkill")),
                parseDate(sheet, layout.scalar("titleObtainedDate"), "职称取得时间", issues, false)),
            new ArchiveDocument.EducationSummary(text(sheet, layout.scalar("fullTimeSchool")),
                text(sheet, layout.scalar("fullTimeEducation")), text(sheet, layout.scalar("fullTimeMajor")),
                text(sheet, layout.scalar("fullTimeDegree"))),
            new ArchiveDocument.EducationSummary(text(sheet, layout.scalar("inServiceSchool")),
                text(sheet, layout.scalar("inServiceEducation")), text(sheet, layout.scalar("inServiceMajor")),
                text(sheet, layout.scalar("inServiceDegree"))),
            education, work, rewards, family, photo, parseDeclarationSigner(declaration),
            parseDeclarationDate(sheet, declaration, new CellAddress(layout.declarationValueRow(), 1), issues)
        );
        issues.throwIfAny();
        document.validateCapacity();
        return document;
    }

    private List<ArchiveDocument.OrderedText> readTextRows(
        XSSFSheet sheet, int first, int lastExclusive, String sectionName, WorkbookFieldIssues issues
    ) {
        List<ArchiveDocument.OrderedText> rows = new ArrayList<>();
        for (int row = first; row < lastExclusive; row++) {
            String left = joinNonBlank(sheet, row, 1, 2);
            String right = joinNonBlank(sheet, row, 3, 8);
            String raw = left.isBlank() ? right : right.isBlank() ? left : left + "\t" + right;
            if (!raw.isBlank()) {
                String normalizedLeft = left;
                String start = null;
                String end = null;
                if (!left.isBlank()) {
                    try {
                        var range = SourceValueNormalizerV4.normalizeDateRange(left);
                        normalizedLeft = range.text();
                        start = blankToNull(range.start().text());
                        end = blankToNull(range.end().text());
                    } catch (SourceValueNormalizationException exception) {
                        issues.add(issue("DATE_NOT_EXACT", sectionName + "第 " + (rows.size() + 1) + " 行日期",
                            sheet, new CellAddress(row, 1), DATE_RANGE_GUIDANCE));
                    }
                }
                String normalizedRaw = normalizedLeft.isBlank()
                    ? right : right.isBlank() ? normalizedLeft : normalizedLeft + "\t" + right;
                rows.add(new ArchiveDocument.OrderedText(
                    rows.size(), normalizedLeft, right, normalizedRaw, start, end));
            }
        }
        return rows;
    }

    private List<ArchiveDocument.FamilyMember> readFamily(
        WorkbookLayoutV1 layout, WorkbookFieldIssues issues
    ) {
        List<HelperFamilyRow> helperRows = readHelperRows(layout);
        Set<Integer> usedHelperRows = new HashSet<>();
        List<ArchiveDocument.FamilyMember> family = new ArrayList<>();
        for (int row = layout.familyHeaderRow() + 1; row < layout.declarationValueRow(); row++) {
            String relationship = text(layout.main(), row, 1);
            String name = text(layout.main(), row, 2);
            String political = text(layout.main(), row, 4);
            String workplace = joinNonBlank(layout.main(), row, 5, 8);
            int memberNumber = family.size() + 1;
            String age = familyAgeText(layout, row, memberNumber, issues);
            if (String.join("", relationship, name, age, political, workplace).isBlank()
                && !isFormula(layout.main(), row, 3)) {
                continue;
            }
            SourceDate birthDate = resolveFamilyBirthDate(
                layout, row, memberNumber, relationship, name, helperRows, usedHelperRows, issues);
            family.add(new ArchiveDocument.FamilyMember(family.size(), relationship, name, birthDate,
                age, political, workplace));
        }
        return family;
    }

    private String familyAgeText(
        WorkbookLayoutV1 layout, int mainRow, int memberNumber, WorkbookFieldIssues issues
    ) {
        Cell ageCell = cell(layout.main(), mainRow, 3);
        String savedValue = text(layout.main(), mainRow, 3);
        String candidate = savedValue;
        if (ageCell != null && ageCell.getCellType() == CellType.FORMULA) {
            var formula = AGE_FORMULA.matcher(ageCell.getCellFormula());
            if (formula.matches()) {
                int helperRow = Integer.parseInt(formula.group(1)) - 1;
                LocalDate startDate = formulaStartDate(layout, helperRow);
                LocalDate today = LocalDate.now(clock);
                if (startDate != null && !startDate.isAfter(today)) {
                    // 只执行模板明确允许的 DATEDIF，并立即冻结为标准整数文本。
                    candidate = Integer.toString(Period.between(startDate, today).getYears());
                }
            }
        }
        try {
            return SourceValueNormalizerV3.normalizeAge(candidate).text();
        } catch (SourceValueNormalizationException exception) {
            issues.add(issue("AGE_NOT_EXACT", "家庭成员第 " + memberNumber + " 行年龄",
                layout.main(), new CellAddress(mainRow, 3), AGE_GUIDANCE));
            return "";
        }
    }

    private static LocalDate formulaStartDate(WorkbookLayoutV1 layout, int helperRow) {
        Cell cell = cell(layout.helper(), helperRow, 3);
        // Excel 将公式直接引用的真正空单元格作为序列值 0；这也是用户打开文件时看到的结果。
        if (cell == null || cell.getCellType() == CellType.BLANK) return LocalDate.of(1899, 12, 30);
        CellType valueType = cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (valueType == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (!DateUtil.isValidExcelDate(value)) return null;
            return DateUtil.getLocalDateTime(value, layout.main().getWorkbook().isDate1904()).toLocalDate();
        }
        if (valueType != CellType.STRING) return null;
        String raw = cell.getStringCellValue().strip();
        if (raw.isEmpty()) return LocalDate.of(1899, 12, 30);
        try {
            return SourceValueParserV2.parseDate(raw).exactDate();
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private List<HelperFamilyRow> readHelperRows(WorkbookLayoutV1 layout) {
        List<HelperFamilyRow> rows = new ArrayList<>();
        int last = Math.min(layout.helper().getLastRowNum(), 10_000);
        for (int row = layout.helperHeaderRow() + 1; row <= last; row++) {
            String relationship = text(layout.helper(), row, 1);
            String name = text(layout.helper(), row, 2);
            String date = text(layout.helper(), row, 3);
            if (!String.join("", relationship, name, date).isBlank() || isFormula(layout.helper(), row, 3)) {
                rows.add(new HelperFamilyRow(row, relationship, name, new CellAddress(row, 3)));
            }
        }
        return rows;
    }

    private SourceDate resolveFamilyBirthDate(
        WorkbookLayoutV1 layout, int mainRow, int memberNumber, String relationship,
        String name, List<HelperFamilyRow> helpers, Set<Integer> usedHelperRows,
        WorkbookFieldIssues issues
    ) {
        Cell ageCell = cell(layout.main(), mainRow, 3);
        if (ageCell != null && ageCell.getCellType() == CellType.FORMULA) {
            var reference = AGE_FORMULA.matcher(ageCell.getCellFormula());
            if (reference.matches()) {
                int referencedRow = Integer.parseInt(reference.group(1)) - 1;
                return helpers.stream().filter(helper -> helper.row() == referencedRow).findFirst()
                    .map(helper -> {
                        usedHelperRows.add(helper.row());
                        return parseDate(layout.helper(), helper.dateAddress(),
                            "家庭成员第 " + memberNumber + " 行出生年月", issues, true);
                    }).orElse(SourceDate.empty());
            }
        }

        String relationshipKey = WorkbookLayoutV1.normalized(relationship);
        String nameKey = WorkbookLayoutV1.normalized(name);
        if (relationshipKey.isBlank() && nameKey.isBlank()) return SourceDate.empty();
        List<HelperFamilyRow> matches = helpers.stream()
            .filter(helper -> !usedHelperRows.contains(helper.row()))
            .filter(helper -> relationshipKey.equals(WorkbookLayoutV1.normalized(helper.relationship())))
            .filter(helper -> nameKey.equals(WorkbookLayoutV1.normalized(helper.name())))
            .toList();
        if (matches.size() != 1) return SourceDate.empty();
        HelperFamilyRow match = matches.getFirst();
        usedHelperRows.add(match.row());
        return parseDate(layout.helper(), match.dateAddress(),
            "家庭成员第 " + memberNumber + " 行出生年月", issues, true);
    }

    private record HelperFamilyRow(int row, String relationship, String name, CellAddress dateAddress) {}

    private ArchiveDocument.Photo extractPhoto(XSSFWorkbook workbook, String filename) {
        XSSFSheet main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
        List<XSSFPicture> pictures = main.getRelations().stream().filter(XSSFDrawing.class::isInstance)
            .map(XSSFDrawing.class::cast).flatMap(drawing -> drawing.getShapes().stream())
            .filter(XSSFPicture.class::isInstance).map(XSSFPicture.class::cast).toList();
        if (pictures.isEmpty() && workbook.getAllPictures().isEmpty()) return null;
        // V1 只支持照片框中的一张图；游离图片或多图会让导出无法确定应还原哪一张。
        if (pictures.size() != 1 || workbook.getAllPictures().size() != 1) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "模板只允许照片区域内的一张图片");
        }
        XSSFPicture shape = pictures.getFirst();
        var anchor = shape.getClientAnchor();
        if (anchor.getCol1() != 8 || anchor.getCol2() < 8 || anchor.getCol2() > 9
            || anchor.getRow1() < 2 || anchor.getRow1() >= 11 || anchor.getRow2() < anchor.getRow1() || anchor.getRow2() > 11
            || anchor.getDx1() < 0 || anchor.getDy1() < 0 || anchor.getDx2() < 0 || anchor.getDy2() < 0
            || anchor.getCol2() == anchor.getCol1() && anchor.getDx2() <= anchor.getDx1()
            || anchor.getRow2() == anchor.getRow1() && anchor.getDy2() <= anchor.getDy1()) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "图片必须位于照片区域");
        }
        XSSFPictureData picture = shape.getPictureData();
        byte[] bytes = picture.getData();
        if (bytes.length > properties.excel().maxPhotoBytes()) throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "照片超过大小限制");
        String mime = picture.getMimeType();
        if (!Set.of("image/jpeg", "image/png").contains(mime)) throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "照片仅支持 JPEG 或 PNG");
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "照片内容无效");
            }
            // 除原始二进制外还保存完整锚点；只保存图片尺寸无法重现用户工作簿中的摆放方式。
            var photoAnchor = new ArchiveDocument.PhotoAnchor(
                anchor.getCol1(), anchor.getRow1(), anchor.getDx1(), anchor.getDy1(),
                anchor.getCol2(), anchor.getRow2(), anchor.getDx2(), anchor.getDy2(),
                readAnchorType(shape));
            return new ArchiveDocument.Photo(bytes, mime, filename + "-photo." + picture.suggestFileExtension(),
                image.getWidth(), image.getHeight(), photoAnchor);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "照片内容无效");
        }
    }

    private static String readAnchorType(XSSFPicture picture) {
        var drawing = picture.getDrawing().getCTDrawing();
        for (var anchor : drawing.getTwoCellAnchorList()) {
            if (anchor.getPic() == picture.getCTPicture()) {
                String editAs = anchor.getEditAs() == null ? "twoCell" : anchor.getEditAs().toString();
                return switch (editAs) {
                    case "absolute" -> "DONT_MOVE_AND_RESIZE";
                    case "oneCell" -> "MOVE_DONT_RESIZE";
                    default -> "MOVE_AND_RESIZE";
                };
            }
        }
        for (var anchor : drawing.getOneCellAnchorList()) {
            if (anchor.getPic() == picture.getCTPicture()) return "MOVE_DONT_RESIZE";
        }
        for (var anchor : drawing.getAbsoluteAnchorList()) {
            if (anchor.getPic() == picture.getCTPicture()) return "DONT_MOVE_AND_RESIZE";
        }
        return picture.getClientAnchor().getAnchorType().name();
    }

    private String text(XSSFSheet sheet, CellAddress address) {
        Cell cell = cell(sheet, address.getRow(), address.getColumn());
        return cell == null ? "" : formatter.formatCellValue(cell).strip();
    }

    private String text(XSSFSheet sheet, int row, int column) {
        return text(sheet, new CellAddress(row, column));
    }

    private String joinNonBlank(XSSFSheet sheet, int row, int firstColumn, int lastColumn) {
        List<String> values = new ArrayList<>();
        for (int column = firstColumn; column <= lastColumn; column++) {
            String value = text(sheet, row, column);
            if (!value.isBlank()) values.add(value);
        }
        return String.join("\t", values);
    }

    private static Cell cell(XSSFSheet sheet, int row, int column) {
        return sheet.getRow(row) == null ? null : sheet.getRow(row).getCell(column);
    }

    private static boolean isFormula(XSSFSheet sheet, int row, int column) {
        Cell cell = cell(sheet, row, column);
        return cell != null && cell.getCellType() == CellType.FORMULA;
    }

    private SourceDate parseDate(
        XSSFSheet sheet, CellAddress address, String fieldName,
        WorkbookFieldIssues issues, boolean rejectFuture
    ) {
        Cell cell = cell(sheet, address.getRow(), address.getColumn());
        if (cell == null || cell.getCellType() == CellType.BLANK) return SourceDate.empty();
        String raw = formatter.formatCellValue(cell).strip();
        if (raw.isEmpty()) return SourceDate.empty();
        SourceDate parsed = numericDateAtDisplayedPrecision(cell);
        if (parsed == null) {
            try {
                parsed = SourceValueNormalizerV3.normalizeDate(raw);
            } catch (SourceValueNormalizationException exception) {
                issues.add(issue("DATE_NOT_EXACT", fieldName, sheet, address, DATE_GUIDANCE));
                return SourceDate.empty();
            }
        }
        if (rejectFuture && parsed.isAfter(LocalDate.now(clock))) {
            issues.add(issue("DATE_IN_FUTURE", fieldName, sheet, address, "出生日期不能晚于当前日期"));
        }
        return parsed;
    }

    private static SourceDate numericDateAtDisplayedPrecision(Cell cell) {
        boolean numeric = cell.getCellType() == CellType.NUMERIC
            || cell.getCellType() == CellType.FORMULA
                && cell.getCachedFormulaResultType() == CellType.NUMERIC;
        if (!numeric || !DateUtil.isCellDateFormatted(cell)) return null;

        // 格式只决定用户实际看见的精度；年月日的真实值仍来自 Excel 数值序列，避免两位年份歧义。
        String format = cell.getCellStyle().getDataFormatString().split(";", 2)[0]
            .replaceAll("\"[^\"]*\"|\\[[^\\]]*]|\\\\.|_.|\\*.", "")
            .toLowerCase(Locale.ROOT);
        if (!format.contains("y")) return null;

        LocalDate date = cell.getLocalDateTimeCellValue().toLocalDate();
        if (format.contains("d")) return SourceDate.exact(date.toString(), date);
        String withoutMinutes = format
            .replaceAll("h+\\s*:?\\s*m+", "")
            .replaceAll("m+\\s*:?\\s*s+", "");
        if (withoutMinutes.contains("m")) {
            YearMonth month = YearMonth.from(date);
            return SourceDate.month(month.toString(), month);
        }
        Year year = Year.from(date);
        return SourceDate.year(year.toString(), year);
    }

    private static String parseDeclarationSigner(String raw) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("承诺人[：:]\\s*([^\\s时间]+)").matcher(raw == null ? "" : raw);
        return matcher.find() ? matcher.group(1) : "";
    }

    private SourceDate parseDeclarationDate(
        XSSFSheet sheet, String raw, CellAddress address, WorkbookFieldIssues issues
    ) {
        java.util.regex.Matcher matcher = DECLARATION_TIME.matcher(raw == null ? "" : raw);
        if (!matcher.find() || matcher.group(1).isBlank()
            || EMPTY_DECLARATION_DATE.matcher(matcher.group(1)).matches()) {
            return SourceDate.empty();
        }
        try {
            // 必须验证“时间：”后的完整内容；截取合法前缀会掩盖尾部错字并改变来源语义。
            return SourceValueNormalizerV3.normalizeDate(matcher.group(1));
        } catch (SourceValueNormalizationException exception) {
            issues.add(issue("DATE_NOT_EXACT", "声明日期", sheet, address, DATE_GUIDANCE));
            return SourceDate.empty();
        }
    }

    private static WorkbookFieldIssue issue(
        String code, String fieldName, XSSFSheet sheet, CellAddress address, String guidance
    ) {
        return new WorkbookFieldIssue(
            code, fieldName, sheet.getSheetName(), address.formatAsString(), guidance);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
