package pl.szymonprofic.foundlybackend.services;

import org.junit.jupiter.api.Test;
import pl.szymonprofic.foundlybackend.config.MatchingRankingProperties;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class MatchingScoreCalculatorTests {
    private final MatchingScoreCalculator calculator = new MatchingScoreCalculator(policy());
    private final LocalDate lost = LocalDate.of(2026, 1, 1);

    @Test
    void combinesFourSignalsWithConfiguredWeights() {
        var score = calculator.calculate(0.8, 0.0, lost, lost, "KEYS", "KEYS");
        assertThat(score.semantic()).isEqualTo(0.8);
        assertThat(score.location()).isEqualTo(1.0);
        assertThat(score.date()).isEqualTo(1.0);
        assertThat(score.category()).isEqualTo(1.0);
        assertThat(score.finalScore()).isCloseTo(0.88, within(1e-12));
        assertThat(score.evidenceCoverage()).isEqualTo(1.0);
    }

    @Test
    void distanceAndElapsedDaysDecaySmoothlyAndCategoryMismatchIsOnlyAPenalty() {
        var score = calculator.calculate(0.9, 10_000.0, lost, lost.plusDays(14), "KEYS", "ELECTRONICS");
        assertThat(score.location()).isCloseTo(Math.exp(-1), within(1e-12));
        assertThat(score.date()).isCloseTo(Math.exp(-1), within(1e-12));
        assertThat(score.category()).isEqualTo(0.0);
        assertThat(score.finalScore()).isCloseTo(0.54 + 0.3 * Math.exp(-1), within(1e-12));
    }

    @Test
    void dateScoringUsesLostFoundChronologyWithToleranceForApproximateDates() {
        assertThat(calculator.calculate(1, 0.0, lost, lost.minusDays(2), "KEYS", "KEYS").date()).isEqualTo(1.0);
        assertThat(calculator.calculate(1, 0.0, lost, lost.minusDays(4), "KEYS", "KEYS").date())
                .isCloseTo(Math.exp(-1), within(1e-12));
        assertThat(calculator.calculate(1, 0.0, lost, lost.plusDays(4), "KEYS", "KEYS").date())
                .isGreaterThan(calculator.calculate(1, 0.0, lost, lost.minusDays(4), "KEYS", "KEYS").date());
    }

    @Test
    void missingSignalsAreExcludedAndTheirWeightsAreRenormalized() {
        var score = calculator.calculate(0.8, null, null, lost, "OTHER", "KEYS");
        assertThat(score.location()).isNull();
        assertThat(score.date()).isNull();
        assertThat(score.category()).isNull();
        assertThat(score.finalScore()).isCloseTo(0.8, within(1e-12));
        assertThat(score.evidenceCoverage()).isCloseTo(0.6, within(1e-12));
    }

    @Test
    void otherCategoryDoesNotEarnAnExactCategoryBonus() {
        var score = calculator.calculate(0.8, 0.0, lost, lost, "OTHER", "OTHER");
        assertThat(score.category()).isNull();
        assertThat(score.finalScore()).isCloseTo(0.78 / 0.9, within(1e-12));
        assertThat(score.evidenceCoverage()).isCloseTo(0.9, within(1e-12));
    }

    @Test
    void clampsSemanticSimilarityAndRejectsInvalidNumericInput() {
        assertThat(calculator.calculate(1.0000001, null, null, null, null, null).finalScore()).isEqualTo(1.0);
        assertThat(calculator.calculate(-0.1, null, null, null, null, null).finalScore()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> calculator.calculate(Double.NaN, null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> calculator.calculate(1, Double.POSITIVE_INFINITY, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> calculator.calculate(1, -1.0, null, null, null, null));
    }

    @Test
    void customWeightsChangeTheRankingFormula() {
        var custom = new MatchingScoreCalculator(new MatchingRankingProperties("test", 1, 0, 0, 0,
                10_000, 14, 2, 2, 0.5, 10));
        assertThat(custom.calculate(0.7, 0.0, lost, lost, "KEYS", "KEYS").finalScore()).isEqualTo(0.7);
    }

    @Test
    void invalidPolicyIsRejectedBeforeItCanBeUsed() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MatchingRankingProperties("v1", 0, 1, 0, 0,
                10_000, 14, 2, 2, 0.5, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new MatchingRankingProperties("v1", 0.6, -1, 0.1, 0.1,
                10_000, 14, 2, 2, 0.5, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new MatchingRankingProperties("v1", 0.6, 0.2, 0.1, 0.1,
                0, 14, 2, 2, 0.5, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> new MatchingRankingProperties("v1", 0.6, 0.2, 0.1, 0.1,
                10_000, 14, 2, 2, 1.1, 10));
    }

    private MatchingRankingProperties policy() {
        return new MatchingRankingProperties("v1", 0.6, 0.2, 0.1, 0.1, 10_000, 14, 2, 2, 0.5, 10);
    }
}
