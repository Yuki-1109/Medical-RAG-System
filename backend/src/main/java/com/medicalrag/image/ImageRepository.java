package com.medicalrag.image;

import java.util.List;
import java.util.Optional;
import com.medicalrag.storage.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ImageRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ImageRecord> ROW=(r,n) -> new ImageRecord(new ImageInfo(
            Long.toString(r.getLong("id")),Long.toString(r.getLong("knowledge_base_id")),r.getString("original_filename"),
            r.getString("file_format"),r.getObject("file_size",Long.class),r.getString("sha256"),r.getObject("width",Integer.class),r.getObject("height",Integer.class),
            r.getString("image_type"),r.getString("stage"),r.getString("severity"),r.getString("keywords"),r.getString("note"),
            r.getString("lifecycle_status"),r.getString("cleanup_error"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant()),r.getString("storage_key"));
    public ImageRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    List<ImageRecord> list(long base, long owner) {
        return jdbc.query("""
                SELECT d.* FROM medical_images d JOIN knowledge_bases k ON k.id=d.knowledge_base_id
                WHERE k.id=? AND k.owner_id=? ORDER BY d.created_at DESC,d.id DESC
                """, ROW, base, owner);
    }
    Optional<ImageRecord> find(long id, long owner) {
        return jdbc.query("""
                SELECT d.* FROM medical_images d JOIN knowledge_bases k ON k.id=d.knowledge_base_id
                WHERE d.id=? AND k.owner_id=?
                """, ROW, id, owner).stream().findFirst();
    }
    ImageRecord reserve(long base, String name, StorageReservation reservation) {
        return jdbc.query("INSERT INTO medical_images (knowledge_base_id,original_filename,storage_key,file_format) VALUES (?,?,?,?) RETURNING *",
                ROW, base, name, reservation.storageKey(),reservation.type().name()).getFirst();
    }
    ImageRecord activate(long id, StoredOriginal receipt, ImageValidator.Dimensions dimensions) {
        return jdbc.query("""
                UPDATE medical_images SET file_size=?,sha256=?,width=?,height=?,lifecycle_status='ACTIVE',updated_at=clock_timestamp()
                WHERE id=? AND lifecycle_status='UPLOADING' RETURNING *
                """, ROW, receipt.size(), receipt.sha256(), dimensions.width(),dimensions.height(), id).stream().findFirst().orElseThrow(ImageException::missing);
    }
    ImageRecord update(long id,ImageMetadata input) {
        return jdbc.query("""
                UPDATE medical_images SET image_type=?,stage=?,severity=?,keywords=?,note=?,updated_at=clock_timestamp()
                WHERE id=? AND lifecycle_status='ACTIVE' RETURNING *
                """,ROW,input.imageType(),input.stage(),input.severity(),input.keywords(),input.note(),id).stream().findFirst().orElseThrow(ImageException::missing);
    }
    void markDeleting(long id) { jdbc.update("UPDATE medical_images SET lifecycle_status='DELETING',cleanup_error=NULL,updated_at=clock_timestamp() WHERE id=?", id); }
    void markAllDeleting(long base, long owner) {
        jdbc.update("""
                UPDATE medical_images d SET lifecycle_status='DELETING',cleanup_error=NULL,updated_at=clock_timestamp()
                WHERE d.knowledge_base_id=? AND EXISTS (SELECT 1 FROM knowledge_bases k WHERE k.id=d.knowledge_base_id AND k.owner_id=?)
                """, base, owner);
    }
    void cleanupFailed(long id, String message) {
        jdbc.update("UPDATE medical_images SET lifecycle_status='DELETE_FAILED',cleanup_error=?,updated_at=clock_timestamp() WHERE id=?", message, id);
    }
    void remove(long id) { jdbc.update("DELETE FROM medical_images WHERE id=? AND lifecycle_status<>'ACTIVE'", id); }
}
