package pl.szymonprofic.foundlybackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("foundly.matching.ranking")
public record MatchingRankingProperties(String version, double semanticWeight, double locationWeight,
                                        double dateWeight, double categoryWeight, double distanceScaleMeters,
                                        double dateScaleDays, int earlyFoundToleranceDays,
                                        double earlyFoundScaleDays, double minSemanticScore, int maxResults) {
    public MatchingRankingProperties {
        if (version == null || version.isBlank() || version.length() > 40)
            throw new IllegalArgumentException("Ranking version must contain 1 to 40 characters");
        for (double weight : new double[]{semanticWeight, locationWeight, dateWeight, categoryWeight}) {
            if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("Ranking weights must be finite and nonnegative");
        }
        if (semanticWeight <= 0 || !Double.isFinite(semanticWeight + locationWeight + dateWeight + categoryWeight))
            throw new IllegalArgumentException("Semantic weight must be positive and total weight finite");
        for (double scale : new double[]{distanceScaleMeters, dateScaleDays, earlyFoundScaleDays}) {
            if (!Double.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("Ranking scales must be finite and positive");
        }
        if (earlyFoundToleranceDays < 0 || !Double.isFinite(minSemanticScore) || minSemanticScore < 0 || minSemanticScore > 1)
            throw new IllegalArgumentException("Invalid date tolerance or semantic threshold");
        if (maxResults < 1 || maxResults > 100) throw new IllegalArgumentException("Ranking max results must be between 1 and 100");
    }
}
