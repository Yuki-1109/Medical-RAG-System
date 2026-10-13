CREATE TABLE knowledge_bases (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name VARCHAR(100) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT knowledge_bases_name_not_blank CHECK (name !~ '^[[:space:]]*$'),
    CONSTRAINT knowledge_bases_description_length CHECK (char_length(description) <= 2000)
);

CREATE INDEX knowledge_bases_owner_updated_idx
    ON knowledge_bases (owner_id, updated_at DESC, id DESC);

-- Future document/image foreign keys must restrict deletion. No cascading data deletion.
