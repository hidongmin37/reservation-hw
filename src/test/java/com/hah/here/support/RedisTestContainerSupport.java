package com.hah.here.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 base — Redis + MySQL 컨테이너.
 *
 * **Singleton container 패턴**: JVM 전역에서 컨테이너 1번만 시작 + JVM 종료 시 자동 정리.
 * @Testcontainers + @Container 조합은 *클래스마다 새 컨테이너* 를 시도해 Spring context 캐시
 * 와 충돌 (이미 종료된 host/port 로 연결 시도). static initializer 로 한 번만 시작 + 모든
 * 테스트 클래스가 공유하는 게 안정적.
 */
public abstract class RedisTestContainerSupport {

    static final RedisContainer REDIS;
    static final MySQLContainer<?> MYSQL;

    static {
        REDIS = new RedisContainer(DockerImageName.parse("redis:7.4-alpine"));
        REDIS.start();

        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("here_db")
                .withUsername("root")
                .withPassword("root");
        MYSQL.start();

        // JVM 종료 시 자동 정리 (testcontainers 의 ryuk 가 처리하지만 명시적으로도 안전)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            REDIS.stop();
            MYSQL.stop();
        }));
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Redis
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);

        // MySQL
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
