/* Excel V1 协议测试：锁定生产模板的工作表、标签、合并区域和容量约定。 */
package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.archiveexport.ArchiveExportService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.ClientAnchor;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelTemplateV1Test {
    @Test
    void productionTemplateKeepsCoordinatesAndIsClean() throws Exception {
        ArchiveProperties properties = properties();
        ExcelArchiveReader reader = new ExcelArchiveReader(properties);
        try (var input = getClass().getResourceAsStream("/excel/personnel-archive-template-v1.xlsx");
             var workbook = new XSSFWorkbook(input)) {
            reader.validateStructure(workbook);
            var main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            assertThat(main.getMergedRegions()).hasSize(73);
            assertThat(main.getRow(1).getCell(1).getStringCellValue()).isBlank();
            assertThat(workbook.getName("byxx")).isNull();
            assertThat(main.getPrintSetup().getPaperSize()).isEqualTo((short) 9);
            assertThat(main.getRowBreaks()).containsExactly(56, 100);
            assertThat(main.getColumnBreaks()).containsExactly(8);
        }
    }

    @Test
    void readsFamilyBirthDateAndPreservesPhotoPlacement() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/excel/personnel-archive-template-v1.xlsx");
             var workbook = new XSSFWorkbook(input); var output = new ByteArrayOutputStream()) {
            var main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            main.getRow(1).getCell(1).setCellValue("T-001");
            main.getRow(2).getCell(1).setCellValue("测试甲");
            main.getRow(6).getCell(1).setCellValue("990000200001010012");
            main.getRow(38).getCell(1).setCellValue("测试称谓");
            main.getRow(38).getCell(2).setCellValue("测试乙");
            main.getRow(38).getCell(3).setCellValue("26");
            var helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
            helper.getRow(1).getCell(3).setCellValue("2000-07-23");
            var imageBytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
            byte[] png = imageBytes.toByteArray();
            int picture = workbook.addPicture(png, Workbook.PICTURE_TYPE_PNG);
            var anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(8); anchor.setRow1(4); anchor.setDx1(12_345); anchor.setDy1(23_456);
            anchor.setCol2(9); anchor.setRow2(7); anchor.setDx2(34_567); anchor.setDy2(45_678);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
            main.createDrawingPatriarch().createPicture(anchor, picture);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        ArchiveDocument document = new ExcelArchiveReader(properties()).read(new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx");
        assertThat(document.familyMembers()).hasSize(1);
        assertThat(document.familyMembers().getFirst().birthDate()).isEqualTo(java.time.LocalDate.of(2000, 7, 23));
        assertThat(document.photo()).isNotNull();
        assertThat(document.photo().width()).isEqualTo(2);
        assertThat(document.photo().anchor()).isEqualTo(new ArchiveDocument.PhotoAnchor(
            8, 4, 12_345, 23_456, 9, 7, 34_567, 45_678, "MOVE_DONT_RESIZE"));
    }

    @Test
    void rejectsPhotoThatExtendsOutsidePhotoArea() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/excel/personnel-archive-template-v1.xlsx");
             var workbook = new XSSFWorkbook(input); var output = new ByteArrayOutputStream()) {
            var imageBytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
            int picture = workbook.addPicture(imageBytes.toByteArray(), Workbook.PICTURE_TYPE_PNG);
            var anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(8); anchor.setRow1(4); anchor.setCol2(7); anchor.setRow2(7);
            workbook.getSheet(ExcelTemplateV1.MAIN_SHEET).createDrawingPatriarch().createPicture(anchor, picture);
            workbook.write(output);
            bytes = output.toByteArray();
        }

        assertThatThrownBy(() -> new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("图片必须位于照片区域");
    }

    @Test
    void readsPartiallyFilledFamilyRowWithoutRequiredFieldFailure() throws Exception {
        // Given
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/excel/personnel-archive-template-v1.xlsx");
             var workbook = new XSSFWorkbook(input); var output = new ByteArrayOutputStream()) {
            workbook.getSheet(ExcelTemplateV1.MAIN_SHEET).getRow(38).getCell(4).setCellValue("群众");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        // When
        ArchiveDocument document = new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx");

        // Then
        assertThat(document.familyMembers()).containsExactly(
            new ArchiveDocument.FamilyMember(0, "", "", null, "群众", ""));
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000, "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500, "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
    }
}
