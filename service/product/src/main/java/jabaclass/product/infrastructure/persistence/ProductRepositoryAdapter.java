package jabaclass.product.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import jabaclass.product.domain.model.Product;
import jabaclass.product.domain.model.status.CategoryType;
import jabaclass.product.domain.model.status.RegionType;
import jabaclass.product.domain.model.status.ProductStatus;
import jabaclass.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ProductRepositoryAdapter implements ProductRepository {

	private final ProductJpaRepository productJpaRepository;

	@Override
	public Product save(Product product) {
		return productJpaRepository.save(product);
	}

	@Override
	public Optional<Product> findById(UUID id) {
		return productJpaRepository.findById(id);
	}

	@Override
	public Page<Product> findByStatusAndTitleContainingAndDeleteDtIsNull(ProductStatus status, String keyword,
		Pageable pageable) {
		return productJpaRepository.findByStatusAndTitleContainingAndDeleteDtIsNull(status, keyword,
			pageable);
	}

	@Override
	public Page<Product> findByStatusAndDeleteDtIsNull(ProductStatus status, Pageable pageable) {
		return productJpaRepository.findByStatusAndDeleteDtIsNull(status, pageable);
	}

	@Override
	public Optional<Product> findByIdAndSellerId(UUID productId, UUID sellerId) {
		return productJpaRepository.findByIdAndSellerId(productId, sellerId);
	}

	@Override
	public List<Product> findAllByIds(List<UUID> productIds) {
		return productJpaRepository.findAllByIdIn(productIds);
	}

	@Override
	public List<Product> findAllByIdsAndDeleteDtIsNull(List<UUID> productIds) {
		return productJpaRepository.findAllByIdInAndDeleteDtIsNull(productIds);
	}

	@Override
	public Page<Product> findAllByDeleteDtIsNull(Pageable pageable) {
		return productJpaRepository.findAllByDeleteDtIsNull(pageable);
	}

	@Override
	public Page<Product> findByCategoryAndRegion(CategoryType category, RegionType region, Pageable pageable) {
		return productJpaRepository.findByCategoryAndRegion(category, region, ProductStatus.ENABLE, pageable);
	}

	@Override
	public void updateViewCount(UUID productId, long viewCount) {
		productJpaRepository.updateViewCount(productId, viewCount);
	}

	@Override
	public void incrementViewCount(UUID productId) {
		productJpaRepository.incrementViewCount(productId);
	}

}
