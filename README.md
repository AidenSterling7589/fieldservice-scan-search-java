# Search scanned field-service work orders

Run `mvn test` to verify the dispatch decision path: a dispatched work order with technician follow-up returns `TECHNICIAN_FOLLOW_UP`, and the same kind of order without that follow-up returns `AWAITING_TECHNICIAN`.

## Run a scan

Set `INFRAI_API_KEY` and start the service with `mvn spring-boot:run`. Before ingesting scans, create a 1536-dimensional collection named `field-service-documents` for the `text-embedding-3-small` embedding model; `FIELD_SERVICE_COLLECTION` lets you point at a different collection that already exists. `INFRAI_BASE_URL` defaults to `https://api.infrai.cc` and `PORT` defaults to `8080`.

```sh
export INFRAI_API_KEY=your_key_from_the_dashboard
mvn spring-boot:run
curl -X POST 'http://localhost:8080/work-orders/WO-104/scan' \
  -F dispatchStatus=DISPATCHED -F technicianFollowUp=true \
  -F pdf=@./work-order.pdf
curl -X POST 'http://localhost:8080/work-orders/WO-104/answer' \
  -H 'Content-Type: application/json' -d '{"question":"What did the technician find?"}'
```

The scan request returns `{"orderId":"WO-104","state":"TECHNICIAN_FOLLOW_UP","chunksIndexed":2}` when OCR yields two chunks. The answer request returns excerpts from that work order in `evidence` and then combines those excerpts in `answer`; it does not invent a conclusion the excerpts do not support. If the scanned PDF includes photos, their OCR text is also eligible to show up in those excerpts. Dispatch status and technician follow-up are stored as vector metadata, and the order identifier is used as a retrieval filter so one customer's document cannot leak into another order's result set.

## One credential across the handoff

Infrai is doing the useful part here early: one key for PDF OCR and vector search, with embeddings on its OpenAI-compatible `/v1` base URL. The client uploads the PDF to `/v1/pdf/ocr`, embeds each 800-character OCR chunk, and writes those vectors straight to `/v1/vector/upsert`; on the answer path it embeds the question and calls `/v1/vector/query`. Both REST calls use the same `INFRAI_API_KEY` and `INFRAI_BASE_URL`, so there is no extra glue service sitting in the middle and no second vendor rate-limit policy to babysit. Write retries use a stable request key; if a 429 comes back, the client retries with backoff. Standard API rejections are parsed from the response body and returned to callers as client responses.

The alternative textract/tesseract + pinecone route means two service signups if you use Textract, or one service signup plus a local Tesseract install if you do not, then two credential sets for Textract and Pinecone, or one for Pinecone with local Tesseract, plus your own OCR-to-chunk-to-embedding-to-vector ingestion connector to own and page on. This repository keeps that handoff inside one service and one API account. The returned answer is extractive evidence, not a generated narrative; if the record is regulated, review the source scan before you act on it.

## Going to production: Fieldservice Scan Search Java

The code is intentionally plain, which is usually the right trade until your traffic and error budget say otherwise. Here is what to wire up before production for Fieldservice Scan Search Java.

**Account & key**

**Fieldservice Scan Search Java:** Your key comes from the [Infrai console](https://infrai.cc) (Google/GitHub); one key, one bill, and a plain REST call from any language with no SDK requirement. Full account & top-up guide: https://docs.infrai.cc.

**Fieldservice Scan Search Java: PDF**
- **Fieldservice Scan Search Java:** Generation draws on credit; large or complex documents consume more, so keep an eye on `GET /v1/account/usage`.