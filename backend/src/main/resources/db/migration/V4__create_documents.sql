-- Additive upgrade: preserve users and existing knowledge bases; no cascading deletion.
ALTER TABLE knowledge_bases ADD COLUMN deletion_pending BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE documents (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE RESTRICT,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(200) NOT NULL UNIQUE,
    file_size BIGINT,
    sha256 VARCHAR(64),
    page_count INTEGER,
    processing_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_reason VARCHAR(500),
    lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
    cleanup_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT documents_filename_valid CHECK (char_length(original_filename) > 0 AND original_filename !~ '[[:cntrl:]/\\]'),
    CONSTRAINT documents_storage_key_valid CHECK (storage_key ~ '^documents/[1-9][0-9]{0,18}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/original\.pdf$'),
    CONSTRAINT documents_storage_library_matches CHECK (split_part(storage_key,'/',2) = knowledge_base_id::text),
    CONSTRAINT documents_processing_valid CHECK (processing_status IN ('PENDING','PROCESSING','INDEXED','FAILED')),
    CONSTRAINT documents_lifecycle_valid CHECK (lifecycle_status IN ('UPLOADING','ACTIVE','DELETING','DELETE_FAILED')),
    CONSTRAINT documents_metadata_valid CHECK (
        (file_size IS NULL AND sha256 IS NULL AND page_count IS NULL AND lifecycle_status <> 'ACTIVE') OR
        (file_size IS NOT NULL AND sha256 IS NOT NULL AND page_count IS NOT NULL
            AND file_size > 0 AND sha256 ~ '^[0-9a-f]{64}$' AND page_count BETWEEN 1 AND 10000)
    )
);
CREATE INDEX documents_library_created_idx ON documents (knowledge_base_id, created_at DESC, id DESC);
CREATE INDEX documents_library_lifecycle_idx ON documents (knowledge_base_id, lifecycle_status);
