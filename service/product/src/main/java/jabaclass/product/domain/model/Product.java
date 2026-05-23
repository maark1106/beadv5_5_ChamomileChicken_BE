package jabaclass.product.domain.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jabaclass.product.application.exception.BusinessException;
import jabaclass.product.common.exception.CommonErrorCode;
import jabaclass.product.domain.model.status.CategoryType;
import jabaclass.product.domain.model.status.RegionType;
import jabaclass.product.domain.model.status.ProductStatus;
import jabaclass.product.infrastructure.converter.ProductImageItemsConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@NoArgsConstructor
@SuperBuilder
@Table(name = "products")
public class Product extends EntityBase {

	@Column(name = "seller_id", nullable = false)
	private UUID sellerId;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(name = "max_capacity", nullable = false)
	private int maxCapacity;

	// null이 가능하게 하고 백단이든, 프론트 단이든 둘 중 하나라도 있게 체크하는게 좋을듯
	// ai 분석 상품 내용 분석을 위해 필수 값으로 수정
	@Column(columnDefinition = "text", nullable = false)
	private String description;

	@Column(name = "thumbnail_path")
	private String thumbnailPath;

	//	Postgres 사용시 주석 해제
	//	@Column(name = "description_path", columnDefinition = "jsonb")
	@Builder.Default
	@Column(name = "description_path", columnDefinition = "text")
	@Convert(converter = ProductImageItemsConverter.class)
	private List<ProductImageItem> descriptionImages = new ArrayList<>();

	@Column(nullable = false, precision = 15, scale = 2)
	private BigDecimal price;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ProductStatus status;

	@Column(nullable = false, length = 255)
	private String roadAddress;

	@Column(length = 255)
	private String detailAddress;

	@Column(length = 10)
	private String zonecode;

	@Column(nullable = false, precision = 10, scale = 7)
	private BigDecimal latitude;

	@Column(nullable = false, precision = 10, scale = 7)
	private BigDecimal longitude;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CategoryType category;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RegionType region;

	@Builder.Default
	@Column(name = "view_count", nullable = false)
	private long viewCount = 0L;

	@PrePersist
	public void prePersist() {
		if (this.status == null) {
			this.status = ProductStatus.ENABLE;
		}
	}

	public void changeTitle(String title) {
		if (title.isBlank()) {
			throw new BusinessException(CommonErrorCode.NOT_TITLE);
		}
		this.title = title;
	}

	public void changeMaxCapacity(int maxCapacity) {
		if (maxCapacity <= 0) {
			throw new BusinessException(CommonErrorCode.NOT_MAXCAPACITY);
		}
		this.maxCapacity = maxCapacity;
	}

	public void changePrice(BigDecimal price) {
		if (price.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(CommonErrorCode.NOT_PRICE);
		}
		this.price = price;
	}

	public void changeImages(List<ProductImageItem> images) {
		if (images.size() > 10) {
			throw new BusinessException(CommonErrorCode.IMAGE_LIMIT_EXCEEDED);
		}
		this.descriptionImages = images;
		this.thumbnailPath = images.isEmpty() ? null : images.get(0).storagePath();
	}

	public void changeDescription(String description) {
		this.description = description;
	}

	public void changeStatus(ProductStatus status) {
		this.status = status;
	}

	public void changeRoadAddress(String roadAddress) {
		this.roadAddress = roadAddress;
	}

	public void changeDetailAddress(String detailAddress) {
		this.detailAddress = detailAddress;
	}

	public void changeZonecode(String zonecode) {
		this.zonecode = zonecode;
	}

	public void changeLatitude(BigDecimal latitude) {
		this.latitude = latitude;
	}

	public void changeLongitude(BigDecimal longitude) {
		this.longitude = longitude;
	}

	public void changeCategory(CategoryType category) {
		this.category = category;
	}

	public void changeRegion(RegionType region) {
		this.region = region;
	}

	public void syncViewCount(long viewCount) {
		this.viewCount = viewCount;
	}

}
