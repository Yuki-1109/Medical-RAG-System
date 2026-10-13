CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(60) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'ADMIN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    singleton_key BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT users_single_administrator UNIQUE (singleton_key),
    CONSTRAINT users_singleton_true CHECK (singleton_key),
    CONSTRAINT users_admin_role CHECK (role = 'ADMIN'),
    CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    CONSTRAINT users_bcrypt_hash CHECK (password_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$')
);
