package com.company.personnelarchive.migration;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonicalSourceValueBackfillV3Test {
    @Test
    void readsEveryPhaseThroughClosedKeysetPages() throws Exception {
        // Given: every scan must be bounded, and one update phase fills an entire page.
        Connection connection = mock(Connection.class);
        PreparedStatement agePageSelect = mock(PreparedStatement.class);
        PreparedStatement ageUpdate = mock(PreparedStatement.class);
        ResultSet fullAgePage = mock(ResultSet.class);
        ResultSet emptyAgePage = mock(ResultSet.class);
        AtomicInteger currentRow = new AtomicInteger();
        AtomicInteger ageSelectPreparations = new AtomicInteger();
        AtomicInteger ageQueries = new AtomicInteger();
        AtomicBoolean fullPageOpen = new AtomicBoolean();
        List<String> selectSqls = new ArrayList<>();

        when(connection.createStatement()).thenThrow(
            new AssertionError("V13 must not issue an unbounded Statement query"));
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT ")) {
                selectSqls.add(sql);
                boolean ageOnly = sql.contains("t.age_text AS age_text")
                    && !sql.contains("relationship_name");
                if (ageOnly && ageSelectPreparations.incrementAndGet() == 2) {
                    return agePageSelect;
                }
                return emptySelect();
            }
            if (sql.startsWith("UPDATE pa_family_member SET age_text=")) return ageUpdate;
            return mock(PreparedStatement.class);
        });
        when(agePageSelect.executeQuery()).thenAnswer(invocation -> {
            if (ageQueries.getAndIncrement() == 0) {
                fullPageOpen.set(true);
                return fullAgePage;
            }
            return emptyAgePage;
        });
        when(fullAgePage.next()).thenAnswer(invocation -> currentRow.incrementAndGet() <= 500);
        when(fullAgePage.getLong("id")).thenAnswer(invocation -> (long) currentRow.get());
        when(fullAgePage.getString("age_text")).thenReturn("三十五岁");
        org.mockito.Mockito.doAnswer(invocation -> {
            fullPageOpen.set(false);
            return null;
        }).when(fullAgePage).close();
        when(emptyAgePage.next()).thenReturn(false);
        when(ageUpdate.executeBatch()).thenAnswer(invocation -> {
            assertThat(fullPageOpen)
                .as("the page result set is closed before its updates execute")
                .isFalse();
            return new int[500];
        });

        // When
        CanonicalSourceValueBackfillV3.migrate(connection);

        // Then: all seven validations, seven canonical updates, and four raw-text rebuilds page reads.
        assertThat(selectSqls).hasSize(18).allSatisfy(sql -> {
            assertThat(sql).contains("t.id>?");
            assertThat(sql).contains("ORDER BY t.id");
            assertThat(sql).contains("LIMIT 500");
        });
        verify(connection, never()).createStatement();
        verify(agePageSelect).setLong(1, 0L);
        verify(agePageSelect).setLong(1, 500L);
        verify(agePageSelect, times(2)).executeQuery();
        verify(ageUpdate, times(500)).addBatch();
        verify(ageUpdate).executeBatch();
        verify(ageUpdate).clearBatch();
    }

    private static PreparedStatement emptySelect() throws Exception {
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);
        return select;
    }
}
