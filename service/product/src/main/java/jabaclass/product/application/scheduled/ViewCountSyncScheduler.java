package jabaclass.product.application.scheduled;

import java.util.UUID;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jabaclass.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class ViewCountSyncScheduler {

	private static final String VIEW_COUNT_KEY_PREFIX = "view_count:product:";

	private final ProductRepository productRepository;
	private final StringRedisTemplate redisTemplate;

	// 매시 정각 Redis view_count → DB 반영
	@Scheduled(cron = "0 0 * * * *")
	@Transactional
	public void syncViewCount() {
		log.info("조회수 DB 동기화 시작");

		ScanOptions options = ScanOptions.scanOptions()
			.match(VIEW_COUNT_KEY_PREFIX + "*")
			.count(100)
			.build();

		int syncCount = 0;
		try (Cursor<String> cursor = redisTemplate.scan(options)) {
			while (cursor.hasNext()) {
				String key = cursor.next();
				String value = redisTemplate.opsForValue().get(key);
				if (value == null) {
					continue;
				}

				UUID productId = UUID.fromString(key.replace(VIEW_COUNT_KEY_PREFIX, ""));
				long viewCount = Long.parseLong(value);
				productRepository.updateViewCount(productId, viewCount);
				syncCount++;
			}
		} catch (Exception e) {
			log.error("조회수 DB 동기화 실패: {}", e.getMessage());
		}

		log.info("조회수 DB 동기화 완료 - 동기화 상품 수: {}", syncCount);
	}
}