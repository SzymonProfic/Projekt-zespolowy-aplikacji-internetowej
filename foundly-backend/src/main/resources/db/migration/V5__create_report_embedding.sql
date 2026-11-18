CREATE TABLE report_embedding (
    report_id uuid PRIMARY KEY REFERENCES report(id) ON DELETE CASCADE,
    embedding vector(768) NOT NULL,
    model_id varchar(255) NOT NULL CHECK (length(trim(model_id)) > 0),
    model_revision varchar(128) NOT NULL CHECK (length(trim(model_revision)) > 0),
    preprocessing_version varchar(40) NOT NULL CHECK (length(trim(preprocessing_version)) > 0),
    input_hash varchar(64) NOT NULL CHECK (input_hash ~ '^[0-9a-f]{64}$'),
    updated_at timestamptz NOT NULL
);
