package pl.szymonprofic.foundlybackend.models;

import java.time.Instant;
import java.util.UUID;

public record ClaimedMatchingJob(UUID id, UUID reportId, long reportVersion, String pipelineVersion,
                                 int attempts, Instant lockedUntil, UUID lockToken) {}
