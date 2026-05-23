package jabaclass.product.presentation.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jabaclass.product.domain.model.Favorite;
import jabaclass.product.domain.model.Product;

@Schema(description = "즐겨찾기")
public record FavoritesResponseDto(

	@Schema(description = "즐겨찾기 Id", example = "550e8400-e29b-41d4-a716-446655440000")
	UUID id,

	@Schema(description = "상품 Id", example = "550e8400-e29b-41d4-a716-446655440000")
	UUID productId,

	@Schema(description = "상품 제목", example = "서울 한강 카약 체험")
	String productTitle,

	@Schema(description = "상품 썸네일 경로", example = "/images/product/thumbnail.jpg")
	String thumbnailPath,

	@Schema(description = "상품 가격", example = "35000")
	BigDecimal price

) {
	public static FavoritesResponseDto from(Favorite favorite) {
		return new FavoritesResponseDto(
			favorite.getId(),
			favorite.getProductId(),
			null,
			null,
			null
		);
	}

	public static FavoritesResponseDto from(Favorite favorite, Product product) {
		return new FavoritesResponseDto(
			favorite.getId(),
			product.getId(),
			product.getTitle(),
			product.getThumbnailPath(),
			product.getPrice()
		);
	}
}