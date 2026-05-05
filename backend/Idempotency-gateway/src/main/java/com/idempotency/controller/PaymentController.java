package com.idempotency.controller;

import com.idempotency.dto.PaymentRequestDto;
import com.idempotency.dto.PaymentResponseDto;
import com.idempotency.exception.BadRequestException;
import com.idempotency.service.PaymentService;
import com.idempotency.service.PaymentService.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/process-payment")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponseDto> processPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequestDto paymentRequest) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            log.warn("Missing Idempotency-Key header");
            throw new BadRequestException("Missing or invalid Idempotency-Key header");
        }

        if (idempotencyKey.length() > 255) {
            log.warn("Idempotency-Key exceeds 255 characters");
            throw new BadRequestException("Idempotency-Key must not exceed 255 characters");
        }

        log.info("Received payment request with idempotency key: {}", idempotencyKey);

        PaymentResult result = paymentService.processPayment(idempotencyKey, paymentRequest);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Cache-Hit", String.valueOf(result.cacheHit()));

        HttpStatus status = result.cacheHit() ? HttpStatus.OK : HttpStatus.CREATED;

        return ResponseEntity
            .status(status)
            .headers(headers)
            .body(result.response());
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllTransactions() {
        log.info("Fetching all transactions");
        return ResponseEntity.ok(paymentService.getAllTransactions());
    }

    @GetMapping("/{idempotencyKey}")
    public ResponseEntity<Map<String, Object>> getByIdempotencyKey(@PathVariable String idempotencyKey) {
        log.info("Fetching transaction for idempotency key: {}", idempotencyKey);
        return ResponseEntity.ok(paymentService.getByIdempotencyKey(idempotencyKey));
    }

}
