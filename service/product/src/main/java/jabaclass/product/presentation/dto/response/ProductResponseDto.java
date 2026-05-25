package jabaclass.product.presentation.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jabaclass.product.domain.model.Product;
import jabaclass.product.domain.model.ProductImageItem;
import jabaclass.product.domain.model.status.ProductStatus;
import jabaclass.product.infrastructure.elasticsearch.ProductDocument;

@Schema(description = "상품 응답")
public record ProductResponseDto(
	@Schema(description = "상품 ID", example = "550e8400-e29b-41d4-a716-446655440000")
	UUID id,

	@Schema(description = "판매자 이름", example = "홍길동")
	String sellerName,

	@Schema(description = "상품명", example = "향수 공방")
	String title,

	@Schema(description = "총 인원수", example = "10")
	int maxCapacity,

	@Schema(description = "설명", example = "상품에 대한 설명입니다.")
	String description,

	@Schema(description = "썸네일 경로")
	String thumbnailPath,

	@Schema(description = "상품 이미지 경로 목록")
	List<String> imagePaths,

	@Schema(description = "가격", example = "550000.00")
	BigDecimal price,

	@Schema(description = "상태", example = "공개")
	String statusName,

	@Schema(description = "등록일시", example = "2026-03-04T18:10:00")
	LocalDateTime regDt,

	@Schema(description = "수정일시", example = "2026-03-04T18:12:00")
	LocalDateTime modifyDt,

	@Schema(description = "도로명 주소", example = "경기 성남시 분당구 판교역로 166")
	String roadAddress,

	@Schema(description = "상세 주소", example = "카카오 판교 아지트 1층")
	String detailAddress,

	@Schema(description = "우편 번호", example = "13529")
	String zonecode,

	@Schema(description = "위도", example = "37.3952")
	BigDecimal latitude,

	@Schema(description = "경도", example = "127.1110")
	BigDecimal longitude,

	@Schema(description = "좋아요 수", example = "42")
	long likeCount,

	@Schema(description = "조회수", example = "1024")
	long viewCount
) {

	public static ProductResponseDto from(Product product, String sellerName, long likeCount, long viewCount) {
		List<ProductImageItem> images = product.getDescriptionImages();
		List<String> imagePaths = images == null ? List.of()
			: images.stream().map(ProductImageItem::storagePath).toList();

		return new ProductResponseDto(
			product.getId(),
			sellerName,
			product.getTitle(),
			product.getMaxCapacity(),
			product.getDescription(),
			product.getThumbnailPath(),
			imagePaths,
			product.getPrice(),
			product.getStatus().getStatusName(),
			product.getRegDt(),
			product.getModifyDt(),
			product.getRoadAddress(),
			product.getDetailAddress(),
			product.getZonecode(),
			product.getLatitude(),
			product.getLongitude(),
			likeCount,
			viewCount
		);
	}

	// 생성/수정 응답용 (카운터 불필요)
	public static ProductResponseDto from(Product product, String sellerName) {
		return from(product, sellerName, 0L, 0L);
	}

	public static ProductResponseDto from(ProductDocument document, long likeCount, long viewCount) {
		return new ProductResponseDto(
			UUID.fromString(document.getId()),
			document.getSellerName(),
			document.getTitle(),
			document.getMaxCapacity(),
			document.getDescription(),
			document.getThumbnailPath(),
			List.of(),
			document.getPrice(),
			ProductStatus.valueOf(document.getStatus()).getStatusName(),
			document.getRegDt(),
			null,
			null,
			null,
			null,
			null,
			null,
			likeCount,
			viewCount
		);
	}

	// 목록 조회 시 Redis miss인 경우 0으로 반환
	public static ProductResponseDto from(ProductDocument document) {
		return from(document, 0L, 0L);
	}
}