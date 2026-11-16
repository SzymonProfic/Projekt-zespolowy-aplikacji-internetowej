package pl.szymonprofic.foundlybackend.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.szymonprofic.foundlybackend.config.MatchingRankingProperties;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class MatchingScoreCalculator {
    private final MatchingRankingProperties policy;

    public Scores calculate(double similarity, Double distanceMeters, LocalDate lostDate, LocalDate foundDate,
                            String sourceCategory, String candidateCategory) {
        if (!Double.isFinite(similarity)) throw new IllegalArgumentException("Similarity must be finite");
        if (distanceMeters != null && (!Double.isFinite(distanceMeters) || distanceMeters < 0))
            throw new IllegalArgumentException("Distance must be finite and nonnegative");
        double semantic = Math.clamp(similarity, 0, 1);
        Double location = distanceMeters == null ? null : Math.exp(-distanceMeters / policy.distanceScaleMeters());
        Double date = null;
        if (lostDate != null && foundDate != null) {
            long days = ChronoUnit.DAYS.between(lostDate, foundDate);
            date = days >= 0 ? Math.exp(-days / policy.dateScaleDays())
                    : Math.exp(-Math.max(0, -days - policy.earlyFoundToleranceDays()) / policy.earlyFoundScaleDays());
        }
        Double category = unknownCategory(sourceCategory) || unknownCategory(candidateCategory)
                ? null : (sourceCategory.equals(candidateCategory) ? 1.0 : 0.0);
        double weight = policy.semanticWeight();
        double weighted = semantic * weight;
        if (location != null) { weight += policy.locationWeight(); weighted += location * policy.locationWeight(); }
        if (date != null) { weight += policy.dateWeight(); weighted += date * policy.dateWeight(); }
        if (category != null) { weight += policy.categoryWeight(); weighted += category * policy.categoryWeight(); }
        double allWeights = policy.semanticWeight() + policy.locationWeight() + policy.dateWeight() + policy.categoryWeight();
        return new Scores(semantic, location, date, category, Math.clamp(weighted / weight, 0, 1), weight / allWeights);
    }

    private boolean unknownCategory(String code) {
        return code == null || code.isBlank() || "OTHER".equals(code);
    }

    public record Scores(double semantic, Double location, Double date, Double category,
                         double finalScore, double evidenceCoverage) {}
}
