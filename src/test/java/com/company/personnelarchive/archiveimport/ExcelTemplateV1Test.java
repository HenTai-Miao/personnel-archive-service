/* Excel V1 协议测试：锁定生产模板的工作表、标签、合并区域和容量约定。 */
package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.archiveexport.ArchiveExportService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.util.CellAddress;
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
            main.getRow(38).getCell(3).setCellFormula("DATEDIF(家庭成员年龄计算!D2,TODAY(),\"y\")");
            var helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
            helper.getRow(1).getCell(1).setCellValue("测试称谓");
            helper.getRow(1).getCell(2).setCellValue("测试乙");
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

    @Test
    void readsShiftedSectionsFromLabels() throws Exception {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            main.shiftRows(33, 45, 2, true, false);
            cell(main, "B34").setCellValue("2020.01-2021.01");
            cell(main, "D34").setCellValue("测试工作经历");
            cell(main, "B36").setCellValue("2022.01");
            cell(main, "D36").setCellValue("测试奖励");
            cell(main, "B41").setCellValue("测试称谓");
            cell(main, "C41").setCellValue("测试成员");
            cell(main, "D41").setCellFormula("DATEDIF(家庭成员年龄计算!D2,TODAY(),\"y\")");
            cell(main, "E41").setCellValue("群众");
            cell(main, "F41").setCellValue("测试单位");
            XSSFSheet helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
            cell(helper, "B2").setCellValue("测试称谓");
            cell(helper, "C2").setCellValue("测试成员");
            cell(helper, "D2").setCellValue("2000-07-23");
        });

        ArchiveDocument document = new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx");

        assertThat(document.workExperiences()).extracting(ArchiveDocument.OrderedText::rawText)
            .contains("2020.01-2021.01\t测试工作经历");
        assertThat(document.rewardsPunishments()).extracting(ArchiveDocument.OrderedText::rawText)
            .containsExactly("2022.01\t测试奖励");
        assertThat(document.familyMembers()).containsExactly(new ArchiveDocument.FamilyMember(
            0, "测试称谓", "测试成员", LocalDate.of(2000, 7, 23), "群众", "测试单位"));
    }

    @Test
    void readsMonthOnlyTextWithoutInventingFailure() throws Exception {
        byte[] bytes = workbookBytes(workbook ->
            cell(workbook.getSheet(ExcelTemplateV1.MAIN_SHEET), "F3").setCellValue("1988.07"));

        ArchiveDocument document = new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx");

        assertThat(document.birthDate()).isEqualTo(LocalDate.of(1988, 7, 1));
    }

    @Test
    void readsTenFamilyRowsAndKeepsUnlinkedBirthDateBlank() throws Exception {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            main.shiftRows(44, main.getLastRowNum(), 4, true, false);
            XSSFSheet helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
            for (int index = 0; index < 10; index++) {
                int mainRow = 39 + index;
                int helperRow = 2 + index;
                cell(main, "B" + mainRow).setCellValue("称谓" + index);
                cell(main, "C" + mainRow).setCellValue("成员" + index);
                cell(main, "E" + mainRow).setCellValue("群众");
                cell(main, "F" + mainRow).setCellValue("单位" + index);
                cell(helper, "B" + helperRow).setCellValue("称谓" + index);
                cell(helper, "C" + helperRow).setCellValue("成员" + index);
                if (index < 9) {
                    cell(helper, "D" + helperRow).setCellValue("2000-07-" + String.format("%02d", index + 1));
                    cell(main, "D" + mainRow).setCellFormula(
                        "DATEDIF(家庭成员年龄计算!D" + helperRow + ",TODAY(),\"y\")");
                }
            }
        });

        ArchiveDocument document = new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx");

        assertThat(document.familyMembers()).hasSize(10);
        assertThat(document.familyMembers().get(8).birthDate()).isEqualTo(LocalDate.of(2000, 7, 9));
        assertThat(document.familyMembers().get(9).birthDate()).isNull();
    }

    @Test
    void preservesAllWorkplaceCellsInFamilyRow() throws Exception {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            main.removeMergedRegion(main.getMergedRegions().indexOf(
                org.apache.poi.ss.util.CellRangeAddress.valueOf("F39:I39")));
            cell(main, "B39").setCellValue("测试称谓");
            cell(main, "C39").setCellValue("测试成员");
            cell(main, "F39").setCellValue("第一段");
            cell(main, "G39").setCellValue("第二段");
        });

        ArchiveDocument document = new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx");

        assertThat(document.familyMembers()).singleElement()
            .extracting(ArchiveDocument.FamilyMember::workplacePosition)
            .isEqualTo("第一段\t第二段");
    }

    @Test
    void doesNotAssignUnmatchedHelperBirthDateToBlankMainMember() throws Exception {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            cell(main, "B39").setCellValue("岳父");
            cell(main, "C39").setCellValue("主表成员");
            XSSFSheet helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
            cell(helper, "B2").setCellValue("其他");
            cell(helper, "C2").setCellValue("辅助记录");
            cell(helper, "D2").setCellValue("1960-01-02");
        });

        ArchiveDocument document = new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx");

        assertThat(document.familyMembers()).singleElement()
            .extracting(ArchiveDocument.FamilyMember::birthDate)
            .isNull();
    }

    @Test
    void matchesHelperBirthDateByUniqueExactRelationshipAndName() throws Exception {
        byte[] bytes = workbookBytes(workbook -> {
            XSSFSheet main = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET);
            cell(main, "B39").setCellValue("测试称谓");
            cell(main, "C39").setCellValue("测试成员");
            XSSFSheet helper = workbook.getSheet(ExcelTemplateV1.AGE_SHEET);
            cell(helper, "B2").setCellValue("测试称谓");
            cell(helper, "C2").setCellValue("测试成员");
            cell(helper, "D2").setCellValue("1960-01-02");
        });

        ArchiveDocument document = new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx");

        assertThat(document.familyMembers()).singleElement()
            .extracting(ArchiveDocument.FamilyMember::birthDate)
            .isEqualTo(LocalDate.of(1960, 1, 2));
    }

    private static byte[] workbookBytes(Consumer<XSSFWorkbook> editor) throws Exception {
        try (var input = ExcelTemplateV1Test.class.getResourceAsStream("/excel/personnel-archive-template-v1.xlsx");
             var workbook = new XSSFWorkbook(input); var output = new ByteArrayOutputStream()) {
            editor.accept(workbook);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static XSSFCell cell(XSSFSheet sheet, String coordinate) {
        CellAddress address = new CellAddress(coordinate);
        var row = sheet.getRow(address.getRow());
        if (row == null) row = sheet.createRow(address.getRow());
        var cell = row.getCell(address.getColumn());
        if (cell == null) cell = row.createCell(address.getColumn());
        return cell;
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000, "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500, "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
    }
}
