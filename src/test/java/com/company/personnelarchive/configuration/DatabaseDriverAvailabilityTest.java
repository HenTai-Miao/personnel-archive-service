package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDriverAvailabilityTest {
    @Test
    void loadsApplicationAndFlywayDriversWithoutConnecting() throws Exception {
        assertThat(new com.kingbase8.Driver()
            .acceptsURL("jdbc:kingbase8://10.141.29.96:54321/personnel_archive"))
            .isTrue();
        assertThat(new org.postgresql.Driver()
            .acceptsURL("jdbc:postgresql://10.141.29.96:54321/personnel_archive"))
            .isTrue();
    }
}
