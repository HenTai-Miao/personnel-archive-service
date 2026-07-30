package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class LegacyDateRangeSeparatorImportTest {
    @Test
    void importsLegacyRangeSeparatorsFromRepeatedSections() throws Exception {
        // Given
        byte[] workbook = workbookBytes(source -> {
            WorkbookLayoutV1 layout = WorkbookLayoutV1.resolve(source, new DataFormatter(Locale.CHINA));
            setRepeatedRow(layout.main(), layout.educationRow(), "1996.09--1999.06", "测试学历");
            setRepeatedRow(layout.main(), layout.educationRow() + 1, "1999.09---2002.06", "测试学历二");
            setRepeatedRow(layout.main(), layout.workRow(), "2003.04～2009.06", "测试经历");
            setRepeatedRow(layout.main(), layout.workRow() + 1, "2009.10——2018.11", "测试经历二");
            setRepeatedRow(layout.main(), layout.workRow() + 2, "2018-06至今", "测试在职经历");
        });

        // When
        ArchiveDocument document = new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(workbook), workbook.length, "fixture.xlsx");

        // Then
        assertThat(document.mainEducation())
            .extracting(ArchiveDocument.OrderedText::rawText,
                ArchiveDocument.OrderedText::startDate, ArchiveDocument.OrderedText::endDate)
            .containsExactly(
                tuple("1996-09—1999-06\t测试学历", "1996-09", "1999-06"),
                tuple("1999-09—2002-06\t测试学历二", "1999-09", "2002-06"));
        assertThat(document.workExperiences())
            .extracting(ArchiveDocument.OrderedText::rawText,
                ArchiveDocument.OrderedText::startDate, ArchiveDocument.OrderedText::endDate)
            .containsExactly(
                tuple("2003-04—2009-06\t测试经历", "2003-04", "2009-06"),
                tuple("2009-10—2018-11\t测试经历二", "2009-10", "2018-11"),
                tuple("2018-06—至今\t测试在职经历", "2018-06", "至今"));
    }

    @Test
    void reportsDateRangeGuidanceForInvalidRepeatedSectionRange() throws Exception {
        // Given
        byte[] workbook = workbookBytes(source -> {
            WorkbookLayoutV1 layout = WorkbookLayoutV1.resolve(source, new DataFormatter(Locale.CHINA));
            setRepeatedRow(layout.main(), layout.educationRow(), "1996.09--约1999.06", "测试学历");
        });

        // When / Then
        assertThatThrownBy(() -> new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(workbook), workbook.length, "fixture.xlsx"))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.code()).isEqualTo(ErrorCode.IMPORT_FIELD_VALIDATION_FAILED);
                assertThat(exception.getMessage()).contains(
                    "必须填写明确且有效的日期或日期区间，例如 2024、1996.09-1999.06 或 2019.03至今");
            });
    }

    private static void setRepeatedRow(XSSFSheet sheet, int rowIndex, String range, String description) {
        cell(sheet, new CellAddress(rowIndex, 1)).setCellValue(range);
        cell(sheet, new CellAddress(rowIndex, 3)).setCellValue(description);
    }

    private static byte[] workbookBytes(Consumer<XSSFWorkbook> editor) throws Exception {
        try (var input = LegacyDateRangeSeparatorImportTest.class
                .getResourceAsStream("/excel/personnel-archive-template-v1.xlsx");
             var workbook = new XSSFWorkbook(input);
             var output = new ByteArrayOutputStream()) {
            editor.accept(workbook);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static XSSFCell cell(XSSFSheet sheet, CellAddress address) {
        var row = sheet.getRow(address.getRow());
        if (row == null) row = sheet.createRow(address.getRow());
        var cell = row.getCell(address.getColumn());
        if (cell == null) cell = row.createCell(address.getColumn());
        return cell;
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000,
                "x".repeat(32), "h".repeat(32), "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500,
                "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "测试管理员"));
    }
}
