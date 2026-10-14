package com.medicalrag.settings;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.medicalrag.settings.ModelTypes.*;

@Repository
class ModelRepository {
    private final JdbcTemplate jdbc;
    ModelRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    void lockOwner(long owner) { jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,owner); }
    boolean hasEncryptedKeys() { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM model_configs WHERE encrypted_api_key IS NOT NULL)",Boolean.class)); }
    Optional<Stored> find(long owner, Type type) {
        return jdbc.query("SELECT * FROM model_configs WHERE owner_id=? AND config_type=?",(row,n) -> {
            var time=row.getTimestamp("tested_at");
            return new Stored(owner,type,row.getString("protocol"),row.getString("base_url"),row.getString("model_id"),
                row.getString("encrypted_api_key"),row.getLong("revision"),row.getString("test_status"),row.getString("test_code"),
                time==null?null:time.toInstant(),(Integer)row.getObject("embedding_dimension"));
        },owner,type.name()).stream().findFirst();
    }
    void save(long owner, Type type, Input input, String cipher) {
        jdbc.update("""
            INSERT INTO model_configs (owner_id,config_type,protocol,base_url,model_id,encrypted_api_key)
            VALUES (?,?,?,?,?,?) ON CONFLICT (owner_id,config_type) DO UPDATE SET
            protocol=EXCLUDED.protocol, base_url=EXCLUDED.base_url, model_id=EXCLUDED.model_id,
            encrypted_api_key=EXCLUDED.encrypted_api_key, revision=model_configs.revision+1,
            test_status='UNTESTED', test_code=NULL, tested_at=NULL, embedding_dimension=NULL, updated_at=CURRENT_TIMESTAMP
            """,owner,type.name(),input.protocol(),input.baseUrl(),input.modelId(),cipher);
    }
    boolean tested(Stored config, String code, Integer dimension) {
        return jdbc.update("""
            UPDATE model_configs SET test_status=?,test_code=?,tested_at=CURRENT_TIMESTAMP,embedding_dimension=?
            WHERE owner_id=? AND config_type=? AND revision=?
            """,code==null?"SUCCESS":"FAILED",code,dimension,config.owner(),config.type().name(),config.revision())==1;
    }
}
