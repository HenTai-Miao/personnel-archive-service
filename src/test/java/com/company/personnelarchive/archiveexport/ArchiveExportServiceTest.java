/* Excel 导出测试：重开生成工作簿并核对字段、原始行、布局及照片还原。 */
package com.company.personnelarchive.archiveexport;

import com.company.personnelarchive.archiveimport.ArchiveDocument;
import com.company.personnelarchive.archiveimport.ExcelArchiveReader;
import com.company.personnelarchive.archiveimport.ExcelTemplateV1;
import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.person.IdentityProtection;
import com.company.personnelarchive.person.PersonReadMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.SheetUtil;
import org.apache.poi.util.Units;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArchiveExportServiceTest {
    @Test
    void preservesSourceDateAndFamilyAges() throws Exception {
        ArchiveProperties properties = new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000,
                "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500,
                "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        IdentityProtection protection = mock(IdentityProtection.class);
        AuditService audit = mock(AuditService.class);
        PersonReadMapper.PersonRow person = new PersonReadMapper.PersonRow(
            7, "T-001", "测试甲", null, null, "女", "1980", "汉族", "测试籍贯",
            "测试出生地", "群众", "2002.10", "已婚", "健康", "测试地址", "000000",
            "00000000", "测试甲", "2026年7月22日", "V1", 1,
            Instant.parse("2026-07-21T00:00:00Z"));
        List<String> ages = List.of("36", "11", "62", "63", "59", "57");
        List<PersonReadMapper.FamilyRow> family = IntStream.range(0, ages.size())
            .mapToObj(index -> new PersonReadMapper.FamilyRow(index + 1L, "称谓" + index, "成员" + index,
                index == 0 ? "1990.02" : "", ages.get(index), "群众", "测试单位", "", index + 1))
            .toList();
        when(mapper.findActive(1, 7)).thenReturn(person);
        when(mapper.findEducation(1, 7)).thenReturn(List.of());
        when(mapper.findWork(1, 7)).thenReturn(List.of());
        when(mapper.findRewards(1, 7)).thenReturn(List.of());
        when(mapper.findFamily(1, 7)).thenReturn(family);
        ArchiveExportService service = new ArchiveExportService(mapper, properties, protection,
            new ExcelArchiveReader(properties), new DefaultResourceLoader(), audit);

        ArchiveExportService.GeneratedArchive generated = service.generate(7, 9);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(generated.bytes()))) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            var main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            var helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
            assertThat(formatter.formatCellValue(main.getRow(2).getCell(5))).isEqualTo("1980");
            assertThat(formatter.formatCellValue(main.getRow(3).getCell(7))).isEqualTo("2002.10");
            assertThat(formatter.formatCellValue(main.getRow(45).getCell(1)))
                .contains("2026年7月22日");
            assertThat(formatter.formatCellValue(helper.getRow(1).getCell(3))).isEqualTo("1990.02");
            assertThat(IntStream.range(0, ages.size())
                .mapToObj(index -> formatter.formatCellValue(main.getRow(38 + index).getCell(3)))
                .toList()).containsExactlyElementsOf(ages);
        }
    }

    @Test
    void rebuildsWorkbookAndKeepsPrintLayout() throws Exception {
        ArchiveProperties properties = new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000, "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500, "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        IdentityProtection protection = mock(IdentityProtection.class);
        AuditService audit = mock(AuditService.class);
        PersonReadMapper.PersonRow person = new PersonReadMapper.PersonRow(7, "T-001", "测试甲", new byte[]{1}, "0012",
            "女", "2000-01-01", "测试民族", "测试籍贯", "测试出生地", "测试面貌", null,
            "未填写", "良好", "测试地址", "000000", "00000000", "测试甲", "2026-07-22",
            "V1", 1, Instant.parse("2026-07-21T00:00:00Z"));
        when(mapper.findActive(1, 7)).thenReturn(person);
        when(mapper.findEmployment(1, 7)).thenReturn(new PersonReadMapper.EmploymentRow("测试单位", "测试部门", null, null, "测试职务", "测试技能", null));
        when(mapper.findEducation(1, 7)).thenReturn(List.of());
        when(mapper.findWork(1, 7)).thenReturn(List.of(new PersonReadMapper.WorkRow(null, null, null, "测试时段\t测试工作", 1)));
        when(mapper.findRewards(1, 7)).thenReturn(List.of());
        when(mapper.findFamily(1, 7)).thenReturn(List.of(new PersonReadMapper.FamilyRow(8, "测试称谓", "测试乙",
            "2000-07-23", "25", "测试面貌", "测试单位及职务", null, 1)));
        when(protection.decrypt(person.idCardCiphertext())).thenReturn("990000200001010012");
        ExcelArchiveReader validator = new ExcelArchiveReader(properties);
        ArchiveExportService service = new ArchiveExportService(mapper, properties, protection, validator,
            new DefaultResourceLoader(), audit);

        ArchiveExportService.GeneratedArchive generated = service.generate(7, 9);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(generated.bytes()))) {
            var main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            assertThat(main.getRow(1).getCell(1).getStringCellValue()).isEqualTo("T-001");
            assertThat(main.getRow(2).getCell(1).getStringCellValue()).isEqualTo("测试甲");
            assertThat(main.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(main.getRow(6).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(main.getRow(11).getCell(5).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(main.getRow(11).getCell(7).getCellStyle().getDataFormatString()).isEqualTo("@");
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            assertThat(formatter.formatCellValue(main.getRow(38).getCell(3))).isEqualTo("25");
            assertThat(formatter.formatCellValue(
                workbook.getSheet(ExcelTemplateV1.AGE_SHEET).getRow(1).getCell(3))).isEqualTo("2000-07-23");
            assertThat(workbook.getSheet(ExcelTemplateV1.AGE_SHEET).getRow(1).getCell(4).getCellType())
                .as("导出不应在原表空白的辅助列中新增年龄值")
                .isEqualTo(CellType.BLANK);
            ArchiveExportService.validatePrintLayout(main);
        }
    }

    @Test
    void writesPhotoTransformAtAnchoredCellInsteadOfSheetOrigin() throws Exception {
        ArchiveProperties properties = new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000, "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500, "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        IdentityProtection protection = mock(IdentityProtection.class);
        AuditService audit = mock(AuditService.class);
        PersonReadMapper.PersonRow person = new PersonReadMapper.PersonRow(7, "T-001", "测试甲", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            "V1", 1, Instant.parse("2026-07-21T00:00:00Z"));
        var imageBytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
        var photo = new PersonReadMapper.PhotoRow(imageBytes.toByteArray(), "image/png", "photo.png", imageBytes.size(),
            2, 2, new byte[32], 8, 2, 9_525, 9_525, 9, 7, 352_153, 180_975, "MOVE_DONT_RESIZE");
        when(mapper.findActive(1, 7)).thenReturn(person);
        when(mapper.findEducation(1, 7)).thenReturn(List.of());
        when(mapper.findWork(1, 7)).thenReturn(List.of());
        when(mapper.findRewards(1, 7)).thenReturn(List.of());
        when(mapper.findFamily(1, 7)).thenReturn(List.of());
        when(mapper.findPhoto(1, 7)).thenReturn(photo);
        ArchiveExportService service = new ArchiveExportService(mapper, properties, protection,
            new ExcelArchiveReader(properties), new DefaultResourceLoader(), audit);

        ArchiveExportService.GeneratedArchive generated = service.generate(7, 9);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(generated.bytes()))) {
            var sheet = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            var picture = (org.apache.poi.xssf.usermodel.XSSFPicture) sheet.getDrawingPatriarch().getShapes().getFirst();
            var transform = picture.getCTPicture().getSpPr().getXfrm();
            double absoluteXPixels = 0;
            float defaultCharacterWidth = SheetUtil.getDefaultCharWidthAsFloat(workbook);
            for (int column = 0; column < photo.anchorCol1(); column++) {
                absoluteXPixels += sheet.getColumnWidth(column) / 256d * defaultCharacterWidth;
            }
            long expectedX = Units.pixelToEMU((int) absoluteXPixels) + photo.anchorDx1();
            long expectedY = photo.anchorDy1();
            for (int row = 0; row < photo.anchorRow1(); row++) {
                expectedY += Units.toEMU(sheet.getRow(row).getHeightInPoints());
            }
            assertThat(((Number) transform.getOff().getX()).longValue()).isEqualTo(expectedX);
            assertThat(((Number) transform.getOff().getY()).longValue()).isEqualTo(expectedY);
            assertThat(sheet.getRow(6).getCell(1).getCellType()).isEqualTo(CellType.BLANK);
        }
        verifyNoInteractions(protection);
    }

    @Test
    void expandsTemplateForTenFamilyMembers() throws Exception {
        ArchiveProperties properties = new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000,
                "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500,
                "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
        PersonReadMapper mapper = mock(PersonReadMapper.class);
        IdentityProtection protection = mock(IdentityProtection.class);
        AuditService audit = mock(AuditService.class);
        PersonReadMapper.PersonRow person = new PersonReadMapper.PersonRow(7, "T-010", "测试十人", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, "测试十人",
            "2026年7月22日", "V1", 1, Instant.parse("2026-07-21T00:00:00Z"));
        List<PersonReadMapper.FamilyRow> family = IntStream.rangeClosed(1, 10)
            .mapToObj(index -> new PersonReadMapper.FamilyRow(index, "称谓" + index, "成员" + index,
                index == 10 ? null : "2000-07-" + String.format("%02d", index), "", "群众",
                "单位" + index, null, index))
            .toList();
        when(mapper.findActive(1, 7)).thenReturn(person);
        when(mapper.findEducation(1, 7)).thenReturn(List.of());
        when(mapper.findWork(1, 7)).thenReturn(List.of());
        when(mapper.findRewards(1, 7)).thenReturn(List.of());
        when(mapper.findFamily(1, 7)).thenReturn(family);
        ArchiveExportService service = new ArchiveExportService(mapper, properties, protection,
            new ExcelArchiveReader(properties), new DefaultResourceLoader(), audit);

        ArchiveExportService.GeneratedArchive generated = service.generate(7, 9);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(generated.bytes()))) {
            var main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            var helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
            assertThat(main.getRow(38).getCell(1).getStringCellValue()).isEqualTo("称谓1");
            assertThat(main.getRow(47).getCell(2).getStringCellValue()).isEqualTo("成员10");
            assertThat(helper.getRow(1).getCell(1).getStringCellValue()).isEqualTo("称谓1");
            assertThat(helper.getRow(10).getCell(2).getStringCellValue()).isEqualTo("成员10");
            assertThat(helper.getRow(10).getCell(3).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(main.getMergedRegions().stream().map(CellRangeAddress::formatAsString))
                .contains("A38:A48", "F48:I48", "A49:I49", "B50:I50");
            assertThat(main.getRow(49).getCell(1).getStringCellValue())
                .contains("承诺人：测试十人", "2026年7月22日");
            assertThat(main.getRowBreaks()).containsExactly(60, 104);
        }
        ArchiveDocument reopened = new ExcelArchiveReader(properties).read(
            new ByteArrayInputStream(generated.bytes()), generated.bytes().length, generated.filename());
        assertThat(reopened.familyMembers()).hasSize(10);
        assertThat(reopened.familyMembers().getLast().birthDate().text()).isEmpty();
        assertThat(reopened.familyMembers().getLast().birthDate().exactDate()).isNull();
        assertThat(reopened.declarationSigner()).isEqualTo("测试十人");
    }
}
