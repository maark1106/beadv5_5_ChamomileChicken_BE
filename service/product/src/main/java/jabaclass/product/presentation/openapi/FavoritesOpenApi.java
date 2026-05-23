package jabaclass.product.presentation.openapi;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jabaclass.product.common.exception.ApiResponseDto;
import jabaclass.product.presentation.dto.response.FavoritesResponseDto;

@Tag(name = "Favorites", description = "즐겨찾기 API")
public interface FavoritesOpenApi {

	@Operation(summary = "즐겨찾기 생성", description = "즐겨찾기를 생성 합니다.")
	@ApiResponse(
		responseCode = "201",
		description = "즐겨찾기 생성 성공",
		content = @Content(
			schema = @Schema(implementation = ApiResponseDto.class)
		)
	)
	@CommonErrorResponses
	ResponseEntity<ApiResponseDto<FavoritesResponseDto>> create(UUID productId, UUID userId);

	@Operation(summary = "즐겨찾기 해제", description = "즐겨찾기를 해제 합니다.")
	@ApiResponse(
		responseCode = "200",
		description = "즐겨찾기 해제 성공",
		content = @Content(
			schema = @Schema(implementation = ApiResponseDto.class)
		)
	)
	@CommonErrorResponses
	ResponseEntity<ApiResponseDto<FavoritesResponseDto>> delete(UUID likeId, UUID userId);

	@Operation(summary = "즐겨찾기 검색", description = "즐겨찾기를 검색 합니다.")
	@ApiResponse(
		responseCode = "200",
		description = "즐겨찾기 검색 성공",
		content = @Content(
			schema = @Schema(implementation = ApiResponseDto.class)
		)
	)
	@CommonErrorResponses
	ResponseEntity<ApiResponseDto<List<FavoritesResponseDto>>> getList(UUID userId);
}
