package pl.szymonprofic.foundlybackend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "foundly.matching.enabled", havingValue = "true", matchIfMissing = true)
public class MatchingSchedulingConfig {}
