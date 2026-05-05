package com.idempotency.controller;

import com.idempotency.dto.PaymentRequestDto;
import com.idempotency.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Happy path — new payment
    // -------------------------------------------------------------------------

    @Test
    void testProcessPaymentWithValidIdempotencyKey() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(
            PaymentRequestDto.builder().amount(100.0).currency("GHS").build());

        mockMvc.perform(post("/process-payment")
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Cache-Hit", "false"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.transactionId").exists());
    }

    // -------------------------------------------------------------------------
    // Idempotency replay — same key + same payload returns cached response
    // -------------------------------------------------------------------------

    @Test
    void testDuplicateRequestReturnsCachedResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(
            PaymentRequestDto.builder().amount(50.0).currency("USD").build());

        // First request — processes and stores
        String firstResponse = mockMvc.perform(post("/process-payment")
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Cache-Hit", "false"))
            .andReturn().getResponse().getContentAsString();

        // Second request — same key, same payload must return cached data
        mockMvc.perform(post("/process-payment")
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Cache-Hit", "true"))
            .andExpect(content().json(firstResponse));
    }

    // -------------------------------------------------------------------------
    // Conflict — same key + different payload must be rejected
    // -------------------------------------------------------------------------

    @Test
    void testSameKeyDifferentPayloadReturnsConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        String originalBody = objectMapper.writeValueAsString(
            PaymentRequestDto.builder().amount(100.0).currency("GHS").build());
        String conflictBody = objectMapper.writeValueAsString(
            PaymentRequestDto.builder().amount(999.0).currency("GHS").build());

        // First request — accepted
        mockMvc.perform(post("/process-payment")
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(originalBody))
            .andExpect(status().isCreated());

        // Second request — different amount must be rejected with 422
        mockMvc.perform(post("/process-payment")
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(conflictBody))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").exists());
    }

    // -------------------------------------------------------------------------
    // Missing header
    // -------------------------------------------------------------------------

    @Test
    void testProcessPaymentWithoutIdempotencyKey() throws Exception {
        String body = objectMapper.writeValueAsString(
            PaymentRequestDto.builder().amount(100.0).currency("GHS").build());

        mockMvc.perform(post("/process-payment")
            .contentType("application/json")
            .content(body))
            .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Key too long — must be rejected before hitting the DB
    // -------------------------------------------------------------------------

    @Test
    void testKeyExceeding255CharactersReturnsBadRequest() throws Exception {
        String longKey = "k".repeat(256);
        String body = objectMapper.writeValueAsString(
            PaymentRequestDto.builder().amount(100.0).currency("GHS").build());

        mockMvc.perform(post("/process-payment")
            .header("Idempotency-Key", longKey)
            .contentType("application/json")
            .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
    }

    // -------------------------------------------------------------------------
    // Invalid payload
    // -------------------------------------------------------------------------

    @Test
    void testNegativeAmountReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/process-payment")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content("{\"amount\": -50, \"currency\": \"GHS\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void testInvalidCurrencyFormatReturnsBadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(
            PaymentRequestDto.builder().amount(100.0).currency("usd").build());

        mockMvc.perform(post("/process-payment")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    void testEmptyCurrencyReturnsBadRequest() throws Exception {
        String body = "{\"amount\": 100.0, \"currency\": \"\"}";

        mockMvc.perform(post("/process-payment")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content(body))
            .andExpect(status().isBadRequest());
    }
}
