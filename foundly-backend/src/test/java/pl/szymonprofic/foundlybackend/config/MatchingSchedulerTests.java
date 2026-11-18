package pl.szymonprofic.foundlybackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.szymonprofic.foundlybackend.services.MatchingScheduler;
import pl.szymonprofic.foundlybackend.services.MatchingWorker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchingSchedulerTests {
    @Test
    void enabledSchedulerInvokesWorkerAutomatically() {
        var worker = mock(MatchingWorker.class);
        var called = new CountDownLatch(1);
        when(worker.processNext()).thenAnswer(invocation -> { called.countDown(); return false; });

        new ApplicationContextRunner()
                .withUserConfiguration(MatchingSchedulingConfig.class, MatchingScheduler.class)
                .withBean(MatchingWorker.class, () -> worker)
                .withPropertyValues("foundly.matching.enabled=true", "foundly.matching.poll-interval=10ms")
                .run(context -> {
                    assertThat(context).hasSingleBean(MatchingScheduler.class);
                    assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
                });
    }

    @Test
    void disabledSchedulerDoesNotInvokeWorker() {
        var worker = mock(MatchingWorker.class);
        new ApplicationContextRunner()
                .withUserConfiguration(MatchingSchedulingConfig.class, MatchingScheduler.class)
                .withBean(MatchingWorker.class, () -> worker)
                .withPropertyValues("foundly.matching.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MatchingScheduler.class);
                    verifyNoInteractions(worker);
                });
    }
}
