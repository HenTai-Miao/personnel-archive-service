/* Excel 导出测试：重开生成工作簿并核对字段、原始行、布局及照片还原。 */
package com.company.personnelarchive.archiveexport;

import com.company.personnelarchive.archiveimport.ExcelArchiveReader;
import com.company.personnelarchive.archiveimport.ExcelTemplateV1;
import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.person.IdentityProtection;
import com.company.personnelarchive.person.PersonReadMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.SheetUtil;
import org.apache.poi.util.Units;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArchiveExportServiceTest {
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
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        PersonReadMapper.PersonRow person = new PersonReadMapper.PersonRow(7, "T-001", "测试甲", new byte[]{1}, "0012",
            "女", LocalDate.of(2000, 1, 1), "测试民族", "测试籍贯", "测试出生地", "测试面貌", null,
            "未填写", "良好", "测试地址", "000000", "00000000", "测试甲", LocalDate.of(2026, 7, 22),
            "V1", 1, Instant.parse("2026-07-21T00:00:00Z"));
        when(mapper.findActive(1, 7)).thenReturn(person);
        when(mapper.findEmployment(1, 7)).thenReturn(new PersonReadMapper.EmploymentRow("测试单位", "测试部门", null, null, "测试职务", "测试技能", null));
        when(mapper.findEducation(1, 7)).thenReturn(List.of());
        when(mapper.findWork(1, 7)).thenReturn(List.of(new PersonReadMapper.WorkRow(null, null, null, "测试时段\t测试工作", 1)));
        when(mapper.findRewards(1, 7)).thenReturn(List.of());
        when(mapper.findFamily(1, 7)).thenReturn(List.of(new PersonReadMapper.FamilyRow(8, "测试称谓", "测试乙",
            LocalDate.of(2000, 7, 23), "测试面貌", "测试单位及职务", null, 1)));
        when(protection.decrypt(person.idCardCiphertext())).thenReturn("990000200001010012");
        ExcelArchiveReader validator = new ExcelArchiveReader(properties);
        ArchiveExportService service = new ArchiveExportService(mapper, properties, protection, validator,
            new DefaultResourceLoader(), clock, audit);

        ArchiveExportService.GeneratedArchive generated = service.generate(7, 9);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(generated.bytes()))) {
            var main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            assertThat(main.getRow(1).getCell(1).getStringCellValue()).isEqualTo("T-001");
            assertThat(main.getRow(2).getCell(1).getStringCellValue()).isEqualTo("测试甲");
            assertThat(main.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(main.getRow(6).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(main.getRow(11).getCell(5).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(main.getRow(11).getCell(7).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(main.getRow(38).getCell(3).getNumericCellValue()).isEqualTo(25);
            assertThat(workbook.getSheet(ExcelTemplateV1.AGE_SHEET).getRow(1).getCell(3)
                .getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2000, 7, 23));
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
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        ArchiveExportService service = new ArchiveExportService(mapper, properties, protection,
            new ExcelArchiveReader(properties), new DefaultResourceLoader(), clock, audit);

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
}
