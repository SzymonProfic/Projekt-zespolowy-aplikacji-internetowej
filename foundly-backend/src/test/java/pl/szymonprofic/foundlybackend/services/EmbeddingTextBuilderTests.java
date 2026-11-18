package pl.szymonprofic.foundlybackend.services;

import org.junit.jupiter.api.Test;
import pl.szymonprofic.foundlybackend.dto.ReportContent;
import pl.szymonprofic.foundlybackend.dto.ReportLocation;
import pl.szymonprofic.foundlybackend.models.enums.ReportType;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class EmbeddingTextBuilderTests {
    private final EmbeddingTextBuilder builder = new EmbeddingTextBuilder();

    @Test
    void foundTextContainsItemFeaturesAndKnownUtf8Hash() {
        var result = builder.build(ReportType.FOUND,
                content("Czarny portfel", "Skórzany portfel bez logo.", "czarny"));

        assertThat(result.text()).isEqualTo("Czarny portfel\nSkórzany portfel bez logo.\nKolor: czarny");
        assertThat(result.inputHash()).isEqualTo("85e6217822c76b6ebd87f1ad36c7e287599cb6a3f995673066858950407acd44");
        assertThat(result.preprocessingVersion()).isEqualTo("v1");
    }

    @Test
    void lostTextUsesQueryPrefixAndHasDifferentHashFromFound() {
        var content = content("Czarny portfel", "Skórzany portfel bez logo.", "czarny");
        var found = builder.build(ReportType.FOUND, content);
        var lost = builder.build(ReportType.LOST, content);

        assertThat(lost.text()).isEqualTo("zapytanie: " + found.text());
        assertThat(lost.inputHash()).isNotEqualTo(found.inputHash());
    }

    @Test
    void missingOrBlankColorDoesNotAddAnEmptyLine() {
        var expected = builder.build(ReportType.FOUND, content("Portfel", "Skórzany portfel.", null));
        for (String color : Arrays.asList(null, "", "  \t\n", "\u00a0")) {
            assertThat(builder.build(ReportType.FOUND, content("Portfel", "Skórzany portfel.", color)))
                    .isEqualTo(expected);
        }
        assertThat(expected.text()).isEqualTo("Portfel\nSkórzany portfel.");
    }

    @Test
    void whitespaceAndEquivalentUnicodeProduceTheSameTextAndHash() {
        var canonical = content("Czarny portfel", "Skórzany portfel bez logo.", "czarny");
        var formatted = content("  Czarny\u00a0 portfel\t", " Sko\u0301rzany\nportfel\tbez  logo. ", " czarny\n");

        assertThat(builder.build(ReportType.LOST, formatted))
                .isEqualTo(builder.build(ReportType.LOST, canonical));
    }

    @Test
    void changingTitleDescriptionOrColorChangesHash() {
        var original = builder.build(ReportType.FOUND,
                content("Czarny portfel", "Skórzany portfel bez logo.", "czarny"));
        var changes = List.of(
                content("Brązowy portfel", "Skórzany portfel bez logo.", "czarny"),
                content("Czarny portfel", "Skórzany portfel z logo.", "czarny"),
                content("Czarny portfel", "Skórzany portfel bez logo.", "brązowy"));

        for (var changed : changes) {
            assertThat(builder.build(ReportType.FOUND, changed).inputHash())
                    .isNotEqualTo(original.inputHash());
        }
    }

    @Test
    void categoryLocationAndDateDoNotAffectTextOrHash() {
        var original = content("Czarny portfel", "Skórzany portfel bez logo.", "czarny");
        var changed = new ReportContent(original.title(), "OTHER", original.description(), original.color(),
                LocalDate.of(2026, 2, 15), new ReportLocation("Warszawa", 21.0122, 52.2297));

        assertThat(builder.build(ReportType.LOST, changed))
                .isEqualTo(builder.build(ReportType.LOST, original));
    }

    @Test
    void missingOrBlankTitleAndDescriptionAreRejected() {
        for (String invalid : Arrays.asList(null, "", "\u00a0")) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    builder.build(ReportType.FOUND, content(invalid, "Skórzany portfel.", null)));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    builder.build(ReportType.FOUND, content("Portfel", invalid, null)));
        }
    }

    private ReportContent content(String title, String description, String color) {
        return new ReportContent(title, "WALLETS_DOCUMENTS", description, color,
                LocalDate.of(2026, 1, 1), new ReportLocation("Kraków", 19.937, 50.061));
    }
}
