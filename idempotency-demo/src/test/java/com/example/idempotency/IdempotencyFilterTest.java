package com.example.idempotency;

import com.example.idempotency.filter.IdempotencyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyFilterTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String ORDER_BODY =
            "{\"customerId\":\"cust-1\",\"productId\":\"prod-1\",\"quantity\":2}";

    private static final String DIFFERENT_ORDER_BODY =
            "{\"customerId\":\"cust-1\",\"productId\":\"prod-1\",\"quantity\":99}";

    @Test
    void duplicateRequestWithSameKeyAndBodyReplaysOriginalResponse() throws Exception {
        String key = "test-key-" + System.nanoTime();

        // First call: goes through normally, creates the order.
        String firstOrderId = mockMvc.perform(post("/api/orders")
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();

        // Second call, identical key + body: replayed, not re-processed.
        String secondOrderId = mockMvc.perform(post("/api/orders")
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
                .andReturn().getResponse().getContentAsString();

        // Same exact response body (same order ID) proves the handler was not re-run.
        org.junit.jupiter.api.Assertions.assertEquals(firstOrderId, secondOrderId);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        String key = "test-key-" + System.nanoTime();

        mockMvc.perform(post("/api/orders")
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders")
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DIFFERENT_ORDER_BODY))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void requestsWithoutIdempotencyKeyAreNotDeduplicated() throws Exception {
        int before = currentCallCount();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY))
                .andExpect(status().isCreated());

        int after = currentCallCount();
        org.junit.jupiter.api.Assertions.assertEquals(before + 2, after,
                "without an Idempotency-Key, each request should create a new order");
    }

    @Test
    void concurrentDuplicateRequestsResultInOnlyOneOrderCreated() throws Exception {
        String key = "concurrent-key-" + System.nanoTime();
        int before = currentCallCount();

        int threadCount = 5;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return mockMvc.perform(post("/api/orders")
                                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(ORDER_BODY))
                        .andReturn().getResponse().getStatus();
            }));
        }

        ready.await();
        go.countDown();

        int created = 0, replayedOrConflict = 0;
        for (java.util.concurrent.Future<Integer> f : futures) {
            int status = f.get();
            if (status == 201) created++; else replayedOrConflict++;
        }
        pool.shutdown();

        // Regardless of how many callers got a 201 (replay) vs 409 (in-flight conflict),
        // the underlying handler must only have executed once for this key.
        int after = currentCallCount();
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, after,
                "handler should only run once per idempotency key even under concurrent duplicates");
    }

    private int currentCallCount() throws Exception {
        String body = mockMvc.perform(get("/api/orders/_debug/call-count"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Integer.parseInt(body.trim());
    }
}
