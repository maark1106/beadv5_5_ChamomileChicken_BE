package jabaclass.product.application.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jabaclass.product.application.exception.BusinessException;
import jabaclass.product.application.usecase.FavoriteUseCase;
import jabaclass.product.common.exception.CommonErrorCode;
import jabaclass.product.domain.model.Favorite;
import jabaclass.product.domain.model.Product;
import jabaclass.product.domain.repository.FavoriteRepository;
import jabaclass.product.domain.repository.ProductRepository;
import jabaclass.product.infrastructure.event.dto.ProductWishlistedEvent;
import jabaclass.product.presentation.dto.response.FavoritesResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class FavoriteService implements FavoriteUseCase {

	private static final String LIKE_COUNT_KEY = "like_count:product:";

	private final FavoriteRepository favoriteRepository;
	private final ProductRepository productRepository;
	private final ApplicationEventPublisher publisher;
	private final StringRedisTemplate redisTemplate;

	@Override
	@Transactional
	public FavoritesResponseDto createFavorite(UUID productId, UUID userId) {
		log.info("찜 생성 요청 수신: userId={}, productId={}", userId, productId);

		productRepository.findById(productId)
			.orElseThrow(() -> new BusinessException(CommonErrorCode.PRODUCT_NOT_FOUND));

		Favorite existing = favoriteRepository.findByUserIdAndProductIdAndDeleteDtIsNull(userId, productId);
		if (existing != null) {
			throw new BusinessException(CommonErrorCode.ALREADY_LIKED);
		}

		Favorite favorite = Favorite.builder()
			.productId(productId)
			.userId(userId)
			.build();

		Favorite savedFavorite = favoriteRepository.save(favorite);
		log.info("찜 저장 완료: favoriteId={}, userId={}, productId={}", savedFavorite.getId(), userId, productId);

		incrementLikeCount(productId);

		publisher.publishEvent(ProductWishlistedEvent.of(userId, productId));

		return FavoritesResponseDto.from(savedFavorite);
	}

	@Override
	@Transactional
	public void deleteFavorite(UUID favoriteId, UUID userId) {
		Favorite matched = favoriteRepository.findByIdAndUserIdAndDeleteDtIsNull(favoriteId, userId);

		if (matched == null) {
			throw new BusinessException(CommonErrorCode.NOT_MATCH_USER_LIKE);
		}

		matched.changeDelete();
		decrementLikeCount(matched.getProductId());
	}

	@Override
	public List<FavoritesResponseDto> findByUserIdAndDeleteDtIsNull(UUID userId) {
		List<Favorite> favorites = favoriteRepository.findByUserIdAndDeleteDtIsNull(userId);

		if (favorites.isEmpty()) {
			return List.of();
		}

		List<UUID> productIds = favorites.stream()
			.map(Favorite::getProductId)
			.distinct()
			.toList();

		Map<UUID, Product> productMap = productRepository.findAllByIdsAndDeleteDtIsNull(productIds)
			.stream()
			.collect(Collectors.toMap(Product::getId, p -> p));

		return favorites.stream()
			.filter(f -> {
				if (!productMap.containsKey(f.getProductId())) {
					log.warn("찜 항목의 상품을 찾을 수 없습니다. favoriteId={}, productId={}", f.getId(), f.getProductId());
					return false;
				}
				return true;
			})
			.map(f -> FavoritesResponseDto.from(f, productMap.get(f.getProductId())))
			.toList();
	}

	@Override
	public Map<UUID, Long> getLikeCountBatch(List<UUID> productIds) {
		Map<UUID, Long> dbCounts = new HashMap<>(favoriteRepository.countGroupByProductIdIn(productIds));
		productIds.forEach(id -> dbCounts.putIfAbsent(id, 0L));
		dbCounts.forEach((id, count) ->
			redisTemplate.opsForValue().setIfAbsent(LIKE_COUNT_KEY + id, String.valueOf(count))
		);
		return dbCounts;
	}

	@Override
	public Map<UUID, Long> getLikeCountBatchNoCache(List<UUID> productIds) {
		Map<UUID, Long> dbCounts = new HashMap<>(favoriteRepository.countGroupByProductIdIn(productIds));
		productIds.forEach(id -> dbCounts.putIfAbsent(id, 0L));
		return dbCounts;
	}

	@Override
	public long getLikeCount(UUID productId) {
		String key = LIKE_COUNT_KEY + productId;
		String cached = redisTemplate.opsForValue().get(key);

		if (cached != null) {
			return Long.parseLong(cached);
		}

		// Redis miss: DB에서 COUNT 조회 후 SETNX로 캐싱
		long count = favoriteRepository.countByProductIdAndDeleteDtIsNull(productId);
		redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(count));
		return count;
	}

	private void incrementLikeCount(UUID productId) {
		try {
			String key = LIKE_COUNT_KEY + productId;
			// 키가 없으면 DB 기준으로 초기화 후 INCR
			if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
				long count = favoriteRepository.countByProductIdAndDeleteDtIsNull(productId);
				redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(count));
			}
			redisTemplate.opsForValue().increment(key);
		} catch (Exception e) {
			log.warn("Redis like_count INCR 실패 (productId={}): {}", productId, e.getMessage());
		}
	}

	private void decrementLikeCount(UUID productId) {
		try {
			String key = LIKE_COUNT_KEY + productId;
			if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
				redisTemplate.opsForValue().decrement(key);
			}
		} catch (Exception e) {
			log.warn("Redis like_count DECR 실패 (productId={}): {}", productId, e.getMessage());
		}
	}
}