/* 人员控制器测试：锁定鉴权照片响应的媒体类型、缓存和原始字节。 */
package com.company.personnelarchive.person;

import com.company.personnelarchive.archiveexport.ArchiveExportService;
import com.company.personnelarchive.archiveexport.BatchArchiveExportService;
import com.company.personnelarchive.auth.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonControllerTest {
    @Test
    void returnsNoStorePngWhenReadingStoredPhoto() throws Exception {
        // Given
        PersonQueryService queries = mock(PersonQueryService.class);
        when(queries.photo(7, 9)).thenReturn(new PersonQueryService.PhotoContent(
            new byte[]{(byte) 0x89, 0x50}, "image/png", "fixture.png"));
        var controller = new PersonController(queries, mock(ArchiveExportService.class),
            mock(BatchArchiveExportService.class));
        var principal = new AuthPrincipal(9, 1, "tester", Set.of("VIEWER"));

        // When
        var response = controller.photo(7, principal);

        // Then
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getByteArray()).containsExactly((byte) 0x89, (byte) 0x50);
        verify(queries).photo(7, 9);
    }
}
