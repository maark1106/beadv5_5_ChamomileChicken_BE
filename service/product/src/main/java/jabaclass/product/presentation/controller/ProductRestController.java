package jabaclass.product.presentation.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jabaclass.product.application.usecase.ProductUseCase;
import jabaclass.product.application.usecase.ProductUserUseCase;
import jabaclass.product.common.auth.CurrentUser;
import jabaclass.product.common.exception.ApiResponseDto;
import jabaclass.product.domain.model.status.CategoryType;
import jabaclass.product.domain.model.status.RegionType;
import jabaclass.product.presentation.dto.request.CreateProductRequestDto;
import jabaclass.product.presentation.dto.request.SearchProductRequestDto;
import jabaclass.product.presentation.dto.request.UpdateProductRequestDto;
import org.springframework.web.bind.annotation.RequestParam;
import jabaclass.product.presentation.dto.response.DeleteProductResponseDto;
import jabaclass.product.presentation.dto.response.ProductResponseDto;
import jabaclass.product.presentation.dto.response.ProductUserResponseDto;
import jabaclass.product.presentation.dto.response.SearchProductResponseDto;
import jabaclass.product.presentation.openapi.ProductOpenApi;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Slf4j
public class ProductRestController implements ProductOpenApi {

	private final ProductUseCase productUseCase;
	private final ProductUserUseCase productUserUseCase;

	// 상품 등록
	@Override
	@PostMapping
	public ResponseEntity<ApiResponseDto<ProductResponseDto>> create(
		@RequestBody @Valid CreateProductRequestDto request,
		@CurrentUser UUID userId
	) {
		ProductResponseDto response = productUseCase.create(request, userId);

		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponseDto.success(HttpStatus.CREATED, "성공적으로 등록 되었습니다.", response));
	}

	// 상품 수정
	@Override
	@PutMapping("/{productId}")
	public ResponseEntity<ApiResponseDto<ProductResponseDto>> change(
		@RequestBody @Valid UpdateProductRequestDto request,
		@PathVariable UUID productId
		, @CurrentUser UUID userId
	) {
		ProductResponseDto response = productUseCase.update(request, productId, userId);

		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 수정 되었습니다.", response));
	}

	// 상품 삭제
	@Override
	@DeleteMapping("/{productId}")
	public ResponseEntity<ApiResponseDto<DeleteProductResponseDto>> delete(@PathVariable UUID productId,
		@CurrentUser UUID userId) {
		DeleteProductResponseDto response = productUseCase.delete(productId, userId);

		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 삭제 되었습니다.", response));
	}

	// 상품 전체 검색
	@Override
	@GetMapping
	public ResponseEntity<ApiResponseDto<SearchProductResponseDto>> searchAllProduct(
		@ModelAttribute SearchProductRequestDto request) {
		SearchProductResponseDto response = productUseCase.searchAll(request);

		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 전체 검색이 되었습니다.", response));
	}

	// 내 상품 목록 조회 (SELLER 본인)
	@Override
	@GetMapping("/my")
	public ResponseEntity<ApiResponseDto<SearchProductResponseDto>> searchMyProducts(
		@ModelAttribute SearchProductRequestDto request,
		@CurrentUser UUID userId
	) {
		SearchProductResponseDto response = productUseCase.searchMy(request, userId);
		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 내 상품 목록을 조회하였습니다.", response));
	}

	// 특정 상품 검색
	@Override
	@GetMapping("/{productId}")
	public ResponseEntity<ApiResponseDto<ProductResponseDto>> searchProduct(
		@PathVariable UUID productId,
		@CurrentUser(required = false) UUID userId
	) {
		ProductResponseDto response = productUseCase.searchById(productId, userId);

		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 검색이 되었습니다.", response));
	}

	// 성능 비교용 - 상세 조회 DB UPDATE view_count
	@GetMapping("/{productId}/no-cache")
	public ResponseEntity<ApiResponseDto<ProductResponseDto>> searchByIdNoCache(@PathVariable UUID productId) {
		ProductResponseDto response = productUseCase.searchByIdNoCache(productId);
		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 조회되었습니다.", response));
	}

	// 성능 비교용 - Redis 없이 DB 직접 조회
	@GetMapping("/no-cache")
	public ResponseEntity<ApiResponseDto<SearchProductResponseDto>> searchAllNoCache(
		@ModelAttribute SearchProductRequestDto request) {
		SearchProductResponseDto response = productUseCase.searchAllNoCache(request);
		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 조회되었습니다.", response));
	}

	@GetMapping("/filter")
	public ResponseEntity<ApiResponseDto<SearchProductResponseDto>> filterByCategoryAndRegion(
		@RequestParam CategoryType category,
		@RequestParam RegionType region,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "10") int size
	) {
		SearchProductResponseDto response = productUseCase.filterByCategoryAndRegion(category, region, page, size);
		return ResponseEntity.ok(ApiResponseDto.success(HttpStatus.OK, "성공적으로 조회되었습니다.", response));
	}

	@Override
	@GetMapping("/{productId}/schedules/{scheduleId}/user")
	public ResponseEntity<ApiResponseDto<List<ProductUserResponseDto>>> schedulesSelectUser(
		@PathVariable UUID scheduleId,
		@CurrentUser UUID userId) {
		List<ProductUserResponseDto> response = productUserUseCase.getUser(scheduleId);

		return ResponseEntity.ok()
			.body(ApiResponseDto.success(HttpStatus.OK, "성공적으로 검색 되었습니다.", response));
	}

}
