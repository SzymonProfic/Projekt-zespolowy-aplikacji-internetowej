-- v1 generated embeddings only. Keep its history, invalidate outstanding leases,
-- and schedule embedding reuse/generation plus ranking for every active report.
UPDATE matching_job
SET status = 'SUPERSEDED', lock_token = NULL, locked_until = NULL,
    updated_at = CURRENT_TIMESTAMP, last_error = 'PIPELINE_UPGRADED'
WHERE pipeline_version = 'v1' AND status IN ('PENDING', 'PROCESSING');

INSERT INTO matching_job
    (id, report_id, report_version, pipeline_version, available_at, created_at, updated_at)
SELECT gen_random_uuid(), id, version, 'v2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM report
WHERE status = 'ACTIVE'
ON CONFLICT (report_id, report_version, pipeline_version) DO NOTHING;
