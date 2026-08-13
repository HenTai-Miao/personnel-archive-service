package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDriverAvailabilityTest {
    @Test
    void loadsMysqlDriverWithoutConnecting() throws Exception {
        assertThat(new com.mysql.cj.jdbc.Driver()
            .acceptsURL("jdbc:mysql://127.0.0.1:3306/personnel_archive"))
            .isTrue();
    }
}
