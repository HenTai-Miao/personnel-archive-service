package com.company.personnelarchive.archiveexport;

import com.company.personnelarchive.archiveimport.ExcelArchiveReader;
import com.company.personnelarchive.archiveimport.ExcelTemplateV1;
import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.family.AgeCalculator;
import com.company.personnelarchive.person.IdentityProtection;
import com.company.personnelarchive.person.PersonReadMapper;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.SheetUtil;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Service
/**
 * 从有效数据库快照重建 V1 Excel 档案。
 * 固定标签、样式、合并和打印布局来自只读空模板，数据库只提供用户数据和照片。
 */
public class ArchiveExportService {
    private final PersonReadMapper mapper;
    private final ArchiveProperties properties;
    private final IdentityProtection protection;
    private final ExcelArchiveReader templateValidator;
    private final ResourceLoader resourceLoader;
    private final Clock clock;
    private final AuditService audit;

    public ArchiveExportService(PersonReadMapper mapper, ArchiveProperties properties, IdentityProtection protection,
                                ExcelArchiveReader templateValidator, ResourceLoader resourceLoader, Clock clock,
                                AuditService audit) {
        this.mapper = mapper; this.properties = properties; this.protection = protection;
        this.templateValidator = templateValidator; this.resourceLoader = resourceLoader; this.clock = clock; this.audit = audit;
    }

    public GeneratedArchive generate(long personId, long operatorId) {
        GeneratedArchive archive = generateForBatch(personId);
        audit.record(operatorId, "EXPORT", "PERSON", personId, "SUCCESS", true);
        return archive;
    }

    /**
     * 为批量打包生成一份未记成功审计的工作簿；调用方只在整包构建成功后统一落审计。
     */
    GeneratedArchive generateForBatch(long personId) {
        // 普通导出只允许有效快照；历史版本需要单独的审计/管理入口。
        PersonReadMapper.PersonRow person = mapper.findActive(properties.tenantId(), personId);
        if (person == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        Resource template = resourceLoader.getResource(properties.excel().template());
        if (!template.exists()) throw new BusinessException(ErrorCode.TEMPLATE_NOT_CONFIGURED);
        try (var input = template.getInputStream(); var workbook = new XSSFWorkbook(input);
             var output = new ByteArrayOutputStream()) {
            // 生成前复核模板，避免资源被替换后产出字段错位但看似成功的档案。
            templateValidator.validateStructure(workbook);
            validatePrintLayout(workbook.getSheet(ExcelTemplateV1.MAIN_SHEET));
            fill(workbook, personId, person);
            workbook.write(output);
            return new GeneratedArchive("人员档案-" + safeFilename(person.personName()) + ".xlsx", output.toByteArray());
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成 Excel 档案失败");
        }
    }

    private void fill(XSSFWorkbook workbook, long personId, PersonReadMapper.PersonRow p) {
        XSSFSheet sheet = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
        PersonReadMapper.EmploymentRow employment = mapper.findEmployment(properties.tenantId(), personId);
        List<PersonReadMapper.EducationRow> education = mapper.findEducation(properties.tenantId(), personId);
        List<PersonReadMapper.WorkRow> work = mapper.findWork(properties.tenantId(), personId);
        List<PersonReadMapper.RewardRow> rewards = mapper.findRewards(properties.tenantId(), personId);
        List<PersonReadMapper.FamilyRow> family = mapper.findFamily(properties.tenantId(), personId);
        // V1 没有续页协议，超容量必须失败，不能静默丢失数据库中的行。
        if (education.stream().filter(row -> "MAIN".equals(row.educationType())).count() > ExcelTemplateV1.MAIN_EDUCATION_CAPACITY
            || work.size() > ExcelTemplateV1.WORK_EXPERIENCE_CAPACITY || rewards.size() > ExcelTemplateV1.REWARD_PUNISHMENT_CAPACITY
            || family.size() > ExcelTemplateV1.FAMILY_MEMBER_CAPACITY) {
            throw new BusinessException(ErrorCode.TEMPLATE_CAPACITY_EXCEEDED);
        }
        set(sheet, "B2", p.employeeNo()); set(sheet, "B3", p.personName()); set(sheet, "D3", p.gender());
        setDate(sheet, "F3", p.birthDate()); set(sheet, "H3", p.nation()); set(sheet, "B4", p.nativePlace());
        set(sheet, "D4", p.birthPlace()); set(sheet, "F4", p.politicalStatus()); setDate(sheet, "H4", p.politicalJoinDate());
        if (employment != null) {
            set(sheet, "B5", joinOrganization(employment)); setDate(sheet, "F5", employment.workStartDate());
            setDate(sheet, "H5", employment.dispatchedToBiddingCompanyDate()); set(sheet, "B6", employment.positionName());
            set(sheet, "F6", employment.professionalTitleOrSkill()); setDate(sheet, "H6", employment.titleOrSkillAcquiredDate());
        }
        // 身份证全文只为生成授权档案临时解密；未填写时按原工作簿保持空白。
        set(sheet, "B7", p.idCardCiphertext() == null ? "" : protection.decrypt(p.idCardCiphertext()));
        set(sheet, "F7", p.maritalStatus()); set(sheet, "H7", p.healthStatus());
        education.stream().filter(row -> "FULL_TIME".equals(row.educationType())).findFirst().ifPresent(row -> writeSummary(sheet, row, 8));
        education.stream().filter(row -> "IN_SERVICE".equals(row.educationType())).findFirst().ifPresent(row -> writeSummary(sheet, row, 10));
        set(sheet, "B12", p.mailingAddress()); set(sheet, "F12", p.postalCode()); set(sheet, "H12", p.phoneNumber());
        // 自由文本按持久化的原文和序号回填，防止结构化解析改变用户输入。
        education.stream().filter(row -> "MAIN".equals(row.educationType())).forEach(row -> writeRawRow(sheet, 12 + row.sortOrder(), row.rawText()));
        work.forEach(row -> writeRawRow(sheet, 17 + row.sortOrder(), row.rawText()));
        rewards.forEach(row -> writeRawRow(sheet, 33 + row.sortOrder(), row.rawText()));
        XSSFSheet helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
        for (PersonReadMapper.FamilyRow member : family) {
            int mainRow = 38 + member.sortOrder();
            set(sheet, "B" + mainRow, member.relationshipName()); set(sheet, "C" + mainRow, member.memberName());
            // 年龄是导出当天的派生值，权威数据仍是辅助表中的出生日期。
            if (member.birthDate() != null) setNumber(sheet, "D" + mainRow, AgeCalculator.completedYears(member.birthDate(), clock));
            set(sheet, "E" + mainRow, member.politicalStatus()); set(sheet, "F" + mainRow, member.organizationAndPosition());
            int helperRow = 1 + member.sortOrder();
            set(helper, "B" + helperRow, member.relationshipName()); set(helper, "C" + helperRow, member.memberName());
            setDate(helper, "D" + helperRow, member.birthDate());
        }
        set(sheet, "B46", declaration(p));
        PersonReadMapper.PhotoRow photo = mapper.findPhoto(properties.tenantId(), personId);
        if (photo != null) insertPhoto(workbook, sheet, photo);
    }

    private static void writeSummary(XSSFSheet sheet, PersonReadMapper.EducationRow row, int firstRow) {
        set(sheet, "C" + firstRow, row.educationLevel()); set(sheet, "F" + firstRow, row.institutionName());
        set(sheet, "C" + (firstRow + 1), row.degreeName()); set(sheet, "F" + (firstRow + 1), row.departmentAndMajor());
    }

    private static void writeRawRow(XSSFSheet sheet, int row, String raw) {
        if (raw == null) return;
        String[] parts = raw.split("\\t", 2);
        set(sheet, "B" + row, parts[0]);
        if (parts.length > 1) set(sheet, "D" + row, parts[1]);
    }

    private static void insertPhoto(XSSFWorkbook workbook, XSSFSheet sheet, PersonReadMapper.PhotoRow photo) {
        // 使用数据库中的原始图片字节和完整锚点，保持导入前后的格式与位置。
        int pictureType = "image/png".equals(photo.mimeType()) ? Workbook.PICTURE_TYPE_PNG : Workbook.PICTURE_TYPE_JPEG;
        int index = workbook.addPicture(photo.photoData(), pictureType);
        var drawing = sheet.createDrawingPatriarch();
        ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
        anchor.setCol1(photo.anchorCol1()); anchor.setRow1(photo.anchorRow1());
        anchor.setDx1(photo.anchorDx1()); anchor.setDy1(photo.anchorDy1());
        anchor.setCol2(photo.anchorCol2()); anchor.setRow2(photo.anchorRow2());
        anchor.setDx2(photo.anchorDx2()); anchor.setDy2(photo.anchorDy2());
        anchor.setAnchorType(ClientAnchor.AnchorType.valueOf(photo.anchorType()));
        XSSFPicture picture = (XSSFPicture) drawing.createPicture(anchor, index);
        // POI 创建图片后 OOXML 形状偏移可能退化为相对值，这里恢复相对于工作表原点的绝对 EMU。
        var offset = picture.getCTPicture().getSpPr().getXfrm().getOff();
        double absoluteXPixels = 0;
        float defaultCharacterWidth = SheetUtil.getDefaultCharWidthAsFloat(workbook);
        for (int column = 0; column < photo.anchorCol1(); column++) {
            absoluteXPixels += sheet.getColumnWidth(column) / 256d * defaultCharacterWidth;
        }
        long absoluteX = Units.pixelToEMU((int) absoluteXPixels) + photo.anchorDx1();
        long absoluteY = photo.anchorDy1();
        for (int row = 0; row < photo.anchorRow1(); row++) {
            var sheetRow = sheet.getRow(row);
            absoluteY += Units.toEMU(sheetRow == null ? sheet.getDefaultRowHeightInPoints() : sheetRow.getHeightInPoints());
        }
        offset.setX(absoluteX);
        offset.setY(absoluteY);
    }

    static void validatePrintLayout(XSSFSheet sheet) {
        if (sheet.getPrintSetup().getPaperSize() != 9 || sheet.getPrintSetup().getLandscape()
            || !sheet.getHorizontallyCenter() || !sheet.getVerticallyCenter() || !sheet.getCTWorksheet().isSetSheetProtection()
            || !Arrays.equals(sheet.getRowBreaks(), new int[]{56, 100}) || !Arrays.equals(sheet.getColumnBreaks(), new int[]{8})) {
            throw new BusinessException(ErrorCode.TEMPLATE_INVALID, "模板打印布局或保护设置不正确");
        }
    }

    private static void set(XSSFSheet sheet, String coordinate, String value) {
        XSSFCell cell = cell(sheet, coordinate);
        if (value == null || value.isEmpty()) cell.setBlank();
        else cell.setCellValue(value);
    }
    private static void setDate(XSSFSheet sheet, String coordinate, LocalDate value) {
        XSSFCell cell = cell(sheet, coordinate);
        if (value == null) {
            cell.setBlank();
            return;
        }
        var style = sheet.getWorkbook().createCellStyle();
        style.cloneStyleFrom(cell.getCellStyle());
        style.setDataFormat(sheet.getWorkbook().getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        cell.setCellStyle(style);
        cell.setCellValue(value.atStartOfDay());
    }
    private static void setNumber(XSSFSheet sheet, String coordinate, int value) {
        cell(sheet, coordinate).setCellValue(value);
    }
    private static XSSFCell cell(XSSFSheet sheet, String coordinate) {
        CellAddress address = new CellAddress(coordinate);
        var row = sheet.getRow(address.getRow());
        if (row == null) row = sheet.createRow(address.getRow());
        var cell = row.getCell(address.getColumn());
        if (cell == null) cell = row.createCell(address.getColumn());
        return cell;
    }
    private static String joinOrganization(PersonReadMapper.EmploymentRow row) {
        if (row.organizationName() == null) return row.departmentName();
        return row.departmentName() == null ? row.organizationName() : row.organizationName() + " / " + row.departmentName();
    }
    private static String declaration(PersonReadMapper.PersonRow p) {
        String date = p.declarationDate() == null ? "    年  月  日" : p.declarationDate().getYear() + "年" + p.declarationDate().getMonthValue() + "月" + p.declarationDate().getDayOfMonth() + "日";
        return "承诺人：" + (p.declarationSigner() == null ? "" : p.declarationSigner()) + "  时间：" + date;
    }
    private static String safeFilename(String value) {
        return value == null || value.isBlank() ? "未命名" : value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public record GeneratedArchive(String filename, byte[] bytes) {}
}
