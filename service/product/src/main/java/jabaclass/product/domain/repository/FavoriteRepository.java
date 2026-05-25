package jabaclass.product.domain.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jabaclass.product.domain.model.Favorite;

public interface FavoriteRepository {

	Favorite save(Favorite favorite);

	List<Favorite> findByUserIdAndDeleteDtIsNull(UUID userId);

	Favorite findByIdAndUserIdAndDeleteDtIsNull(UUID favoriteId, UUID userId);

	Favorite findByUserIdAndProductIdAndDeleteDtIsNull(UUID userId, UUID productId);

	long countByProductIdAndDeleteDtIsNull(UUID productId);

	Map<UUID, Long> countAllGroupByProductId();

	Map<UUID, Long> countGroupByProductIdIn(List<UUID> productIds);
}
