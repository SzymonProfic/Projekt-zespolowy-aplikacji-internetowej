CREATE TABLE report_match (
    id uuid PRIMARY KEY,
    lost_report_id uuid NOT NULL REFERENCES report(id) ON DELETE CASCADE,
    found_report_id uuid NOT NULL REFERENCES report(id) ON DELETE CASCADE,
    lost_report_version bigint NOT NULL CHECK (lost_report_version >= 0),
    found_report_version bigint NOT NULL CHECK (found_report_version >= 0),
    lost_input_hash varchar(64) NOT NULL CHECK (lost_input_hash ~ '^[0-9a-f]{64}$'),
    found_input_hash varchar(64) NOT NULL CHECK (found_input_hash ~ '^[0-9a-f]{64}$'),
    lost_embedding_updated_at timestamptz NOT NULL,
    found_embedding_updated_at timestamptz NOT NULL,
    model_id varchar(255) NOT NULL CHECK (length(trim(model_id)) > 0),
    model_revision varchar(40) NOT NULL CHECK (model_revision ~ '^[0-9a-f]{40}$'),
    preprocessing_version varchar(40) NOT NULL CHECK (length(trim(preprocessing_version)) > 0),
    ranking_version varchar(40) NOT NULL CHECK (length(trim(ranking_version)) > 0),
    semantic_score double precision NOT NULL CHECK (semantic_score BETWEEN 0 AND 1),
    location_score double precision CHECK (location_score BETWEEN 0 AND 1),
    date_score double precision CHECK (date_score BETWEEN 0 AND 1),
    category_score double precision CHECK (category_score BETWEEN 0 AND 1),
    final_score double precision NOT NULL CHECK (final_score BETWEEN 0 AND 1),
    evidence_coverage double precision NOT NULL CHECK (evidence_coverage BETWEEN 0 AND 1),
    distance_meters double precision CHECK (distance_meters >= 0 AND distance_meters < 'Infinity'::double precision),
    days_after_loss bigint,
    status varchar(12) NOT NULL DEFAULT 'PROPOSED'
        CHECK (status IN ('PROPOSED', 'CONFIRMED', 'REJECTED', 'EXPIRED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_report_match_pair UNIQUE (lost_report_id, found_report_id),
    CONSTRAINT ck_report_match_different_reports CHECK (lost_report_id <> found_report_id)
);

CREATE INDEX ix_report_match_lost ON report_match(lost_report_id, status, final_score DESC, id);
CREATE INDEX ix_report_match_found ON report_match(found_report_id, status, final_score DESC, id);
