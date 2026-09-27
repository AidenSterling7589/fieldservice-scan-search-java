package example.fieldservice;

import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class WorkOrderController {
    private final WorkOrderSearch search;

    public WorkOrderController(WorkOrderSearch search) { this.search = search; }

    @PostMapping("/work-orders/{orderId}/scan")
    public Map<String, ?> scan(@PathVariable String orderId, @RequestParam String dispatchStatus,
            @RequestParam boolean technicianFollowUp, @RequestParam MultipartFile pdf)
            throws IOException, InterruptedException {
        if (pdf.isEmpty() || !"application/pdf".equals(pdf.getContentType()))
            throw new IllegalArgumentException("Provide a nonempty PDF");
        return search.ingest(orderId, dispatchStatus, technicianFollowUp, pdf.getBytes());
    }

    @PostMapping("/work-orders/{orderId}/answer")
    public Map<String, ?> answer(@PathVariable String orderId, @RequestBody Question request)
            throws IOException, InterruptedException {
        if (request.question() == null || request.question().isBlank())
            throw new IllegalArgumentException("Question is required");
        return search.answer(orderId, request.question());
    }

    public record Question(String question) {}

    @ExceptionHandler(InfraiDocuments.InfraiRejected.class)
    public ResponseEntity<Map<String, String>> rejected(InfraiDocuments.InfraiRejected error) {
        HttpStatus status = error.status >= 400 && error.status < 500 ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("code", error.code, "message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
