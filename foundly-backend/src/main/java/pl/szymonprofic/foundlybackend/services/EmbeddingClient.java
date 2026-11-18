package pl.szymonprofic.foundlybackend.services;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddingClient {
    private static final int DIMENSIONS = 768;
    private static final String MODEL_ID = "sdadas/mmlw-roberta-base";
    private static final double NORM_TOLERANCE = 0.001;
    private final RestClient client;
    private final ObjectMapper json;

    public EmbeddingClient(@Qualifier("embeddingRestClient") RestClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    public EmbeddingResult generate(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding text must not be blank");

        // The Python server requires Content-Length rather than a chunked request body.
        byte[] payload = json.writeValueAsBytes(Map.of("description", text));
        return validate(readResponse(client.post().uri("/")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .contentLength(payload.length).body(payload)));
    }

    public ModelInfo modelInfo() {
        return validateModelInfo(readResponse(client.get().uri("/model").accept(MediaType.APPLICATION_JSON)));
    }

    private JsonNode readResponse(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (httpRequest, result) -> {
                        int status = result.getStatusCode().value();
                        throw new EmbeddingClientException("Embedding service returned HTTP " + status,
                                status >= 500 || status == 429 || status == 408);
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException exception) {
            throw new EmbeddingClientException("Embedding service connection failed or timed out", true, exception);
        } catch (RestClientException exception) {
            // A timeout or broken connection can also occur while reading the response body.
            boolean transportFailure = false;
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof IOException) transportFailure = true;
            }
            throw new EmbeddingClientException("Could not read embedding service response", transportFailure, exception);
        }
    }

    private EmbeddingResult validate(JsonNode response) {
        JsonNode numbers = response == null ? null : response.get("numbers");
        if (numbers == null || !numbers.isArray() || numbers.size() != DIMENSIONS)
            throw new EmbeddingClientException("Embedding response must contain 768 numbers", false);

        ModelInfo model = validateModelInfo(response);

        var vector = new ArrayList<Double>(DIMENSIONS);
        double squaredNorm = 0;
        for (JsonNode number : numbers) {
            if (!number.isNumber() || !Double.isFinite(number.doubleValue()))
                throw new EmbeddingClientException("Embedding contains a non-finite or non-numeric value", false);
            double value = number.doubleValue();
            vector.add(value);
            squaredNorm += value * value;
        }
        double norm = Math.sqrt(squaredNorm);
        if (!Double.isFinite(norm) || Math.abs(norm - 1.0) > NORM_TOLERANCE)
            throw new EmbeddingClientException("Embedding must have unit norm within tolerance 0.001", false);
        return new EmbeddingResult(vector, model.modelId(), model.modelRevision(), model.dimensions());
    }

    private ModelInfo validateModelInfo(JsonNode response) {
        JsonNode modelId = response == null ? null : response.get("model_id");
        JsonNode modelRevision = response == null ? null : response.get("model_revision");
        JsonNode dimensions = response == null ? null : response.get("dimensions");
        if (modelId == null || !modelId.isString() || !MODEL_ID.equals(modelId.asText()))
            throw new EmbeddingClientException("Embedding response contains an unsupported model_id", false);
        if (modelRevision == null || !modelRevision.isString()
                || !modelRevision.asText().matches("[0-9a-f]{40}"))
            throw new EmbeddingClientException("Embedding model_revision must be a full commit hash", false);
        if (dimensions == null || !dimensions.isIntegralNumber() || !dimensions.canConvertToInt()
                || dimensions.intValue() != DIMENSIONS)
            throw new EmbeddingClientException("Embedding dimensions metadata must equal 768", false);
        return new ModelInfo(modelId.asText(), modelRevision.asText(), dimensions.intValue());
    }

    public record ModelInfo(String modelId, String modelRevision, int dimensions) {}

    public record EmbeddingResult(List<Double> numbers, String modelId, String modelRevision, int dimensions) {
        public EmbeddingResult {
            numbers = List.copyOf(numbers);
        }
    }
}
