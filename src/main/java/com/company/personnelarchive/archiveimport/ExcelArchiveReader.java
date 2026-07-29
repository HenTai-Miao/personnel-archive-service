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
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy.M.d"),
        DateTimeFormatter.ofPattern("yyyy年M月d日")
    );
    private static final Pattern MONTH = Pattern.compile("^(\\d{4})(?:[./-]|年)(\\d{1,2})(?:月)?$");
    private static final Pattern MONTH_RANGE = Pattern.compile(
        "^(\\d{4})(?:[./-]|年)(\\d{1,2})(?:月)?\\s*(?:-|—|–|~|至)\\s*(?:(\\d{4})(?:[./-]|年)(\\d{1,2})(?:月)?|至今|现在|今)$");
    private static final Pattern HELPER_DATE_REFERENCE = Pattern.compile(
        "(?:'" + Pattern.quote(ExcelTemplateV1.AGE_SHEET) + "'|" + Pattern.quote(ExcelTemplateV1.AGE_SHEET)
            + ")!\\$?D\\$?(\\d+)");

    static {
        // Apache POI 的限制是进程级设置，必须在打开任何用户工作簿之前完成，以抵御 ZIP 炸弹。
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(32L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(8L * 1024 * 1024);
    }

    private final ArchiveProperties properties;
    private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

    public ExcelArchiveReader(ArchiveProperties properties) {
        this.properties = properties;
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
        List<ArchiveDocument.OrderedText> education = readTextRows(sheet, layout.educationRow(), layout.workRow());
        List<ArchiveDocument.OrderedText> work = readTextRows(sheet, layout.workRow(), layout.rewardRow());
        List<ArchiveDocument.OrderedText> rewards = readTextRows(sheet, layout.rewardRow(), layout.familyHeaderRow());
        List<ArchiveDocument.FamilyMember> family = readFamily(layout);
        ArchiveDocument.Photo photo = extractPhoto(workbook, filename);
        String declaration = joinNonBlank(sheet, layout.declarationValueRow(), 0, 8);
        ArchiveDocument document = new ArchiveDocument(
            text(sheet, layout.scalar("employeeNo")), text(sheet, layout.scalar("name")),
            text(sheet, layout.scalar("identityCard")), text(sheet, layout.scalar("gender")),
            parseDate(sheet, layout.scalar("birthDate")), text(sheet, layout.scalar("ethnicity")),
            text(sheet, layout.scalar("nativePlace")), text(sheet, layout.scalar("birthPlace")),
            text(sheet, layout.scalar("politicalStatus")), parseDate(sheet, layout.scalar("politicalJoinDate")),
            text(sheet, layout.scalar("maritalStatus")), text(sheet, layout.scalar("healthStatus")),
            text(sheet, layout.scalar("address")), text(sheet, layout.scalar("postalCode")),
            text(sheet, layout.scalar("phone")),
            new ArchiveDocument.Employment(text(sheet, layout.scalar("organizationDepartment")),
                parseDate(sheet, layout.scalar("workStartDate")), parseDate(sheet, layout.scalar("dispatchDate")),
                text(sheet, layout.scalar("position")), text(sheet, layout.scalar("titleSkill")),
                parseDate(sheet, layout.scalar("titleObtainedDate"))),
            new ArchiveDocument.EducationSummary(text(sheet, layout.scalar("fullTimeSchool")),
                text(sheet, layout.scalar("fullTimeEducation")), text(sheet, layout.scalar("fullTimeMajor")),
                text(sheet, layout.scalar("fullTimeDegree"))),
            new ArchiveDocument.EducationSummary(text(sheet, layout.scalar("inServiceSchool")),
                text(sheet, layout.scalar("inServiceEducation")), text(sheet, layout.scalar("inServiceMajor")),
                text(sheet, layout.scalar("inServiceDegree"))),
            education, work, rewards, family, photo, parseDeclarationSigner(declaration),
            parseDeclarationDate(declaration)
        );
        document.validateCapacity();
        return document;
    }

    private List<ArchiveDocument.OrderedText> readTextRows(XSSFSheet sheet, int first, int lastExclusive) {
        List<ArchiveDocument.OrderedText> rows = new ArrayList<>();
        for (int row = first; row < lastExclusive; row++) {
            String left = joinNonBlank(sheet, row, 1, 2);
            String right = joinNonBlank(sheet, row, 3, 8);
            // raw_text 和原始行序是无损导出的依据；解析出的日期只用于结构化查询，不能替代原文。
            String raw = left.isBlank() ? right : right.isBlank() ? left : left + "\t" + right;
            if (!raw.isBlank()) {
                DateRange range = parseMonthRange(left);
                rows.add(new ArchiveDocument.OrderedText(rows.size(), left, right, raw, range.start(), range.end()));
            }
        }
        return rows;
    }

    private List<ArchiveDocument.FamilyMember> readFamily(WorkbookLayoutV1 layout) {
        List<HelperFamilyRow> helperRows = readHelperRows(layout);
        Set<Integer> usedHelperRows = new HashSet<>();
        List<ArchiveDocument.FamilyMember> family = new ArrayList<>();
        for (int row = layout.familyHeaderRow() + 1; row < layout.declarationValueRow(); row++) {
            String relationship = text(layout.main(), row, 1);
            String name = text(layout.main(), row, 2);
            String age = text(layout.main(), row, 3);
            String political = text(layout.main(), row, 4);
            String workplace = joinNonBlank(layout.main(), row, 5, 8);
            if (String.join("", relationship, name, age, political, workplace).isBlank()
                && !isFormula(layout.main(), row, 3)) {
                continue;
            }
            LocalDate birthDate = resolveFamilyBirthDate(layout, row, relationship, name, helperRows, usedHelperRows);
            family.add(new ArchiveDocument.FamilyMember(family.size(), relationship, name, birthDate,
                political, workplace));
        }
        return family;
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

    private LocalDate resolveFamilyBirthDate(WorkbookLayoutV1 layout, int mainRow, String relationship,
                                             String name, List<HelperFamilyRow> helpers,
                                             Set<Integer> usedHelperRows) {
        Cell ageCell = cell(layout.main(), mainRow, 3);
        if (ageCell != null && ageCell.getCellType() == CellType.FORMULA) {
            var reference = HELPER_DATE_REFERENCE.matcher(ageCell.getCellFormula());
            if (reference.find()) {
                int referencedRow = Integer.parseInt(reference.group(1)) - 1;
                return helpers.stream().filter(helper -> helper.row() == referencedRow).findFirst()
                    .map(helper -> {
                        usedHelperRows.add(helper.row());
                        return parseDate(layout.helper(), helper.dateAddress());
                    }).orElse(null);
            }
        }

        String relationshipKey = WorkbookLayoutV1.normalized(relationship);
        String nameKey = WorkbookLayoutV1.normalized(name);
        if (relationshipKey.isBlank() && nameKey.isBlank()) return null;
        List<HelperFamilyRow> matches = helpers.stream()
            .filter(helper -> !usedHelperRows.contains(helper.row()))
            .filter(helper -> relationshipKey.equals(WorkbookLayoutV1.normalized(helper.relationship())))
            .filter(helper -> nameKey.equals(WorkbookLayoutV1.normalized(helper.name())))
            .toList();
        if (matches.size() != 1) return null;
        HelperFamilyRow match = matches.getFirst();
        usedHelperRows.add(match.row());
        return parseDate(layout.helper(), match.dateAddress());
    }

    private record HelperFamilyRow(int row, String relationship, String name, CellAddress dateAddress) {}

    private static DateRange parseMonthRange(String raw) {
        if (raw == null || raw.isBlank()) return DateRange.EMPTY;
        var range = MONTH_RANGE.matcher(raw.strip());
        if (range.matches()) {
            try {
                LocalDate start = LocalDate.of(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)), 1);
                LocalDate end = range.group(3) == null ? null
                    : LocalDate.of(Integer.parseInt(range.group(3)), Integer.parseInt(range.group(4)), 1);
                return new DateRange(start, end);
            } catch (DateTimeException ignored) {
                return DateRange.EMPTY;
            }
        }
        var month = MONTH.matcher(raw.strip());
        if (!month.matches()) return DateRange.EMPTY;
        try {
            return new DateRange(LocalDate.of(Integer.parseInt(month.group(1)), Integer.parseInt(month.group(2)), 1), null);
        } catch (DateTimeException ignored) {
            return DateRange.EMPTY;
        }
    }

    private record DateRange(LocalDate start, LocalDate end) {
        private static final DateRange EMPTY = new DateRange(null, null);
    }

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

    private LocalDate parseDate(XSSFSheet sheet, CellAddress address) {
        Cell cell = cell(sheet, address.getRow(), address.getColumn());
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if ((cell.getCellType() == CellType.NUMERIC
            || cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC)
            && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String raw = formatter.formatCellValue(cell).strip();
        if (raw.isEmpty()) return null;
        for (DateTimeFormatter dateFormatter : DATE_FORMATTERS) {
            try { return LocalDate.parse(raw, dateFormatter); } catch (DateTimeParseException ignored) { }
        }
        var month = MONTH.matcher(raw);
        if (month.matches()) {
            try {
                return LocalDate.of(Integer.parseInt(month.group(1)), Integer.parseInt(month.group(2)), 1);
            } catch (DateTimeException ignored) {
                // Fall through to the same safe coordinate-only validation message.
            }
        }
        throw new BusinessException(ErrorCode.TEMPLATE_INVALID, address.formatAsString() + " 日期格式不正确");
    }

    private static String parseDeclarationSigner(String raw) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("承诺人[：:]\\s*([^\\s时间]+)").matcher(raw == null ? "" : raw);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static LocalDate parseDeclarationDate(String raw) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日").matcher(raw == null ? "" : raw);
        return matcher.find() ? LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))) : null;
    }
}
