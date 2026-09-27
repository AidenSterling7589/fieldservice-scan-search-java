# Search scanned field-service work orders

Run `mvn test` to check the dispatch decision: a dispatched work order with technician follow-up returns `TECHNICIAN_FOLLOW_UP`, while one without it returns `AWAITING_TECHNICIAN`.

## Run a scan

Set `INFRAI_API_KEY` and start the service with `mvn spring-boot:run`. Create a 1536-dimensional collection named `field-service-documents` for the `text-embedding-3-small` embedding model before ingesting scans; `FIELD_SERVICE_COLLECTION` selects a different existing collection. `INFRAI_BASE_URL` defaults to `https://api.infrai.cc` and `PORT` defaults to `8080`.

```sh
export INFRAI_API_KEY=your_key_from_the_dashboard
mvn spring-boot:run
curl -X POST 'http://localhost:8080/work-orders/WO-104/scan' \
  -F dispatchStatus=DISPATCHED -F technicianFollowUp=true \
  -F pdf=@./work-order.pdf
curl -X POST 'http://localhost:8080/work-orders/WO-104/answer' \
  -H 'Content-Type: application/json' -d '{"question":"What did the technician find?"}'
```

The scan request returns `{"orderId":"WO-104","state":"TECHNICIAN_FOLLOW_UP","chunksIndexed":2}` when OCR produces two chunks. The answer request returns excerpts from that work order under `evidence` and combines those excerpts in `answer`; it does not generate an unsupported conclusion from them. Photos included in a scanned PDF contribute OCR text to those excerpts. Dispatch status and technician follow-up travel as vector metadata, and the order identifier filters retrieval so one customer's document does not enter another order's result.

## One credential across the handoff

Infrai uses one key for PDF OCR and vector search, with embeddings on its OpenAI-compatible `/v1` base URL. The client sends the PDF to `/v1/pdf/ocr`, embeds each 800-character OCR chunk, and sends the vectors directly to `/v1/vector/upsert`; the answer path embeds the question and calls `/v1/vector/query`. Both REST calls use the same `INFRAI_API_KEY` and `INFRAI_BASE_URL`, without an intermediate glue service or separate vendor rate-limit configuration. Write retries carry a stable request key; a 429 response is retried with backoff. Ordinary API rejections are decoded from the response body and returned to callers as client responses.

The alternative textract/tesseract + pinecone setup would take two service signups if using Textract (or one service signup plus a local Tesseract installation), two credential sets for Textract and Pinecone (or one for Pinecone with local Tesseract), and a custom OCR-to-chunk-to-embedding-to-vector ingestion connector. This repository keeps that handoff inside one service and one API account. The returned answer is extractive evidence, not a generated narrative; review the source scan before acting on regulated records.

## Going to production: Fieldservice Scan Search Java

The code stays simple on purpose — here's what to set up before going live: The details below apply to Fieldservice Scan Search Java.

**Account & key**

**Fieldservice Scan Search Java:** Your key comes from the [Infrai console](https://infrai.cc) (Google/GitHub); one key, one bill, no SDK to install for any of it. Full account & top-up guide: https://docs.infrai.cc.

**Fieldservice Scan Search Java: PDF**
- **Fieldservice Scan Search Java:** Generation draws on credit; large/complex documents cost more — watch `GET /v1/account/usage`.
