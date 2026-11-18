package pl.szymonprofic.foundlybackend.services;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import pl.szymonprofic.foundlybackend.config.EmbeddingClientConfig;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class EmbeddingClientTests {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestContentType = new AtomicReference<>();
    private final AtomicReference<String> requestContentLength = new AtomicReference<>();
    private final CountDownLatch received = new CountDownLatch(1);
    private final CountDownLatch releaseResponse = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService executor;
    private EmbeddingClient client;
    private String baseUrl;
    private int status = 200;
    private String body = vectorJson("1", 768);
    private String contentType = "application/json";
    private boolean blockResponse;
    private boolean blockBody;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                requestMethod.set(exchange.getRequestMethod());
                requestPath.set(exchange.getRequestURI().getPath());
                requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                requestContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                received.countDown();
                if (blockResponse) {
                    try {
                        if (!releaseResponse.await(5, TimeUnit.SECONDS)) return;
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                exchange.getResponseHeaders().set("Content-Type", contentType);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (blockBody) {
                    exchange.getResponseBody().write(bytes[0]);
                    exchange.getResponseBody().flush();
                    try {
                        releaseResponse.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = clientWithReadTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        releaseResponse.countDown();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void readsModelMetadataWithoutPostingTextForInference() {
        body = "{" + metadata() + "}";
        var result = client.modelInfo();
        assertThat(requestMethod.get()).isEqualTo("GET");
        assertThat(requestPath.get()).isEqualTo("/model");
        assertThat(requestBody.get()).isEmpty();
        assertThat(result).isEqualTo(new EmbeddingClient.ModelInfo("sdadas/mmlw-roberta-base", "a".repeat(40), 768));
    }

    @ParameterizedTest
    @MethodSource("invalidMetadata")
    void rejectsInvalidMetadataFromModelEndpoint(String field, String value) {
        ObjectNode response = (ObjectNode) json.readTree("{" + metadata() + "}");
        response.set(field, json.readTree(value));
        body = json.writeValueAsString(response);
        assertThatThrownBy(client::modelInfo).isInstanceOfSatisfying(EmbeddingClientException.class,
                exception -> assertThat(exception.isRetryable()).isFalse());
    }

    @Test
    void sendsPreparedTextAsUtf8JsonWithContentLengthAndReturnsVector() throws Exception {
        String text = "zapytanie: Czarny portfel\nSkórzany portfel z napisem \"Łódź\".";
        var result = client.generate(text);

        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestContentType.get()).startsWith("application/json");
        assertThat(Integer.parseInt(requestContentLength.get()))
                .isEqualTo(requestBody.get().getBytes(StandardCharsets.UTF_8).length);
        assertThat(json.readTree(requestBody.get()).size()).isEqualTo(1);
        assertThat(json.readTree(requestBody.get()).get("description").asText()).isEqualTo(text);
        assertThat(result.numbers()).hasSize(768);
        assertThat(result.numbers().getFirst()).isEqualTo(1.0);
        assertThat(result.numbers().subList(1, 768)).containsOnly(0.0);
        assertThat(result.modelId()).isEqualTo("sdadas/mmlw-roberta-base");
        assertThat(result.modelRevision()).isEqualTo("a".repeat(40));
        assertThat(result.dimensions()).isEqualTo(768);
    }

    @Test
    void returnsTheRevisionReportedByTheService() {
        body = body.replace("a".repeat(40), "b".repeat(40));
        assertThat(client.generate("Portfel").modelRevision()).isEqualTo("b".repeat(40));
    }

    @ParameterizedTest
    @ValueSource(strings = {"model_id", "model_revision", "dimensions"})
    void rejectsMissingModelMetadata(String field) {
        ObjectNode response = (ObjectNode) json.readTree(body);
        response.remove(field);
        body = json.writeValueAsString(response);
        assertNonRetryableFailure();
    }

    @ParameterizedTest
    @MethodSource("invalidMetadata")
    void rejectsInvalidModelMetadata(String field, String value) {
        ObjectNode response = (ObjectNode) json.readTree(body);
        response.set(field, json.readTree(value));
        body = json.writeValueAsString(response);
        assertNonRetryableFailure();
    }

    private static Stream<Arguments> invalidMetadata() {
        return Stream.of(
                Arguments.of("model_id", "null"),
                Arguments.of("model_id", "123"),
                Arguments.of("model_id", "\"\""),
                Arguments.of("model_id", "\"different/model\""),
                Arguments.of("model_revision", "null"),
                Arguments.of("model_revision", "123"),
                Arguments.of("model_revision", "\"\""),
                Arguments.of("model_revision", "\"main\""),
                Arguments.of("model_revision", "\"abc123\""),
                Arguments.of("model_revision", "\"" + "z".repeat(40) + "\""),
                Arguments.of("dimensions", "null"),
                Arguments.of("dimensions", "\"768\""),
                Arguments.of("dimensions", "768.0"),
                Arguments.of("dimensions", "1024"),
                Arguments.of("dimensions", "4294968064"));
    }

    @Test
    void acceptsDistributedFloatPrecisionVector() {
        body = "{" + metadata() + ",\"numbers\":[" + String.join(",", Collections.nCopies(768, "0.03608439")) + "]}";
        assertThat(client.generate("Portfel").numbers()).hasSize(768);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 767, 769})
    void rejectsWrongDimensions(int dimensions) {
        body = vectorJson("1", dimensions);
        assertNonRetryableFailure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "{\"numbers\":null}", "{\"numbers\":{}}", "broken json"})
    void rejectsEmptyMalformedOrMissingVector(String response) {
        body = response;
        assertNonRetryableFailure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"1\"", "true", "{}", "1e309", "NaN"})
    void rejectsNonNumericOrNonFiniteValues(String value) {
        body = vectorJson(value, 768);
        assertNonRetryableFailure();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "1.01"})
    void rejectsZeroOrUnnormalizedVector(String value) {
        body = vectorJson(value, 768);
        assertNonRetryableFailure();
    }

    @Test
    void rejectsUnsupportedResponseContentType() {
        contentType = "text/html";
        body = "<html>Service unavailable</html>";
        assertNonRetryableFailure();
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void transientHttpFailuresCanBeRetried(int responseStatus) {
        status = responseStatus;
        body = "{\"error\":\"Embedding failed\"}";
        assertThatThrownBy(() -> client.generate("Portfel"))
                .isInstanceOfSatisfying(EmbeddingClientException.class,
                        exception -> assertThat(exception.isRetryable()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 415})
    void permanentHttpFailuresAreNotRetried(int responseStatus) {
        status = responseStatus;
        body = "{\"error\":\"Invalid request\"}";
        assertNonRetryableFailure();
    }

    @Test
    void unavailableServiceIsRetryable() {
        server.stop(0);
        assertThatThrownBy(() -> client.generate("Portfel"))
                .isInstanceOfSatisfying(EmbeddingClientException.class,
                        exception -> assertThat(exception.isRetryable()).isTrue());
    }

    @Test
    void readTimeoutIsRetryable() throws Exception {
        blockResponse = true;
        client = clientWithReadTimeout(Duration.ofMillis(100));
        try {
            assertThatThrownBy(() -> client.generate("Portfel"))
                    .isInstanceOfSatisfying(EmbeddingClientException.class,
                            exception -> assertThat(exception.isRetryable()).isTrue());
            assertThat(received.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseResponse.countDown();
        }
    }

    @Test
    void timeoutWhileReadingResponseBodyIsRetryable() throws Exception {
        blockBody = true;
        client = clientWithReadTimeout(Duration.ofMillis(100));
        try {
            assertThatThrownBy(() -> client.generate("Portfel"))
                    .isInstanceOfSatisfying(EmbeddingClientException.class,
                            exception -> assertThat(exception.isRetryable()).isTrue());
            assertThat(received.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseResponse.countDown();
        }
    }

    @Test
    void blankInputDoesNotCallService() {
        for (String text : Arrays.asList(null, "", " \n")) {
            assertThatIllegalArgumentException().isThrownBy(() -> client.generate(text));
        }
        assertThat(received.getCount()).isEqualTo(1);
    }

    @Test
    void infiniteTimeoutConfigurationIsRejected() {
        var config = new EmbeddingClientConfig();
        assertThatIllegalArgumentException().isThrownBy(() ->
                config.embeddingRestClient(baseUrl, Duration.ZERO, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                config.embeddingRestClient(baseUrl, Duration.ofSeconds(1), Duration.ZERO));
    }

    private EmbeddingClient clientWithReadTimeout(Duration timeout) {
        return new EmbeddingClient(new EmbeddingClientConfig()
                .embeddingRestClient(baseUrl, Duration.ofSeconds(1), timeout), json);
    }

    private void assertNonRetryableFailure() {
        assertThatThrownBy(() -> client.generate("Portfel"))
                .isInstanceOfSatisfying(EmbeddingClientException.class,
                        exception -> assertThat(exception.isRetryable()).isFalse());
    }

    private static String vectorJson(String first, int dimensions) {
        String values = dimensions == 0 ? "" : first;
        if (dimensions > 1) values += "," + String.join(",", Collections.nCopies(dimensions - 1, "0"));
        return "{" + metadata() + ",\"description\":\"Portfel\",\"numbers\":[" + values + "]}";
    }

    private static String metadata() {
        return "\"model_id\":\"sdadas/mmlw-roberta-base\",\"model_revision\":\"" + "a".repeat(40)
                + "\",\"dimensions\":768";
    }
}
