package com.medicalrag.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdministratorRepository {
    private final JdbcTemplate jdbc;

    public AdministratorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isInitialized() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users)", Boolean.class));
    }

    public Optional<Administrator> create(String username, String passwordHash) {
        // PostgreSQL's unique singleton constraint arbitrates concurrent requests atomically.
        List<Administrator> created = jdbc.query("""
                INSERT INTO users (username, password_hash)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id, username, created_at
                """, (row, index) -> new Administrator(row.getLong("id"), row.getString("username"),
                row.getTimestamp("created_at").toInstant()), username, passwordHash);
        return created.stream().findFirst();
    }

    public Optional<StoredAdministrator> findByUsername(String username) {
        return jdbc.query("SELECT id, username, password_hash, created_at FROM users WHERE username = ?",
                (row, index) -> new StoredAdministrator(
                        new Administrator(row.getLong("id"), row.getString("username"), row.getTimestamp("created_at").toInstant()),
                        row.getString("password_hash")), username).stream().findFirst();
    }

    record StoredAdministrator(Administrator administrator, String passwordHash) {
        @Override
        public String toString() {
            return "StoredAdministrator[credentials=REDACTED]";
        }
    }
}
