package pl.szymonprofic.foundlybackend.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.szymonprofic.foundlybackend.repositories.MatchingJobRepository;
import pl.szymonprofic.foundlybackend.models.ClaimedMatchingJob;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class MatchingJobService {
    private static final String PIPELINE_VERSION = "v2";
    private final MatchingJobRepository jobs;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration retryDelay;
    private final int maxAttempts;

    public MatchingJobService(MatchingJobRepository jobs, Clock clock,
                             @Value("${foundly.matching.lease-duration}") Duration leaseDuration,
                             @Value("${foundly.matching.retry-delay}") Duration retryDelay,
                             @Value("${foundly.matching.max-attempts}") int maxAttempts) {
        if (leaseDuration.toMillis() <= 0)
            throw new IllegalArgumentException("Matching job lease must be at least 1 ms");
        if (retryDelay.toMillis() <= 0 || maxAttempts < 1)
            throw new IllegalArgumentException("Retry delay must be positive and max attempts at least 1");
        this.jobs = jobs;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        this.retryDelay = retryDelay;
        this.maxAttempts = maxAttempts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean schedule(UUID reportId, long reportVersion) {
        return jobs.enqueue(reportId, reportVersion, PIPELINE_VERSION, clock.instant());
    }

    // Commit the claim before the caller starts any slow AI work.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedMatchingJob> claimNext() {
        var now = clock.instant();
        jobs.expireExhausted(PIPELINE_VERSION, maxAttempts, now);
        return jobs.claimNext(PIPELINE_VERSION, now, now.plus(leaseDuration), maxAttempts);
    }

    // The future worker must save its result and complete the job in the same transaction.
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(ClaimedMatchingJob job) {
        return jobs.complete(job.id(), job.lockToken(), clock.instant());
    }

    @Transactional
    public boolean supersede(ClaimedMatchingJob job) {
        var now = clock.instant();
        return jobs.transition(job, "SUPERSEDED", now, "REPORT_CHANGED_OR_INACTIVE", now);
    }

    @Transactional
    public boolean fail(ClaimedMatchingJob job, boolean retryable, String errorCode) {
        var now = clock.instant();
        boolean retry = retryable && job.attempts() < maxAttempts;
        double delay = retryDelay.toMillis() * Math.pow(2, Math.min(job.attempts() - 1, 6));
        long delayMillis = Math.max(1, Math.round(Math.min(300_000,
                delay * ThreadLocalRandom.current().nextDouble(0.8, 1.2))));
        return jobs.transition(job, retry ? "PENDING" : "DEAD",
                retry ? now.plusMillis(delayMillis) : now, errorCode, now);
    }
}
