package com.medicalrag.document;

import java.util.List;
import java.util.Optional;
import com.medicalrag.storage.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<DocumentRecord> ROW = (r,n) -> new DocumentRecord(new DocumentInfo(
            Long.toString(r.getLong("id")), Long.toString(r.getLong("knowledge_base_id")), r.getString("original_filename"),
            r.getObject("file_size", Long.class), r.getString("sha256"), r.getObject("page_count", Integer.class),
            r.getString("processing_status"), r.getString("error_reason"), r.getString("lifecycle_status"),
            r.getString("cleanup_error"), r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant()), r.getString("storage_key"));
    public DocumentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    List<DocumentRecord> list(long base, long owner) {
        return jdbc.query("""
                SELECT d.* FROM documents d JOIN knowledge_bases k ON k.id=d.knowledge_base_id
                WHERE k.id=? AND k.owner_id=? ORDER BY d.created_at DESC,d.id DESC
                """, ROW, base, owner);
    }
    Optional<DocumentRecord> find(long id, long owner) {
        return jdbc.query("""
                SELECT d.* FROM documents d JOIN knowledge_bases k ON k.id=d.knowledge_base_id
                WHERE d.id=? AND k.owner_id=?
                """, ROW, id, owner).stream().findFirst();
    }
    DocumentRecord reserve(long base, String name, StorageReservation reservation) {
        return jdbc.query("INSERT INTO documents (knowledge_base_id,original_filename,storage_key) VALUES (?,?,?) RETURNING *",
                ROW, base, name, reservation.storageKey()).getFirst();
    }
    DocumentRecord activate(long id, StoredOriginal receipt, int pages) {
        return jdbc.query("""
                UPDATE documents SET file_size=?,sha256=?,page_count=?,lifecycle_status='ACTIVE',updated_at=clock_timestamp()
                WHERE id=? AND lifecycle_status='UPLOADING' RETURNING *
                """, ROW, receipt.size(), receipt.sha256(), pages, id).stream().findFirst().orElseThrow(DocumentException::missing);
    }
    void markDeleting(long id) { jdbc.update("UPDATE documents SET lifecycle_status='DELETING',cleanup_error=NULL,updated_at=clock_timestamp() WHERE id=?", id); }
    void markAllDeleting(long base, long owner) {
        jdbc.update("""
                UPDATE documents d SET lifecycle_status='DELETING',cleanup_error=NULL,updated_at=clock_timestamp()
                WHERE d.knowledge_base_id=? AND EXISTS (SELECT 1 FROM knowledge_bases k WHERE k.id=d.knowledge_base_id AND k.owner_id=?)
                """, base, owner);
    }
    void cleanupFailed(long id, String message) {
        jdbc.update("UPDATE documents SET lifecycle_status='DELETE_FAILED',cleanup_error=?,updated_at=clock_timestamp() WHERE id=?", message, id);
    }
    void remove(long id) { jdbc.update("DELETE FROM documents WHERE id=? AND lifecycle_status<>'ACTIVE'", id); }
}
