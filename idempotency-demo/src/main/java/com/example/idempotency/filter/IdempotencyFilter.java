package com.example.idempotency.filter;

import com.example.idempotency.store.IdempotencyRecord;
import com.example.idempotency.store.IdempotencyRepository;
import com.example.idempotency.store.IdempotencyStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces idempotent semantics for requests that carry an "Idempotency-Key" header.
 * <p>
 * Behaviour:
 * <ul>
 *   <li>No header present -> request passes through untouched (not idempotent).</li>
 *   <li>Header present, key never seen -> a lock record is inserted (IN_PROGRESS), the request
 *       proceeds, and on completion the response is cached (COMPLETED) for future replay.</li>
 *   <li>Header present, key already COMPLETED, same request body -> the cached response is
 *       replayed immediately; the real handler is never invoked again.</li>
 *   <li>Header present, key already COMPLETED, different request body -> 422, since reusing a
 *       key for a different payload is a client error.</li>
 *   <li>Header present, key currently IN_PROGRESS (a concurrent duplicate request) -> 409, telling
 *       the caller the original request is still being processed.</li>
 *   <li>Handler throws / returns 5xx -> the record is removed so the client can safely retry with
 *       the same key.</li>
 * </ul>
 *
 * The unique primary key constraint on {@code idempotency_record.idempotency_key} is what makes
 * the "insert if absent" step safe across concurrent threads and multiple service instances -
 * only one insert can win; the loser gets a {@link DataIntegrityViolationException} and is treated
 * as a duplicate-in-flight request.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Set<String> IDEMPOTENT_METHODS = Set.of("POST", "PATCH");

    private final IdempotencyRepository repository;

    /**
     * Per-JVM locks so that two threads on the *same* instance racing for the same brand-new key
     * don't both attempt the insert (harmless either way thanks to the DB constraint, but this
     * avoids doing the handler's work twice in the common single-instance/test case).
     */
    private final ConcurrentHashMap<String, Object> localLocks = new ConcurrentHashMap<>();

    public IdempotencyFilter(IdempotencyRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String key = request.getHeader(IDEMPOTENCY_KEY_HEADER);

        if (key == null || key.isBlank() || !IDEMPOTENT_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        // Must read the body now so it's cached for hashing (also makes it available downstream).
        byte[] bodyBytes = StreamUtils.copyToByteArray(wrappedRequest.getInputStream());
        String requestHash = sha256(request.getMethod() + "|" + request.getRequestURI() + "|"
                + new String(bodyBytes, StandardCharsets.UTF_8));

        // Re-wrap so downstream reads see the body we just consumed.
        wrappedRequest = new ContentCachingRequestWrapper(request) {
            @Override
            public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
                return new CachedBodyServletInputStream(bodyBytes);
            }
        };

        Object lock = localLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                IdempotencyRecord existing = repository.findById(key).orElse(null);

                if (existing != null) {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        writeError(response, 422,
                                "Idempotency-Key '" + key + "' was already used with a different request body.");
                        return;
                    }
                    if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                        writeError(response, 409,
                                "A request with Idempotency-Key '" + key + "' is already being processed.");
                        return;
                    }
                    if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                        replay(response, existing);
                        return;
                    }
                    // FAILED -> fall through and retry as if new (delete stale record first).
                    repository.deleteById(key);
                }

                if (!tryInsert(key, requestHash)) {
                    // Another instance/thread won the race between our findById and insert.
                    writeError(response, 409,
                            "A request with Idempotency-Key '" + key + "' is already being processed.");
                    return;
                }
            } finally {
                localLocks.remove(key, lock);
            }

            boolean success = false;
            try {
                chain.doFilter(wrappedRequest, wrappedResponse);
                success = wrappedResponse.getStatus() < 500;
            } finally {
                if (success) {
                    String responseBody = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
                    saveCompleted(key, wrappedResponse.getStatus(), responseBody, wrappedResponse.getContentType());
                } else {
                    // Let the client retry with the same key.
                    repository.deleteById(key);
                }
                wrappedResponse.copyBodyToResponse();
            }
        }
    }

    /**
     * Inserts the lock record. {@code saveAndFlush} forces the INSERT to hit the database
     * immediately (rather than at end-of-transaction), so a unique-constraint violation from a
     * concurrent duplicate is caught here rather than surfacing later or being missed.
     * {@code repository.saveAndFlush} is itself transactional (Spring Data JPA wraps it), which
     * is all the transaction boundary this needs.
     */
    protected boolean tryInsert(String key, String requestHash) {
        try {
            repository.saveAndFlush(new IdempotencyRecord(key, requestHash));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    protected void saveCompleted(String key, int status, String body, String contentType) {
        repository.findById(key).ifPresent(record -> {
            record.markCompleted(status, body, contentType);
            repository.save(record);
        });
    }

    private void replay(HttpServletResponse response, IdempotencyRecord record) throws IOException {
        response.setStatus(record.getResponseStatus());
        if (record.getResponseContentType() != null) {
            response.setContentType(record.getResponseContentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (record.getResponseBody() != null) {
            response.getWriter().write(record.getResponseBody());
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal replay-able ServletInputStream backed by an in-memory byte array. */
    private static class CachedBodyServletInputStream extends jakarta.servlet.ServletInputStream {
        private final java.io.ByteArrayInputStream buffer;

        CachedBodyServletInputStream(byte[] contents) {
            this.buffer = new java.io.ByteArrayInputStream(contents);
        }

        @Override
        public boolean isFinished() {
            return buffer.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
            // no-op: not needed for synchronous processing in this demo
        }

        @Override
        public int read() {
            return buffer.read();
        }
    }
}
