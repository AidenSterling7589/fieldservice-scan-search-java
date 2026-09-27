package example.fieldservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InfraiDocuments {
    private final String key = requireKey();
    private final String base;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();
    private final OpenAIClient embeddings;

    public InfraiDocuments(@Value("${fieldservice.base-url}") String base, ObjectMapper json) {
        this.base = base.replaceAll("/+$", "");
        this.json = json;
        this.embeddings = OpenAIOkHttpClient.builder().apiKey(key).baseUrl(this.base + "/v1").build();
    }

    private static String requireKey() {
        String value = System.getenv("INFRAI_API_KEY");
        if (value == null || value.isBlank()) throw new IllegalStateException("Set INFRAI_API_KEY");
        return value;
    }

    public List<Float> embed(String text) {
        return embeddings.embeddings().create(EmbeddingCreateParams.builder()
                .model("text-embedding-3-small").input(text).build()).data().get(0).embedding();
    }

    public JsonNode ocr(byte[] pdf, String requestId) throws IOException, InterruptedException {
        String boundary = "boundary-" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"pdf\"; filename=\"scan.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + pdf.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(pdf, 0, body, prefix.length, pdf.length);
        System.arraycopy(suffix, 0, body, prefix.length + pdf.length, suffix.length);
        return post("/v1/pdf/ocr", HttpRequest.BodyPublishers.ofByteArray(body), "multipart/form-data; boundary=" + boundary, requestId);
    }

    public JsonNode postJson(String path, Map<String, ?> payload, String requestId) throws IOException, InterruptedException {
        return post(path, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)), "application/json", requestId);
    }

    private JsonNode post(String path, HttpRequest.BodyPublisher body, String contentType, String requestId) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + key).header("Content-Type", contentType)
                    .header("Idempotency-Key", requestId).POST(body).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = json.readTree(response.body());
            if (response.statusCode() == 429 && attempt < 3) {
                String retryAfter = response.headers().firstValue("Retry-After").orElse("");
                long delay;
                try { delay = Math.max(1, Long.parseLong(retryAfter)) * 1000; }
                catch (NumberFormatException ignored) { delay = 1000L << attempt; }
                Thread.sleep(delay);
                continue;
            }
            if (!envelope.path("ok").asBoolean(false)) {
                JsonNode error = envelope.path("error");
                throw new InfraiRejected(response.statusCode(), error.path("code").asText("REQUEST_REJECTED"), error.path("message").asText("Request rejected"));
            }
            return envelope.path("data");
        }
        throw new IllegalStateException("Retry attempts exhausted");
    }

    public static class InfraiRejected extends RuntimeException {
        public final int status;
        public final String code;
        public InfraiRejected(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
