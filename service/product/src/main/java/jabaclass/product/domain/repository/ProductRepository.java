package jabaclass.product.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import jabaclass.product.domain.model.Product;
import jabaclass.product.domain.model.status.CategoryType;
import jabaclass.product.domain.model.status.RegionType;
import jabaclass.product.domain.model.status.ProductStatus;

public interface ProductRepository {
	// 상품 등록/수정
	Product save(Product product);

	// 상품 검색
	Optional<Product> findById(UUID id);

	// 키워드가 있는 경우의 전체 검색
	Page<Product> findByStatusAndTitleContainingAndDeleteDtIsNull(ProductStatus status, String keyword,
		Pageable pageable);

	// 키워드가 없는 경우의 전체 검색
	Page<Product> findByStatusAndDeleteDtIsNull(ProductStatus status, Pageable pageable);

	// 판매자와 상품 매치
	Optional<Product> findByIdAndSellerId(UUID productId, UUID sellerId);

	List<Product> findAllByIds(List<UUID> productIds);

	List<Product> findAllByIdsAndDeleteDtIsNull(List<UUID> productIds);

	// ES 초기 마이그레이션용 — 삭제되지 않은 전체 상품 배치 조회
	Page<Product> findAllByDeleteDtIsNull(Pageable pageable);

	Page<Product> findByCategoryAndRegion(CategoryType category, RegionType region, Pageable pageable);
}
