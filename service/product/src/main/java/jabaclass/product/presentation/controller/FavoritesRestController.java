package jabaclass.product.presentation.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jabaclass.product.application.usecase.FavoriteUseCase;
import jabaclass.product.common.auth.CurrentUser;
import jabaclass.product.common.exception.ApiResponseDto;
import jabaclass.product.presentation.dto.response.FavoritesResponseDto;
import jabaclass.product.presentation.openapi.FavoritesOpenApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Slf4j
public class FavoritesRestController implements FavoritesOpenApi {

	private final FavoriteUseCase favoriteUseCase;

	@Override
	@PostMapping("/{productId}/likes")
	public ResponseEntity<ApiResponseDto<FavoritesResponseDto>> create(
		@PathVariable UUID productId,
		@CurrentUser UUID userId) {
		FavoritesResponseDto response = favoriteUseCase.createFavorite(productId, userId);

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponseDto.success(HttpStatus.CREATED, "성공적으로 등록 되었습니다.", response));
	}

	@Override
	@DeleteMapping("/{productId}/likes")
	public ResponseEntity<ApiResponseDto<FavoritesResponseDto>> delete(
		@RequestParam UUID likeId,
		@CurrentUser UUID userId) {
		favoriteUseCase.deleteFavorite(likeId, userId);

		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 삭제 되었습니다.", null));
	}

	@Override
	@GetMapping("/me/likes")
	public ResponseEntity<ApiResponseDto<List<FavoritesResponseDto>>> getList(@CurrentUser UUID userId) {
		List<FavoritesResponseDto> response = favoriteUseCase.findByUserIdAndDeleteDtIsNull(userId);

		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 검색 되었습니다.", response));
	}
}
