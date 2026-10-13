package com.medicalrag.health;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthServiceTest {
    @Test
    void connectionFailureIsReportedWithoutExposingTheSQLException() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("password=private-test-value; jdbc:postgresql://private"));

        HealthResponse health = new HealthService(source).check();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.backend().status()).isEqualTo("UP");
        assertThat(health.database().status()).isEqualTo("DOWN");
        assertThat(health.pgvector().status()).isEqualTo("UNKNOWN");
        assertThat(health.migrations().status()).isEqualTo("UNKNOWN");
        assertThat(health.toString()).doesNotContain("private-test-value", "jdbc:");
    }
}
