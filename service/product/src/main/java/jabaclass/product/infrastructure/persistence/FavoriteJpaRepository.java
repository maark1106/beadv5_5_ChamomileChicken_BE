package jabaclass.product.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import jabaclass.product.infrastructure.persistence.dto.ProductLikeCountDto;

import jabaclass.product.domain.model.Favorite;

public interface FavoriteJpaRepository extends JpaRepository<Favorite, UUID> {

	List<Favorite> findByUserIdAndDeleteDtIsNull(UUID userId);

	Favorite findByIdAndUserIdAndDeleteDtIsNull(UUID favoriteId, UUID userId);

	Favorite findByUserIdAndProductIdAndDeleteDtIsNull(UUID userId, UUID productId);

	long countByProductIdAndDeleteDtIsNull(UUID productId);

	@Query("""
		SELECT new jabaclass.product.infrastructure.persistence.dto.ProductLikeCountDto(f.productId, COUNT(f))
		FROM Favorite f
		WHERE f.deleteDt IS NULL
		GROUP BY f.productId
		""")
	List<ProductLikeCountDto> countGroupByProductId();

	@Query("""
		SELECT new jabaclass.product.infrastructure.persistence.dto.ProductLikeCountDto(f.productId, COUNT(f))
		FROM Favorite f
		WHERE f.productId IN :productIds AND f.deleteDt IS NULL
		GROUP BY f.productId
		""")
	List<ProductLikeCountDto> countGroupByProductIdIn(List<UUID> productIds);
}
