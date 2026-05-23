package jabaclass.product.application.usecase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jabaclass.product.presentation.dto.response.FavoritesResponseDto;

public interface FavoriteUseCase {

	FavoritesResponseDto createFavorite(UUID productId, UUID userId);

	void deleteFavorite(UUID favoriteId, UUID userId);

	List<FavoritesResponseDto> findByUserIdAndDeleteDtIsNull(UUID userId);

	long getLikeCount(UUID productId);

	Map<UUID, Long> getLikeCountBatch(List<UUID> productIds);

	// 성능 비교용 - Redis 없이 DB 직접 조회
	Map<UUID, Long> getLikeCountBatchNoCache(List<UUID> productIds);
}
