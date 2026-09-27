package example.fieldservice;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WorkOrderSearch {
    private final InfraiDocuments documents;
    private final String collection;

    public WorkOrderSearch(InfraiDocuments documents, @Value("${fieldservice.collection}") String collection) {
        this.documents = documents;
        this.collection = collection;
    }

    public static String dispatchDecision(String dispatchStatus, boolean technicianFollowUp) {
        if (technicianFollowUp) return "TECHNICIAN_FOLLOW_UP";
        if ("DISPATCHED".equals(dispatchStatus)) return "AWAITING_TECHNICIAN";
        return "AWAITING_DISPATCH";
    }

    public Map<String, ?> ingest(String orderId, String dispatchStatus, boolean technicianFollowUp, byte[] pdf)
            throws IOException, InterruptedException {
        String state = dispatchDecision(dispatchStatus, technicianFollowUp);
        JsonNode data = documents.ocr(pdf, orderId + ":ocr");
        String text = data.path("text").asText();
        if (text.isBlank()) throw new IllegalArgumentException("OCR returned no document text");
        List<Map<String, ?>> vectors = new ArrayList<>();
        int index = 0;
        for (int start = 0; start < text.length(); start += 800) {
            String chunk = text.substring(start, Math.min(start + 800, text.length()));
            vectors.add(Map.of("id", orderId + ":" + index++, "values", documents.embed(chunk),
                    "metadata", Map.of("order_id", orderId, "dispatch_status", dispatchStatus,
                            "follow_up", technicianFollowUp, "state", state, "text", chunk)));
        }
        documents.postJson("/v1/vector/upsert", Map.of("collection", collection, "vectors", vectors), orderId + ":index");
        return Map.of("orderId", orderId, "state", state, "chunksIndexed", vectors.size());
    }

    public Map<String, ?> answer(String orderId, String question) throws IOException, InterruptedException {
        JsonNode data = documents.postJson("/v1/vector/query", Map.of("collection", collection,
                "embedding", documents.embed(question), "top_k", 3,
                "filter", Map.of("order_id", orderId), "include_metadata", true), orderId + ":query");
        JsonNode matches = data.path("matches");
        List<String> evidence = new ArrayList<>();
        if (matches.isArray()) for (JsonNode match : matches) {
            String excerpt = match.path("metadata").path("text").asText();
            if (!excerpt.isBlank()) evidence.add(excerpt);
        }
        return Map.of("orderId", orderId, "question", question, "evidence", evidence,
                "answer", evidence.isEmpty() ? "No matching scanned text for this work order."
                        : String.join("\n", evidence));
    }
}
