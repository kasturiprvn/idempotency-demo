# idempotency-demo

A minimal Spring Boot microservice showing one common way to implement **idempotency keys**
for REST APIs: a client sends an `Idempotency-Key` header on a mutating request (POST/PATCH);
if the same key is retried, the service replays the original response instead of doing the
work twice.

## What's in here

- `IdempotencyFilter` — a servlet filter that does the actual work: looks up the key, replays
  a cached response, rejects in-flight duplicates with 409, rejects key-reuse-with-different-body
  with 422, and otherwise lets the request through and caches the response afterward.
- `IdempotencyRecord` / `IdempotencyRepository` — a JPA-backed store (H2 in-memory for this demo,
  swap the datasource for Postgres/MySQL in production) with the idempotency key as the primary
  key, so the database's unique constraint is what actually makes concurrent/multi-instance races
  safe.
- `OrderController` / `OrderService` — a trivial "create an order" endpoint used to demonstrate
  the filter. `OrderService` keeps a call counter purely so the tests can prove the handler only
  really ran once.
- `IdempotencyFilterTest` — MockMvc integration tests: replay-on-duplicate, 422 on key-reuse with
  a different body, no-op behavior when no key is sent, and a concurrent-duplicate test using 5
  threads hitting the same key at once.

## Running it

Requires JDK 17+ and Maven (or your IDE's built-in Maven support).

```bash
mvn spring-boot:run
```

The service starts on `http://localhost:8080`. H2 console (if you want to poke at the
`idempotency_record` table) is at `http://localhost:8080/h2-console`, JDBC URL
`jdbc:h2:mem:idempotency`, user `sa`, empty password.

### Import into an IDE

Just open the folder as a Maven project (IntelliJ: "Open" → select the folder with `pom.xml`;
Eclipse/STS: "Import" → "Existing Maven Project"). It's a standard `mvn` layout, nothing special
needed.

### Running the tests

```bash
mvn test
```

## Trying it with curl

Create an order with an idempotency key:

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-abc-123" \
  -d '{"customerId":"cust-1","productId":"prod-1","quantity":2}'
```

Run the exact same command again — you'll get the same order back (same `orderId`), with an
extra `Idempotent-Replayed: true` response header, and no second order was actually created.

Now try the same key with a different body:

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-abc-123" \
  -d '{"customerId":"cust-1","productId":"prod-1","quantity":999}'
```

You'll get `422 Unprocessable Entity` — reusing a key for a different payload is treated as a
client error rather than silently accepted.

Check how many times the handler actually ran:

```bash
curl http://localhost:8080/api/orders/_debug/call-count
```

Requests with no `Idempotency-Key` header pass straight through and are **not** deduplicated —
that's intentional; idempotency is opt-in per request, exactly like Stripe/PayPal-style APIs.

## Design notes / things to adjust for production

- **Storage**: H2-in-memory is only for this demo so it runs with zero setup. Point
  `spring.datasource.url` at a real database and this works unchanged across multiple service
  instances, because correctness comes from the primary-key uniqueness constraint on
  `idempotency_record.idempotency_key`, not from anything in-process.
- **Cleanup/TTL**: records are never deleted (except failed ones, which are deleted so the key
  can be retried). In production you'd add a scheduled job or a DB TTL to expire `COMPLETED`
  records after some window (the `idempotency.record-ttl-hours` property is a placeholder for
  that — no cleanup job is wired up here).
- **What counts as "the same request"**: this demo hashes `method + path + body`. You may also
  want to include specific headers (e.g. an auth-scoped user ID) in the hash if the same key
  could otherwise be replayed across different callers.
- **Which status codes get cached**: this demo caches any non-5xx response (so validation errors
  get replayed too, which matches how several real-world idempotency implementations behave) and
  discards the record on 5xx so the client can safely retry. Adjust the `success` check in
  `IdempotencyFilter` if you want different behavior.
- **Scope**: the filter only applies to `/api/*` and only intercepts `POST`/`PATCH` (see
  `FilterConfig` and `IDEMPOTENT_METHODS`) — `GET`/`DELETE` are typically already
  naturally idempotent and are left alone.
