package com.hah.here.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hah.here.support.RedisTestContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency Layer 1 (Redis) + DB 영속 이중 저장소 검증.
 *
 *  - 정상 모드: claim Redis only, complete DB+Redis
 *  - DB 영속화로 재요청 시 cached 응답 재현
 */
@SpringBootTest
class IdempotencyServiceIntegrationTest extends RedisTestContainerSupport {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyDbStore dbStore;

    @Autowired
    private IdempotencyRecordRepository recordRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private String key;

    @BeforeEach
    void cleanState() {
        key = "test:" + UUID.randomUUID();
        redisTemplate.delete("idem:" + key);
        recordRepository.deleteAll();
    }

    @Nested
    @DisplayName("정상 흐름 — Redis 우선")
    class NormalFlow {

        @Test
        @DisplayName("첫 claim → FIRST 반환, Redis 에 INFLIGHT 마커")
        void firstClaim() {
            IdempotencyOutcome outcome = idempotencyService.claim(key);

            assertThat(outcome.isFirst()).isTrue();
            assertThat(redisTemplate.opsForValue().get("idem:" + key)).isEqualTo("__INFLIGHT__");
        }

        @Test
        @DisplayName("같은 키 재진입 — INFLIGHT 응답")
        void inFlight() {
            idempotencyService.claim(key);

            IdempotencyOutcome second = idempotencyService.claim(key);

            assertThat(second.isInFlight()).isTrue();
        }

        @Test
        @DisplayName("complete 후 재진입 — CACHED 응답 (Redis hit)")
        void cachedFromRedis() {
            idempotencyService.claim(key);
            TestResponse response = new TestResponse(42L, "OK");

            idempotencyService.complete(key, response);
            IdempotencyOutcome second = idempotencyService.claim(key);

            assertThat(second.isCached()).isTrue();
            assertThat(second.cachedJson()).contains("\"id\":42");
            assertThat(second.cachedJson()).contains("\"status\":\"OK\"");
        }

        @Test
        @DisplayName("complete 시 DB 에도 영속 — Redis 장애 중에도 응답 재현 가능")
        void completePersistsToDb() {
            idempotencyService.claim(key);
            TestResponse response = new TestResponse(7L, "PERSIST");

            idempotencyService.complete(key, response);

            IdempotencyRecord record = recordRepository.findById(key).orElseThrow();
            assertThat(record.getStatus()).isEqualTo(IdempotencyRecord.Status.COMPLETED);
            assertThat(record.getResponseJson()).contains("\"id\":7");
        }

        @Test
        @DisplayName("release — INFLIGHT 마커 제거 (Redis only)")
        void release() {
            idempotencyService.claim(key);
            assertThat(redisTemplate.opsForValue().get("idem:" + key)).isEqualTo("__INFLIGHT__");

            idempotencyService.release(key);

            assertThat(redisTemplate.opsForValue().get("idem:" + key)).isNull();
        }

        @Test
        @DisplayName("release 후 재 claim — 다시 FIRST")
        void releaseAllowsNewClaim() {
            idempotencyService.claim(key);
            idempotencyService.release(key);

            IdempotencyOutcome second = idempotencyService.claim(key);

            assertThat(second.isFirst()).isTrue();
        }
    }

    @Nested
    @DisplayName("DbStore — fallback 모드 시뮬")
    class DbStoreFallback {

        @Test
        @DisplayName("DbStore.claim — 첫 호출 → FIRST + DB 에 INFLIGHT row")
        void dbClaimFirst() {
            IdempotencyOutcome outcome = dbStore.claim(key);

            assertThat(outcome.isFirst()).isTrue();
            IdempotencyRecord record = recordRepository.findById(key).orElseThrow();
            assertThat(record.getStatus()).isEqualTo(IdempotencyRecord.Status.INFLIGHT);
        }

        @Test
        @DisplayName("DbStore.claim — 두 번째 호출 (INFLIGHT 잔존) → INFLIGHT")
        void dbClaimInFlight() {
            dbStore.claim(key);

            IdempotencyOutcome second = dbStore.claim(key);

            assertThat(second.isInFlight()).isTrue();
        }

        @Test
        @DisplayName("DbStore.complete 후 재 claim → CACHED")
        void dbCachedRecovery() {
            dbStore.claim(key);
            dbStore.complete(key, "{\"id\":99}");

            IdempotencyOutcome second = dbStore.claim(key);

            assertThat(second.isCached()).isTrue();
            assertThat(second.cachedJson()).contains("\"id\":99");
        }

        @Test
        @DisplayName("DbStore.release — INFLIGHT 만 삭제, COMPLETED 는 보존")
        void dbReleaseOnlyInflight() {
            dbStore.claim(key);
            dbStore.release(key);
            assertThat(recordRepository.findById(key)).isEmpty();

            // COMPLETED 인 record 는 release 시 보존
            String key2 = "test:" + UUID.randomUUID();
            dbStore.claim(key2);
            dbStore.complete(key2, "{}");
            dbStore.release(key2);
            assertThat(recordRepository.findById(key2)).isPresent();
        }
    }

    @Nested
    @DisplayName("resolveKey — 헤더 fallback")
    class ResolveKey {

        @Test
        @DisplayName("헤더 있음 — 그대로 사용")
        void useHeader() {
            String resolved = idempotencyService.resolveKey("custom-key", 1L, 2L);
            assertThat(resolved).isEqualTo("custom-key");
        }

        @Test
        @DisplayName("헤더 비어있음 — auto:userId:productId 자동 생성")
        void autoKey() {
            assertThat(idempotencyService.resolveKey(null, 1L, 2L)).isEqualTo("auto:1:2");
            assertThat(idempotencyService.resolveKey("", 1L, 2L)).isEqualTo("auto:1:2");
            assertThat(idempotencyService.resolveKey("  ", 1L, 2L)).isEqualTo("auto:1:2");
        }
    }

    record TestResponse(Long id, String status) {}
}
