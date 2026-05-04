package com.hah.here;

import com.hah.here.support.RedisTestContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring 컨텍스트 부팅 검증 (smoke test).
 * Testcontainers 의 Redis + MySQL 가 함께 기동되어야 부팅 가능.
 */
@SpringBootTest
class HereApplicationTests extends RedisTestContainerSupport {

	@Test
	void contextLoads() {
	}

}
