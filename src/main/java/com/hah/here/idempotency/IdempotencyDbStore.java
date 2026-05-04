package com.hah.here.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Redis 장애 시 폴백 + complete 시 영속 저장 담당.
 *
 * IdempotencyService 와 분리: AOP @Transactional 이 외부 호출에서만 작동하므로
 * fallback 흐름에서도 트랜잭션이 정상 적용되도록 별도 빈으로 둠.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyDbStore {

    private final IdempotencyRecordRepository repository;

    /**
     * DB 기반 claim. 동시 두 요청 race 는 PK UNIQUE 충돌로 한 명만 first() 통과.
     */
    @Transactional
    public IdempotencyOutcome claim(String idempotencyKey) {
        Optional<IdempotencyRecord> existing = repository.findById(idempotencyKey);
        if (existing.isPresent()) {
            return mapExisting(existing.get());
        }
        try {
            repository.saveAndFlush(IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .build());
            return IdempotencyOutcome.first();
        } catch (DataIntegrityViolationException e) {
            // 다른 인스턴스가 동시 INSERT 성공. 다시 조회.
            return repository.findById(idempotencyKey)
                    .map(this::mapExisting)
                    .orElse(IdempotencyOutcome.inFlight());
        }
    }

    /**
     * 응답 영속. 기존 INFLIGHT row 가 있으면 update, 없으면 INSERT (with COMPLETED).
     */
    @Transactional
    public void complete(String idempotencyKey, String responseJson) {
        IdempotencyRecord record = repository.findById(idempotencyKey)
                .orElseGet(() -> IdempotencyRecord.builder()
                        .idempotencyKey(idempotencyKey)
                        .build());
        record.markCompleted(responseJson);
        repository.save(record);
    }

    /**
     * INFLIGHT 마커 제거. 이미 COMPLETED 면 보존.
     */
    @Transactional
    public void release(String idempotencyKey) {
        repository.findById(idempotencyKey).ifPresent(record -> {
            if (record.getStatus() == IdempotencyRecord.Status.INFLIGHT) {
                repository.delete(record);
            }
        });
    }

    private IdempotencyOutcome mapExisting(IdempotencyRecord record) {
        if (record.getStatus() == IdempotencyRecord.Status.COMPLETED) {
            return IdempotencyOutcome.cached(record.getResponseJson());
        }
        return IdempotencyOutcome.inFlight();
    }
}
