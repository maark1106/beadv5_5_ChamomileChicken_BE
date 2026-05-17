package jabaclass.product.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jabaclass.product.domain.model.Product;
import jabaclass.product.domain.model.status.CategoryType;
import jabaclass.product.domain.model.status.RegionType;
import jabaclass.product.domain.model.status.ProductStatus;

public interface ProductJpaRepository extends JpaRepository<Product, UUID> {
	Page<Product> findByStatusAndTitleContainingAndDeleteDtIsNull(ProductStatus status, String keyword,
		Pageable pageable);

	Page<Product> findByStatusAndDeleteDtIsNull(ProductStatus status, Pageable pageable);

	Optional<Product> findByIdAndSellerId(UUID productId, UUID sellerId);

	List<Product> findAllByIdIn(List<UUID> productIds);

	List<Product> findAllByIdInAndDeleteDtIsNull(List<UUID> productIds);

	Page<Product> findAllByDeleteDtIsNull(Pageable pageable);

	@Query("""
		SELECT p FROM Product p
		WHERE p.category = :category
		  AND p.region = :region
		  AND p.status = :status
		  AND p.deleteDt IS NULL
		ORDER BY p.regDt DESC
		""")
	Page<Product> findByCategoryAndRegion(
		@Param("category") CategoryType category,
		@Param("region") RegionType region,
		@Param("status") ProductStatus status,
		Pageable pageable);
}
