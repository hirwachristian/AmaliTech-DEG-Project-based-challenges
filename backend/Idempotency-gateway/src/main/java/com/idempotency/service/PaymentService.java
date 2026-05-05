package com.idempotency.service;

import com.idempotency.dto.PaymentRequestDto;
import com.idempotency.dto.PaymentResponseDto;
import com.idempotency.entity.PaymentRequest;
import com.idempotency.exception.IdempotencyConflictException;
import com.idempotency.repository.PaymentRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.idempotency.exception.BadRequestException;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRequestRepository paymentRequestRepository;
    private final ObjectMapper objectMapper;

    // Self-injection via @Lazy to allow @Transactional on doProcessPayment to work through
    // the Spring AOP proxy (direct this.doProcessPayment() calls bypass the proxy).
    @Lazy
    @Autowired
    private PaymentService self;

    public record PaymentResult(PaymentResponseDto response, boolean cacheHit) {}

    private final ConcurrentHashMap<String, Lock> inFlightLocks = new ConcurrentHashMap<>();

    public PaymentResult processPayment(String idempotencyKey, PaymentRequestDto paymentRequest) {
        Lock lock = inFlightLocks.computeIfAbsent(idempotencyKey, k -> new ReentrantLock());
        lock.lock();
        try {
            return self.doProcessPayment(idempotencyKey, paymentRequest);
        } catch (DataIntegrityViolationException e) {
            // Multi-node race: another instance saved the same key first.
            // The DB unique constraint is the safety net — fetch and return the winner's response.
            log.warn("Race condition detected for key: {}, returning winner's cached response", idempotencyKey);
            return paymentRequestRepository.findByIdempotencyKey(idempotencyKey)
                .map(saved -> {
                    try {
                        PaymentResponseDto cached = objectMapper.readValue(saved.getResponseBody(), PaymentResponseDto.class);
                        return new PaymentResult(cached, true);
                    } catch (Exception ex) {
                        throw new RuntimeException("Failed to deserialize cached response after race condition", ex);
                    }
                })
                .orElseThrow(() -> new RuntimeException(
                    "Race condition: key not found after constraint violation", e));
        } finally {
            // Remove before unlock so a new thread that arrives after the remove
            // creates a fresh lock rather than sharing the one being released.
            inFlightLocks.remove(idempotencyKey, lock);
            lock.unlock();
        }
    }

    @Transactional
    public PaymentResult doProcessPayment(String idempotencyKey, PaymentRequestDto paymentRequest) {
        log.info("Processing payment with idempotency key: {}", idempotencyKey);

        Optional<PaymentRequest> existingRequest = paymentRequestRepository.findByIdempotencyKey(idempotencyKey);

        if (existingRequest.isPresent()) {
            PaymentRequest existing = existingRequest.get();

            try {
                // Structural JSON comparison avoids false conflicts caused by field-ordering
                // differences that can arise from different serialization paths.
                JsonNode existingNode = objectMapper.readTree(existing.getRequestBody());
                JsonNode newNode = objectMapper.readTree(objectMapper.writeValueAsString(paymentRequest));

                if (!existingNode.equals(newNode)) {
                    log.warn("Idempotency key reused with different payload. Key: {}", idempotencyKey);
                    throw new IdempotencyConflictException(
                        "Idempotency key already used for a different request body."
                    );
                }
            } catch (IdempotencyConflictException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to compare request bodies", e);
            }

            log.info("Returning cached response for idempotency key: {}", idempotencyKey);
            try {
                PaymentResponseDto cachedResponse = objectMapper.readValue(
                    existing.getResponseBody(), PaymentResponseDto.class);
                return new PaymentResult(cachedResponse, true);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize cached response", e);
            }
        }

        log.info("Processing new payment request with idempotency key: {}", idempotencyKey);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Payment processing interrupted", e);
        }

        PaymentResponseDto response = PaymentResponseDto.builder()
            .message(String.format("Charged %.2f %s", paymentRequest.getAmount(), paymentRequest.getCurrency()))
            .transactionId(UUID.randomUUID().toString())
            .timestamp(System.currentTimeMillis())
            .build();

        String requestBodyJson;
        String responseBodyJson;
        try {
            requestBodyJson = objectMapper.writeValueAsString(paymentRequest);
            responseBodyJson = objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request/response", e);
        }

        PaymentRequest paymentRequestEntity = PaymentRequest.builder()
            .idempotencyKey(idempotencyKey)
            .requestBody(requestBodyJson)
            .responseBody(responseBodyJson)
            .statusCode(201)
            .build();

        // saveAndFlush forces the INSERT immediately so DataIntegrityViolationException
        // surfaces here (inside the try/catch in processPayment) rather than at commit time.
        paymentRequestRepository.saveAndFlush(paymentRequestEntity);
        log.info("Payment saved with transaction ID: {}", response.getTransactionId());

        return new PaymentResult(response, false);
    }

    public Map<String, Object> getByIdempotencyKey(String idempotencyKey) {
        return paymentRequestRepository.findByIdempotencyKey(idempotencyKey)
                .map(this::toSummary)
                .orElseThrow(() -> new BadRequestException("No transaction found for key: " + idempotencyKey));
    }

    public List<Map<String, Object>> getAllTransactions() {
        return paymentRequestRepository.findAll().stream()
                .map(this::toSummary)
                .toList();
    }

    @Scheduled(fixedRateString = "${idempotency.ttl.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanupExpiredKeys() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        int deleted = paymentRequestRepository.deleteExpiredKeys(cutoff);
        if (deleted > 0) {
            log.info("TTL cleanup: removed {} expired idempotency keys", deleted);
        }
    }

    private Map<String, Object> toSummary(PaymentRequest entity) {
        PaymentResponseDto response;
        try {
            response = objectMapper.readValue(entity.getResponseBody(), PaymentResponseDto.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize response body", e);
        }
        return Map.of(
                "idempotencyKey", entity.getIdempotencyKey(),
                "transactionId", response.getTransactionId(),
                "message", response.getMessage(),
                "statusCode", entity.getStatusCode(),
                "createdAt", entity.getCreatedAt().toString(),
                "updatedAt", entity.getUpdatedAt().toString()
        );
    }
}
