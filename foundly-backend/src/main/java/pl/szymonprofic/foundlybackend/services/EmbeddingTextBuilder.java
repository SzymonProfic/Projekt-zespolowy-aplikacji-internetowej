package pl.szymonprofic.foundlybackend.services;

import org.springframework.stereotype.Component;
import pl.szymonprofic.foundlybackend.dto.ReportContent;
import pl.szymonprofic.foundlybackend.models.enums.ReportType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Objects;

@Component
public class EmbeddingTextBuilder {
    private static final String PREPROCESSING_VERSION = "v1";

    public PreparedText build(ReportType type, ReportContent content) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        return build(type, content.title(), content.description(), content.color());
    }

    public PreparedText build(ReportType type, String title, String description, String itemColor) {
        Objects.requireNonNull(type, "type");
        String text = requiredText(title, "title") + "\n" + requiredText(description, "description");
        String color = itemColor == null ? "" : normalize(itemColor);
        if (!color.isEmpty()) text += "\nKolor: " + color;
        if (type == ReportType.LOST) text = "zapytanie: " + text;

        return new PreparedText(text, sha256(text), PREPROCESSING_VERSION);
    }

    private static String requiredText(String value, String field) {
        String normalized = value == null ? "" : normalize(value);
        if (normalized.isEmpty())
            throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .replaceAll("(?U)\\s+", " ").strip();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record PreparedText(String text, String inputHash, String preprocessingVersion) {}
}
