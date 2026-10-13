package com.medicalrag.knowledgebase;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeBaseRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<KnowledgeBase> ROW = (row, index) -> new KnowledgeBase(
            Long.toString(row.getLong("id")), row.getString("name"), row.getString("description"),
            row.getBoolean("is_demo"), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("updated_at").toInstant(), 0, 0);

    public KnowledgeBaseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<KnowledgeBase> list(long owner) {
        return jdbc.query("SELECT * FROM knowledge_bases WHERE owner_id = ? ORDER BY updated_at DESC, id DESC", ROW, owner);
    }

    public Optional<KnowledgeBase> find(long id, long owner, boolean lock) {
        return jdbc.query("SELECT * FROM knowledge_bases WHERE id = ? AND owner_id = ?" + (lock ? " FOR UPDATE" : ""),
                ROW, id, owner).stream().findFirst();
    }

    public KnowledgeBase create(long owner, String name, String description) {
        return jdbc.query("INSERT INTO knowledge_bases (owner_id,name,description) VALUES (?,?,?) RETURNING *",
                ROW, owner, name, description).getFirst();
    }

    public Optional<KnowledgeBase> update(long id, long owner, String name, String description) {
        return jdbc.query("""
                UPDATE knowledge_bases SET name = ?, description = ?, updated_at = clock_timestamp()
                WHERE id = ? AND owner_id = ? RETURNING *
                """, ROW, name, description, id, owner).stream().findFirst();
    }

    public boolean delete(long id, long owner) {
        return jdbc.update("DELETE FROM knowledge_bases WHERE id = ? AND owner_id = ?", id, owner) == 1;
    }
}
