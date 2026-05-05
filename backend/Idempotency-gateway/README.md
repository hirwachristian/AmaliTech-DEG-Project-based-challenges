# Idempotency Gateway

A Spring Boot REST API that implements an idempotency layer for exactly-once payment processing. Retrying a failed or timed-out request always returns the original response without re-charging the customer.

---

## Architecture Diagram

The sequence diagram below covers five flows: new payment, idempotency replay, payload conflict, multi-node race condition, and TTL cleanup.

![Idempotency Gateway Sequence Diagram](docs/sequence-diagram.png)


---

## Setup Instructions

### Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+ |
| Maven | 3.8+ |
| Docker & Docker Compose | 20+ |
| PostgreSQL (local) | 15+ (or use Docker) |

---

### Option A — Docker Compose (recommended)

Starts both PostgreSQL and the application in one command.

```bash
# 1. Build the JAR
mvn clean package -DskipTests

# 2. Start everything
docker-compose up --build
```

The API is available at `http://localhost:8001`.

To stop:

```bash
docker-compose down
```

To stop and wipe the database volume:

```bash
docker-compose down -v
```

---

### Option B — Local (Maven + existing PostgreSQL)

```bash
# 1. Create the database
psql -U postgres -c "CREATE DATABASE idempotency_db;"

# 2. Run the application
mvn spring-boot:run
```

Default database credentials are `postgres / postgres` on `localhost:5432`. Override them via environment variables:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/idempotency_db
export SPRING_DATASOURCE_USERNAME=your_user
export SPRING_DATASOURCE_PASSWORD=your_password
mvn spring-boot:run
```

---

### Run Tests

Tests use an H2 in-memory database and require no external services.

```bash
mvn test
```

---

## API Documentation

### Base URL

```
http://localhost:8001
```

---

### POST `/process-payment`

Process a payment. Returns a cached response on replay.

**Headers**

| Header | Required | Description |
|--------|----------|-------------|
| `Idempotency-Key` | Yes | Unique string (1–255 chars) identifying this request |
| `Content-Type` | Yes | `application/json` |

**Request Body**

```json
{
  "amount": 150.00,
  "currency": "GHS"
}
```

| Field | Type | Constraints |
|-------|------|-------------|
| `amount` | `number` | Required, must be positive |
| `currency` | `string` | Required, exactly 3 uppercase letters (ISO 4217) |

---

**Responses**

#### 201 Created — new payment processed

```json
{
  "message": "Charged 150.00 GHS",
  "transactionId": "b3d2e1f4-...",
  "timestamp": 1748123456789
}
```

Response header: `X-Cache-Hit: false`

---

#### 200 OK — duplicate key, same payload (idempotency replay)

Returns the exact same body as the original `201` response.

Response header: `X-Cache-Hit: true`

---

#### 422 Unprocessable Entity — same key, different payload

```json
{
  "error": "Idempotency key already used for a different request body.",
  "status": 422,
  "timestamp": "2025-05-05T10:30:00"
}
```

---

#### 400 Bad Request — validation failure

Missing or blank `Idempotency-Key`:
```json
{
  "error": "Missing or invalid Idempotency-Key header",
  "status": 400
}
```

Key exceeds 255 characters:
```json
{
  "error": "Idempotency-Key must not exceed 255 characters",
  "status": 400
}
```

Invalid body fields:
```json
{
  "errors": {
    "currency": "Currency must be a valid 3-letter ISO 4217 code (e.g. USD, GHS, EUR)",
    "amount": "Amount must be positive"
  },
  "status": 400
}
```

---

### GET `/process-payment`

Returns all stored transactions.

**Response — 200 OK**

```json
[
  {
    "idempotencyKey": "pay-001",
    "transactionId": "b3d2e1f4-...",
    "message": "Charged 150.00 GHS",
    "statusCode": 201,
    "createdAt": "2025-05-05T10:00:00",
    "updatedAt": "2025-05-05T10:00:00"
  }
]
```

---

### GET `/process-payment/{idempotencyKey}`

Returns a single transaction by its idempotency key.

**Response — 200 OK**

```json
{
  "idempotencyKey": "pay-001",
  "transactionId": "b3d2e1f4-...",
  "message": "Charged 150.00 GHS",
  "statusCode": 201,
  "createdAt": "2025-05-05T10:00:00",
  "updatedAt": "2025-05-05T10:00:00"
}
```

**Response — 400 Bad Request** (key not found)

```json
{
  "error": "No transaction found for key: pay-999"
}
```

---

## Design Decisions

### 1. UUID Primary Key

UUIDs are generated at the application layer, not the database. This removes a round-trip to the DB for ID allocation and works correctly across horizontally scaled nodes without coordination.

### 2. Store Request and Response as JSON Strings

Both the original request body and the processed response are persisted as plain JSON text. This provides a full audit trail, allows the exact original response to be replayed byte-for-byte, and avoids schema migrations when the payment model evolves.

### 3. Database UNIQUE Constraint as the Safety Net

`idempotency_key` carries a `UNIQUE` constraint at the database level. This is the last line of defence for multi-node deployments where two instances could both pass the in-memory check simultaneously. The service catches `DataIntegrityViolationException` from the constraint violation, fetches the winning node's already-stored response, and returns it as a cache hit — no 500 error, no lost response.

### 4. `saveAndFlush` Instead of `save`

Spring Data JPA defers the SQL `INSERT` until the end of the transaction by default (`save` only adds to the persistence context). Using `saveAndFlush` forces the write immediately so a `DataIntegrityViolationException` surfaces inside the method boundary where it can be caught and handled gracefully, rather than at commit time where it would escape unhandled.

### 5. Per-Key `ReentrantLock` for Same-Node Concurrency

A `ConcurrentHashMap<String, ReentrantLock>` ensures that concurrent requests carrying the same idempotency key on the same JVM are serialised. The second request waits until the first request's transaction has fully committed before checking the database, guaranteeing it will find the cached record. Multi-node concurrency is handled at the database layer (see decision 3).

### 6. Self-Injection via `@Lazy` for `@Transactional`

Spring's `@Transactional` works through AOP proxies. Calling `this.doProcessPayment()` from within the same bean bypasses the proxy and the transaction is silently ignored. The service injects itself via `@Lazy @Autowired` so `doProcessPayment` is called through the proxy, ensuring the transaction commits before the lock is released.

### 7. Structural JSON Comparison with `JsonNode`

Payload matching uses `JsonNode.equals()` (Jackson) instead of raw string equality. String comparison is fragile because field serialisation order is not guaranteed across Jackson versions or different serialisation contexts. Structural comparison treats `{"amount":100,"currency":"GHS"}` and `{"currency":"GHS","amount":100}` as identical — which they are — preventing false 422 conflicts.

### 8. Idempotency Key Length Validation

The database column is `VARCHAR(255)`. A key longer than 255 characters would fail with an opaque database error. An explicit check in the controller rejects oversized keys with a clean `400 Bad Request` before the request reaches the database.

### 9. Currency Format Validation

The `currency` field is validated against the pattern `[A-Z]{3}`, enforcing ISO 4217 format at the API boundary. Lowercase codes (`usd`), empty strings, and arbitrary values are rejected with a descriptive field error instead of being stored and silently ignored.

---

## Developer's Choice: Idempotency Key TTL (Time-To-Live)

### The Problem

Without expiry, idempotency keys are stored forever. A key created today could still block a legitimate future payment with the same identifier months later. Storage also grows unboundedly, which becomes a cost and performance concern for high-volume payment processors.

### The Implementation

A scheduled background job runs every hour and deletes any `payment_requests` records whose `created_at` timestamp is older than **24 hours**.

```java
@Scheduled(fixedRateString = "${idempotency.ttl.cleanup-interval-ms:3600000}")
@Transactional
public void cleanupExpiredKeys() {
    LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
    int deleted = paymentRequestRepository.deleteExpiredKeys(cutoff);
    if (deleted > 0) {
        log.info("TTL cleanup: removed {} expired idempotency keys", deleted);
    }
}
```

The cleanup interval is configurable via `idempotency.ttl.cleanup-interval-ms` in `application.properties`.

### Why 24 Hours

- **Retry window:** Most payment clients implement retry logic with exponential back-off. A 24-hour window far exceeds any realistic retry window while keeping the table lean.
- **Regulatory alignment:** Financial transaction systems typically require retaining transaction records for at least 24 hours for dispute resolution and audit purposes. Deleting after 24 hours complies with this minimum without over-retaining data.
- **Configurable per environment:** Production may require a longer window (e.g., 72 hours); a test or staging environment can use a shorter window (e.g., 1 hour) to keep the database clean.

### Why This Matters in Fintech

Unbounded key storage is a known operational hazard in payment systems. Without TTL:
- Storage costs grow linearly with transaction volume.
- Key lookup queries degrade as the table grows.
- Old keys can produce surprising conflicts for clients who reuse key formats (e.g., `order-{id}`) across billing cycles.

The TTL feature turns the idempotency store from an ever-growing ledger into a bounded, self-maintaining cache.

---

## Project Structure

```
src/
├── main/java/com/idempotency/
│   ├── controller/
│   │   └── PaymentController.java       # REST endpoints, header & key validation
│   ├── service/
│   │   └── PaymentService.java          # Business logic, locking, TTL cleanup
│   ├── repository/
│   │   └── PaymentRequestRepository.java
│   ├── entity/
│   │   └── PaymentRequest.java          # JPA entity mapped to payment_requests
│   ├── dto/
│   │   ├── PaymentRequestDto.java       # Validated request model
│   │   └── PaymentResponseDto.java      # Response model
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   ├── IdempotencyConflictException.java
│   │   └── BadRequestException.java
│   └── IdempotencyGatewayApplication.java
└── test/java/com/idempotency/
    └── controller/
        └── PaymentControllerTest.java   # Integration tests (H2 in-memory)
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/idempotency_db` | Database URL |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | Database password |
| `IDEMPOTENCY_TTL_CLEANUP_INTERVAL_MS` | `3600000` (1 hour) | TTL cleanup interval in milliseconds |
