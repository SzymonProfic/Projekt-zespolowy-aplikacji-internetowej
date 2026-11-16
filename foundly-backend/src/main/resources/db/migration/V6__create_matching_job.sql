CREATE TABLE matching_job (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES report(id) ON DELETE CASCADE,
    report_version bigint NOT NULL CHECK (report_version >= 0),
    pipeline_version varchar(40) NOT NULL CHECK (length(trim(pipeline_version)) > 0),
    status varchar(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD', 'SUPERSEDED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL,
    locked_until timestamptz,
    lock_token uuid,
    last_error varchar(1000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_matching_job_report_version UNIQUE (report_id, report_version, pipeline_version),
    CONSTRAINT ck_matching_job_lease CHECK (
        (status = 'PROCESSING' AND locked_until IS NOT NULL AND lock_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND locked_until IS NULL AND lock_token IS NULL)
    )
);

CREATE INDEX ix_matching_job_pending ON matching_job(available_at, created_at, id)
    WHERE status = 'PENDING';
CREATE INDEX ix_matching_job_expired_lease ON matching_job(locked_until)
    WHERE status = 'PROCESSING';
