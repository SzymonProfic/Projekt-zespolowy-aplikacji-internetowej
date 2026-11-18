package pl.szymonprofic.foundlybackend.services;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MatchingWorker {
    private static final Logger log = LoggerFactory.getLogger(MatchingWorker.class);
    private final MatchingJobService jobs;
    private final EmbeddingJobProcessor processor;
    private final EmbeddingClient client;

    public boolean processNext() {
        var next = jobs.claimNext();
        if (next.isEmpty()) return false;
        var job = next.get();
        try {
            var prepared = processor.prepare(job);
            if (prepared.isEmpty()) {
                jobs.supersede(job);
                return true;
            }
            var model = client.modelInfo();
            if (processor.completeIfCurrent(job, prepared.get(), model)) return true;
            var result = client.generate(prepared.get().text());
            processor.publish(job, prepared.get(), result);
        } catch (EmbeddingJobProcessor.LostLeaseException exception) {
            log.info("Discarded result for job {} after lease loss", job.id());
        } catch (EmbeddingClientException exception) {
            jobs.fail(job, exception.isRetryable(), "EMBEDDING_SERVICE_ERROR");
            log.warn("Embedding request failed for job {}; retryable={}", job.id(), exception.isRetryable());
        } catch (RuntimeException exception) {
            jobs.fail(job, true, "PROCESSING_ERROR");
            // Do not log request text, vectors or exception messages containing user data.
            log.warn("Processing failed for job {}; error type={}", job.id(), exception.getClass().getSimpleName());
        }
        return true;
    }
}
