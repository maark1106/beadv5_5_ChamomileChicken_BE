package jabaclass.product.application.scheduled;

import java.util.Map;
import java.util.UUID;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jabaclass.product.domain.repository.FavoriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class LikeCountSyncScheduler {

	private static final String LIKE_COUNT_KEY = "like_count:product:";

	private final FavoriteRepository favoriteRepository;
	private final StringRedisTemplate redisTemplate;

	@Scheduled(cron = "0 0 3 * * *")
	public void syncLikeCount() {
		log.info("좋아요 수 보정 시작");

		Map<UUID, Long> countMap = favoriteRepository.countAllGroupByProductId();

		countMap.forEach((productId, count) ->
			redisTemplate.opsForValue().set(LIKE_COUNT_KEY + productId, String.valueOf(count))
		);

		ScanOptions options = ScanOptions.scanOptions().match(LIKE_COUNT_KEY + "*").count(100).build();
		int resetCount = 0;
		try (Cursor<String> cursor = redisTemplate.scan(options)) {
			while (cursor.hasNext()) {
				String key = cursor.next();
				UUID productId = UUID.fromString(key.replace(LIKE_COUNT_KEY, ""));
				if (!countMap.containsKey(productId)) {
					redisTemplate.opsForValue().set(key, "0");
					resetCount++;
				}
			}
		} catch (Exception e) {
			log.error("좋아요 수 0 보정 실패: {}", e.getMessage());
		}

		log.info("좋아요 수 보정 완료 - 동기화: {}개, 0 보정: {}개", countMap.size(), resetCount);
	}
}