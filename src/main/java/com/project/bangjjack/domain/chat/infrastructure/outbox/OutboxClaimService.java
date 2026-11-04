package com.project.bangjjack.domain.chat.infrastructure.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** Redis 호출과 독립된 짧은 트랜잭션. 토큰으로 선점 만료 후 이전 작업자의 갱신을 막는다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class OutboxClaimService {
    private final OutboxEventRepository repository;

    public record Claim(Long id, Long roomId, String eventType, String payload, String token) {}

    public Optional<Claim> claim(LocalDateTime threshold, long leaseMs) {
        LocalDateTime now = LocalDateTime.now();
        return repository.findClaimableForUpdate(threshold, now).stream().findFirst().map(row -> {
            String token = UUID.randomUUID().toString();
            row.claim(token, now.plusNanos(leaseMs * 1_000_000));
            return new Claim(row.getId(), row.getRoomId(), row.getEventType(), row.getPayload(), token);
        });
    }

    public boolean complete(Claim claim) {
        return repository.completeClaim(claim.id(), claim.token(), LocalDateTime.now()) == 1;
    }

    public boolean fail(Claim claim, String reason) {
        return repository.failClaim(claim.id(), claim.token(), LocalDateTime.now(), reason) == 1;
    }

    public void release(Claim claim) {
        repository.releaseClaim(claim.id(), claim.token());
    }
}
