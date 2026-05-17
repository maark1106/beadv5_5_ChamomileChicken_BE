package jabaclass.product.presentation.dto.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jabaclass.product.domain.model.status.CategoryType;
import jabaclass.product.domain.model.status.RegionType;
import jabaclass.product.domain.model.status.ProductStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "상품 수정")
public record UpdateProductRequestDto(
	@NotBlank(message = "상품명을 입력해주세요.")
	@Size(max = 100)
	@Schema(description = "상품명", example = "향수 공방")
	String title,

	@Min(value = 1, message = "예약 가능 인원수를 입력해주세요.")
	@Schema(description = "총 인원수", example = "10")
	int maxCapacity,

	@Schema(description = "설명", example = "상품에 대한 설명입니다.")
	String description,

	@Size(max = 10, message = "사진은 최대 10개까지 등록 가능합니다.")
	@Schema(description = "상품 이미지 file 목록")
	List<UUID> imageIds,

	@Min(value = 1, message = "가격은 1원 이상이어야 합니다.")
	@Schema(description = "가격", example = "550000.00")
	BigDecimal price,

	@Schema(description = "상태", example = "ENABLE")
	ProductStatus status,

	@NotBlank(message = "도로명 주소를 입력해주세요.")
	@Schema(description = "도로명 주소", example = "경기 성남시 분당구 판교역로 166")
	String roadAddress,

	@Schema(description = "상세 주소", example = "카카오 판교 아지트 1층")
	String detailAddress,

	@Schema(description = "우편 번호", example = "13529")
	String zonecode,

	@NotNull(message = "위도를 입력해주세요.")
	@Schema(description = "위도", example = "37.3952")
	BigDecimal latitude,

	@NotNull(message = "경도를 입력해주세요.")
	@Schema(description = "경도", example = "127.1110")
	BigDecimal longitude,

	@NotNull(message = "카테고리를 선택해주세요.")
	@Schema(description = "카테고리", example = "SPORTS")
	CategoryType category,

	@NotNull(message = "지역을 선택해주세요.")
	@Schema(description = "지역(구)", example = "GANGNAM")
	RegionType region
) {
}
