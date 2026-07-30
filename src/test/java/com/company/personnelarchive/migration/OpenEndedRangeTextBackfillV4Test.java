package com.company.personnelarchive.migration;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenEndedRangeTextBackfillV4Test {
    @Test
    void rewritesOneClosedKeysetPageAtATime() throws Exception {
        // Given: one full migration page and a driver that forbids updates while its result set is open.
        Connection connection = mock(Connection.class);
        PreparedStatement educationSelect = mock(PreparedStatement.class);
        PreparedStatement educationUpdate = mock(PreparedStatement.class);
        PreparedStatement workSelect = mock(PreparedStatement.class);
        PreparedStatement workUpdate = mock(PreparedStatement.class);
        ResultSet fullPage = mock(ResultSet.class);
        ResultSet emptyEducationPage = mock(ResultSet.class);
        ResultSet emptyWorkPage = mock(ResultSet.class);
        AtomicInteger currentRow = new AtomicInteger();
        AtomicInteger educationQueries = new AtomicInteger();
        AtomicBoolean fullPageOpen = new AtomicBoolean();

        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT e.id")) return educationSelect;
            if (sql.startsWith("UPDATE pa_education")) return educationUpdate;
            if (sql.startsWith("SELECT w.id")) return workSelect;
            if (sql.startsWith("UPDATE pa_work_experience")) return workUpdate;
            throw new AssertionError("Unexpected SQL shape");
        });
        when(educationSelect.executeQuery()).thenAnswer(invocation -> {
            if (educationQueries.getAndIncrement() == 0) {
                fullPageOpen.set(true);
                return fullPage;
            }
            return emptyEducationPage;
        });
        when(fullPage.next()).thenAnswer(invocation -> currentRow.incrementAndGet() <= 500);
        when(fullPage.getLong("id")).thenAnswer(invocation -> (long) currentRow.get());
        when(fullPage.getString("start_date")).thenReturn("2018-06");
        when(fullPage.getString("raw_text")).thenReturn("2018-06至今\t活动学历");
        org.mockito.Mockito.doAnswer(invocation -> {
            fullPageOpen.set(false);
            return null;
        }).when(fullPage).close();
        when(emptyEducationPage.next()).thenReturn(false);
        when(workSelect.executeQuery()).thenReturn(emptyWorkPage);
        when(emptyWorkPage.next()).thenReturn(false);
        when(educationUpdate.executeBatch()).thenAnswer(invocation -> {
            assertThat(fullPageOpen).as("result set is closed before its page is updated").isFalse();
            return new int[500];
        });

        // When
        OpenEndedRangeTextBackfillV4.migrate(connection);

        // Then: reads are keyset-bounded and the empty tail page terminates the scan.
        verify(connection).prepareStatement(argThat(sql -> sql.contains("FROM pa_education")
            && sql.contains("e.id>?") && sql.contains("LIMIT 500")));
        verify(connection).prepareStatement(argThat(sql -> sql.contains("FROM pa_work_experience")
            && sql.contains("w.id>?") && sql.contains("LIMIT 500")));
        verify(educationSelect).setLong(2, 0L);
        verify(educationSelect).setLong(2, 500L);
        verify(workSelect).setLong(2, 0L);
        verify(educationSelect, times(2)).executeQuery();
        verify(educationUpdate, times(500)).addBatch();
        verify(educationUpdate).executeBatch();
    }
}
