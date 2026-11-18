package pl.szymonprofic.foundlybackend.services;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "foundly.matching.enabled", havingValue = "true", matchIfMissing = true)
public class MatchingScheduler {
    private static final Logger log = LoggerFactory.getLogger(MatchingScheduler.class);
    private final MatchingWorker worker;

    @Scheduled(fixedDelayString = "${foundly.matching.poll-interval}",
            initialDelayString = "${foundly.matching.poll-interval}")
    public void poll() {
        try {
            worker.processNext();
        } catch (RuntimeException exception) {
            log.warn("Matching scheduler failed; error type={}", exception.getClass().getSimpleName());
        }
    }
}
