package pl.szymonprofic.foundlybackend.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.szymonprofic.foundlybackend.models.ClaimedMatchingJob;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MatchingJobRepository {
    private final JdbcTemplate jdbc;

    public boolean enqueue(UUID reportId, long reportVersion, String pipelineVersion, Instant now) {
        return jdbc.update("""
                INSERT INTO matching_job
                    (id, report_id, report_version, pipeline_version, available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (report_id, report_version, pipeline_version) DO NOTHING
                """, UUID.randomUUID(), reportId, reportVersion, pipelineVersion,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    public void expireExhausted(String pipelineVersion, int maxAttempts, Instant now) {
        jdbc.update("""
                WITH exhausted AS (
                    SELECT id FROM matching_job
                    WHERE pipeline_version = ? AND attempts >= ? AND (
                        (status = 'PENDING' AND available_at <= ?)
                        OR (status = 'PROCESSING' AND locked_until <= ?)
                    )
                    ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED
                )
                UPDATE matching_job AS job SET status = 'DEAD', locked_until = NULL, lock_token = NULL,
                    updated_at = ?, last_error = 'ATTEMPTS_EXHAUSTED'
                FROM exhausted WHERE job.id = exhausted.id
                """, pipelineVersion, maxAttempts, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<ClaimedMatchingJob> claimNext(String pipelineVersion, Instant now, Instant lockedUntil, int maxAttempts) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM matching_job
                    WHERE pipeline_version = ? AND attempts < ? AND (
                        (status = 'PENDING' AND available_at <= ?)
                        OR (status = 'PROCESSING' AND locked_until <= ?)
                    )
                    ORDER BY CASE WHEN status = 'PROCESSING' THEN locked_until ELSE available_at END,
                             created_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE matching_job AS job SET
                    status = 'PROCESSING', attempts = job.attempts + 1,
                    locked_until = ?, lock_token = ?, updated_at = ?
                FROM candidate WHERE job.id = candidate.id
                RETURNING job.id, job.report_id, job.report_version, job.pipeline_version,
                          job.attempts, job.locked_until, job.lock_token
                """, (row, index) -> new ClaimedMatchingJob(
                row.getObject("id", UUID.class), row.getObject("report_id", UUID.class),
                row.getLong("report_version"), row.getString("pipeline_version"),
                row.getInt("attempts"), row.getTimestamp("locked_until").toInstant(),
                row.getObject("lock_token", UUID.class)), pipelineVersion, maxAttempts,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(lockedUntil),
                UUID.randomUUID(), Timestamp.from(now)).stream().findFirst();
    }

    public boolean complete(UUID jobId, UUID lockToken, Instant now) {
        return jdbc.update("""
                UPDATE matching_job SET status = 'COMPLETED', locked_until = NULL,
                    lock_token = NULL, updated_at = ?, last_error = NULL
                WHERE id = ? AND status = 'PROCESSING' AND lock_token = ? AND locked_until > ?
                """, Timestamp.from(now), jobId, lockToken, Timestamp.from(now)) == 1;
    }

    public boolean transition(ClaimedMatchingJob job, String status, Instant availableAt, String errorCode, Instant now) {
        return jdbc.update("""
                UPDATE matching_job SET status = ?, available_at = ?, locked_until = NULL,
                    lock_token = NULL, last_error = ?, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND lock_token = ? AND locked_until > ?
                """, status, Timestamp.from(availableAt), errorCode, Timestamp.from(now),
                job.id(), job.lockToken(), Timestamp.from(now)) == 1;
    }
}
