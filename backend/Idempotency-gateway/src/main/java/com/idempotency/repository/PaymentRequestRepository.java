package com.idempotency.repository;

import com.idempotency.entity.PaymentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, UUID> {

    Optional<PaymentRequest> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query("DELETE FROM PaymentRequest p WHERE p.createdAt < :cutoff")
    int deleteExpiredKeys(@Param("cutoff") LocalDateTime cutoff);

}
