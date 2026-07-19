CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_app_users_normalized_username
    ON app_users (lower(username));

CREATE TABLE app_roles (
    name VARCHAR(64) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user_roles (
    user_id UUID NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    role_name VARCHAR(64) NOT NULL REFERENCES app_roles (name) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_name)
);

INSERT INTO app_roles (name)
VALUES ('USER'), ('ADMIN');
