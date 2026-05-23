package jabaclass.product.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import jabaclass.product.domain.model.Favorite;
import jabaclass.product.domain.repository.FavoriteRepository;
import jabaclass.product.infrastructure.persistence.dto.ProductLikeCountDto;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class FavoriteRepositoryAdapter implements FavoriteRepository {

	private final FavoriteJpaRepository favoriteJpaRepository;

	@Override
	public Favorite save(Favorite favorite) {
		return favoriteJpaRepository.save(favorite);
	}

	@Override
	public List<Favorite> findByUserIdAndDeleteDtIsNull(UUID userId) {
		return favoriteJpaRepository.findByUserIdAndDeleteDtIsNull(userId);
	}

	@Override
	public Favorite findByIdAndUserIdAndDeleteDtIsNull(UUID favoriteId, UUID userId) {
		return favoriteJpaRepository.findByIdAndUserIdAndDeleteDtIsNull(favoriteId, userId);
	}

	@Override
	public Favorite findByUserIdAndProductIdAndDeleteDtIsNull(UUID userId, UUID productId) {
		return favoriteJpaRepository.findByUserIdAndProductIdAndDeleteDtIsNull(userId, productId);
	}

	@Override
	public long countByProductIdAndDeleteDtIsNull(UUID productId) {
		return favoriteJpaRepository.countByProductIdAndDeleteDtIsNull(productId);
	}

	@Override
	public Map<UUID, Long> countAllGroupByProductId() {
		return favoriteJpaRepository.countGroupByProductId().stream()
			.collect(Collectors.toMap(ProductLikeCountDto::productId, ProductLikeCountDto::count));
	}

	@Override
	public Map<UUID, Long> countGroupByProductIdIn(List<UUID> productIds) {
		return favoriteJpaRepository.countGroupByProductIdIn(productIds).stream()
			.collect(Collectors.toMap(ProductLikeCountDto::productId, ProductLikeCountDto::count));
	}
}
