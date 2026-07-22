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
import org.apache.poi.ss.util.CellRangeAddress;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

@Component
/**
 * V1 人员档案工作簿的受信边界。
 *
 * <p>上传内容按敌对输入处理：先限制 ZIP 解压，再验证工作表结构，最后才按显式坐标映射
 * 用户数据。不能改成依赖模板内已损坏的命名范围，也不能在这里静默截断超容量行。</p>
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
                // 先确认固定标签与合并区域，避免在错误版本模板上读取同一坐标的无关数据。
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
        XSSFSheet sheet = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
        XSSFSheet ageSheet = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
        if (sheet == null || ageSheet == null) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "缺少 V1 工作表");
        }
        ExcelTemplateV1.LABELS.forEach((coordinate, expected) -> {
            String actual = text(sheet, coordinate).replace("\r\n", "\n");
            if (!expected.equals(actual)) {
                throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "模板标签不匹配：" + coordinate);
            }
        });
        Set<String> merges = sheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).collect(Collectors.toSet());
        if (!merges.containsAll(ExcelTemplateV1.REQUIRED_MERGES)) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "模板合并区域不匹配");
        }
        ExcelTemplateV1.AGE_LABELS.forEach((coordinate, expected) -> {
            if (!expected.equals(text(ageSheet, coordinate).replace("\r\n", "\n"))) {
                throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "年龄计算表标签不匹配：" + coordinate);
            }
        });
        if (ageSheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).noneMatch("A1:A7"::equals)) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "年龄计算表合并区域不匹配");
        }
    }

    private ArchiveDocument map(XSSFWorkbook workbook, String filename) {
        // 坐标映射属于 V1 协议的一部分；模板换版时应新增版本映射，而不是猜测字段位置。
        XSSFSheet sheet = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
        XSSFSheet ageSheet = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
        List<ArchiveDocument.OrderedText> education = readTextRows(sheet, 13, 17);
        List<ArchiveDocument.OrderedText> work = readTextRows(sheet, 18, 33);
        List<ArchiveDocument.OrderedText> rewards = readTextRows(sheet, 34, 37);
        List<ArchiveDocument.FamilyMember> family = new ArrayList<>();
        for (int row = 39; row <= 44; row++) {
            String relationship = text(sheet, "B" + row);
            String name = text(sheet, "C" + row);
            int helperRow = row - 37;
            String helperRelationship = text(ageSheet, "B" + helperRow);
            String helperName = text(ageSheet, "C" + helperRow);
            String political = text(sheet, "E" + row);
            String workplace = text(sheet, "F" + row);
            if (!String.join("", relationship, name, helperRelationship, helperName, political, workplace,
                text(ageSheet, "D" + helperRow)).isBlank()) {
                // 主表负责展示，辅助表保存出生日期；两边已有值时必须一致，不能悄悄任选其一。
                if (!helperRelationship.isBlank() && !relationship.isBlank() && !helperRelationship.equals(relationship)
                    || !helperName.isBlank() && !name.isBlank() && !helperName.equals(name)) {
                    throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "家庭成员主表与年龄计算表不一致：第 " + (row - 38) + " 行");
                }
                relationship = relationship.isBlank() ? helperRelationship : relationship;
                name = name.isBlank() ? helperName : name;
                family.add(new ArchiveDocument.FamilyMember(row - 39, relationship, name,
                    parseDate(ageSheet, "D" + helperRow), political, workplace));
            }
        }
        ArchiveDocument.Photo photo = extractPhoto(workbook, filename);
        ArchiveDocument document = new ArchiveDocument(
            text(sheet, "B2"), text(sheet, "B3"), text(sheet, "B7"), text(sheet, "D3"), parseDate(sheet, "F3"),
            text(sheet, "H3"), text(sheet, "B4"), text(sheet, "D4"), text(sheet, "F4"), parseDate(sheet, "H4"),
            text(sheet, "F7"), text(sheet, "H7"), text(sheet, "B12"), text(sheet, "F12"), text(sheet, "H12"),
            new ArchiveDocument.Employment(text(sheet, "B5"), parseDate(sheet, "F5"), parseDate(sheet, "H5"),
                text(sheet, "B6"), text(sheet, "F6"), parseDate(sheet, "H6")),
            new ArchiveDocument.EducationSummary(text(sheet, "F8"), text(sheet, "C8"), text(sheet, "F9"), text(sheet, "C9")),
            new ArchiveDocument.EducationSummary(text(sheet, "F10"), text(sheet, "C10"), text(sheet, "F11"), text(sheet, "C11")),
            education, work, rewards, family, photo, parseDeclarationSigner(text(sheet, "B46")),
            parseDeclarationDate(text(sheet, "B46"))
        );
        document.validateCapacity();
        return document;
    }

    private List<ArchiveDocument.OrderedText> readTextRows(XSSFSheet sheet, int first, int last) {
        List<ArchiveDocument.OrderedText> rows = new ArrayList<>();
        for (int row = first; row <= last; row++) {
            String left = text(sheet, "B" + row);
            String right = text(sheet, "D" + row);
            // raw_text 和原始行序是无损导出的依据；解析出的日期只用于结构化查询，不能替代原文。
            String raw = left.isBlank() ? right : right.isBlank() ? left : left + "\t" + right;
            if (!raw.isBlank()) {
                DateRange range = parseMonthRange(left);
                rows.add(new ArchiveDocument.OrderedText(row - first, left, right, raw, range.start(), range.end()));
            }
        }
        return rows;
    }

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

    private String text(XSSFSheet sheet, String coordinate) {
        CellAddress address = new CellAddress(coordinate);
        Cell cell = sheet.getRow(address.getRow()) == null ? null : sheet.getRow(address.getRow()).getCell(address.getColumn());
        return cell == null ? "" : formatter.formatCellValue(cell).strip();
    }

    private LocalDate parseDate(XSSFSheet sheet, String coordinate) {
        CellAddress address = new CellAddress(coordinate);
        Cell cell = sheet.getRow(address.getRow()) == null ? null : sheet.getRow(address.getRow()).getCell(address.getColumn());
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String raw = formatter.formatCellValue(cell).strip();
        if (raw.isEmpty()) return null;
        for (DateTimeFormatter dateFormatter : DATE_FORMATTERS) {
            try { return LocalDate.parse(raw, dateFormatter); } catch (DateTimeParseException ignored) { }
        }
        throw new BusinessException(ErrorCode.TEMPLATE_INVALID, coordinate + " 日期格式不正确");
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
