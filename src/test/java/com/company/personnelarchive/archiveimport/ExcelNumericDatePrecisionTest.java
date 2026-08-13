package com.company.personnelarchive.archiveimport;

import com.company.personnelarchive.configuration.ArchiveProperties;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelNumericDatePrecisionTest {
    @Test
    void readsExactNumericExcelDateWhenFormatUsesTwoDigitYear() throws Exception {
        // Given
        LocalDate underlyingDate = LocalDate.of(1988, 7, 23);
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/excel/personnel-archive-template-v1.xlsx");
             var workbook = new XSSFWorkbook(input); var output = new ByteArrayOutputStream()) {
            var dateCell = workbook.getSheet(ExcelTemplateV1.MAIN_SHEET).getRow(2).getCell(5);
            dateCell.setCellValue(underlyingDate);
            var dateStyle = workbook.createCellStyle();
            dateStyle.cloneStyleFrom(dateCell.getCellStyle());
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("m/d/yy"));
            dateCell.setCellStyle(dateStyle);
            workbook.write(output);
            bytes = output.toByteArray();
        }

        // When
        ArchiveDocument document = new ExcelArchiveReader(properties()).read(
            new ByteArrayInputStream(bytes), bytes.length, "fixture.xlsx");

        // Then
        assertThat(document.birthDate()).isEqualTo(SourceDate.exact("1988-07-23", underlyingDate));
    }

    private static ArchiveProperties properties() {
        return new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000,
                "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500,
                "classpath:/excel/personnel-archive-template-v1.xlsx"),
            new ArchiveProperties.BootstrapAdmin("", "", "初始管理员"));
    }
}
