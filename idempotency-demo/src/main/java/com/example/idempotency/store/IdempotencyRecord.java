package com.example.idempotency.store;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Persisted record of an idempotency key. The {@code idempotencyKey} column has a unique
 * constraint (it's the primary key), which is what makes "create if absent" safe under
 * concurrent requests / multiple service instances: only one insert can win.
 *
 * Implements {@link Persistable} because the id is assigned by the caller (not
 * database-generated). Without this, Spring Data JPA can't tell a brand-new record from an
 * existing one just by looking at the id, and would issue a SELECT-then-merge instead of a
 * plain INSERT - which would silently swallow the race condition this whole design relies on
 * a unique-constraint violation to catch.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    @Transient
    private boolean isNew = true;

    /** SHA-256 hex hash of the incoming request (method + path + body). Detects key reuse with a different payload. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Lob
    @Column(name = "response_body")
    private String responseBody;

    @Lob
    @Column(name = "response_content_type")
    private String responseContentType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
        // JPA
    }

    public IdempotencyRecord(String idempotencyKey, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = Instant.now();
    }

    public void markCompleted(int responseStatus, String responseBody, String responseContentType) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.responseContentType = responseContentType;
        this.completedAt = Instant.now();
    }

    public void markFailed() {
        this.status = IdempotencyStatus.FAILED;
        this.completedAt = Instant.now();
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getResponseContentType() {
        return responseContentType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
