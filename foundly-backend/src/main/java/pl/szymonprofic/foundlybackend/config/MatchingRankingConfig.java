package pl.szymonprofic.foundlybackend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MatchingRankingProperties.class)
public class MatchingRankingConfig {}
