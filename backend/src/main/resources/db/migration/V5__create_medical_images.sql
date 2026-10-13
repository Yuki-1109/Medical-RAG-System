-- Preserve V1-V4, administrator, libraries, documents and their original files.
CREATE TABLE medical_images (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE RESTRICT,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(200) NOT NULL UNIQUE,
    file_format VARCHAR(8) NOT NULL,
    file_size BIGINT,
    sha256 VARCHAR(64),
    width INTEGER,
    height INTEGER,
    image_type VARCHAR(16) NOT NULL DEFAULT 'UNCLASSIFIED',
    stage VARCHAR(100) NOT NULL DEFAULT '',
    severity VARCHAR(100) NOT NULL DEFAULT '',
    keywords VARCHAR(500) NOT NULL DEFAULT '',
    note VARCHAR(2000) NOT NULL DEFAULT '',
    lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
    cleanup_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT images_filename_valid CHECK (char_length(original_filename)>0 AND original_filename !~ '[[:cntrl:]/\\]'),
    CONSTRAINT images_format_valid CHECK (file_format IN ('JPEG','PNG')),
    CONSTRAINT images_key_valid CHECK (storage_key ~ '^images/[1-9][0-9]{0,18}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/original\.(jpg|png)$'),
    CONSTRAINT images_key_library_matches CHECK (split_part(storage_key,'/',2)=knowledge_base_id::text),
    CONSTRAINT images_key_format_matches CHECK ((file_format='JPEG' AND storage_key LIKE '%.jpg') OR (file_format='PNG' AND storage_key LIKE '%.png')),
    CONSTRAINT images_type_valid CHECK (image_type IN ('LESION','NORMAL','UNCLASSIFIED')),
    CONSTRAINT images_lifecycle_valid CHECK (lifecycle_status IN ('UPLOADING','ACTIVE','DELETING','DELETE_FAILED')),
    CONSTRAINT images_metadata_valid CHECK (
        (file_size IS NULL AND sha256 IS NULL AND width IS NULL AND height IS NULL AND lifecycle_status<>'ACTIVE') OR
        (file_size IS NOT NULL AND sha256 IS NOT NULL AND width IS NOT NULL AND height IS NOT NULL
            AND file_size>0 AND sha256 ~ '^[0-9a-f]{64}$' AND width>0 AND height>0)
    ),
    CONSTRAINT images_manual_text_plain CHECK (stage !~ '[<>]' AND severity !~ '[<>]' AND keywords !~ '[<>]' AND note !~ '[<>]')
);
CREATE INDEX images_library_created_idx ON medical_images(knowledge_base_id,created_at DESC,id DESC);
CREATE INDEX images_library_lifecycle_idx ON medical_images(knowledge_base_id,lifecycle_status);
