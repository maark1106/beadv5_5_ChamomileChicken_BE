package jabaclass.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jabaclass.product.application.usecase.ProductUseCase;
import jabaclass.product.application.usecase.ProductUserUseCase;
import jabaclass.product.common.exception.ApiResponseDto;
import jabaclass.product.domain.model.status.CategoryType;
import jabaclass.product.domain.model.status.ProductStatus;
import jabaclass.product.domain.model.status.RegionType;
import jabaclass.product.domain.model.status.ReservationStatus;
import jabaclass.product.presentation.controller.ProductRestController;
import jabaclass.product.presentation.dto.request.CreateProductRequestDto;
import jabaclass.product.presentation.dto.request.SearchProductRequestDto;
import jabaclass.product.presentation.dto.request.UpdateProductRequestDto;
import jabaclass.product.presentation.dto.response.DeleteProductResponseDto;
import jabaclass.product.presentation.dto.response.ProductResponseDto;
import jabaclass.product.presentation.dto.response.ProductUserResponseDto;
import jabaclass.product.presentation.dto.response.SearchProductResponseDto;

@ExtendWith(MockitoExtension.class)
class ProductRestControllerTest {

	@InjectMocks
	private ProductRestController productRestController;

	@Mock
	private ProductUseCase productUseCase;

	@Mock
	private ProductUserUseCase productUserUseCase;

	private static final UUID PRODUCT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
	private static final UUID SELLER_ID = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
	private static final UUID SCHEDULE_ID = UUID.fromString("323e4567-e89b-12d3-a456-426614174000");
	private static final UUID PRODUCT_USER_ID = UUID.fromString("423e4567-e89b-12d3-a456-426614174000");

	private CreateProductRequestDto createRequest;
	private UpdateProductRequestDto updateRequest;
	private ProductResponseDto productResponse;

	@BeforeEach
	void setUp() {
		createRequest = new CreateProductRequestDto(
			SELLER_ID, "상품", 10, "설명",
			List.of(UUID.randomUUID()), new BigDecimal("10000"), ProductStatus.ENABLE,
			"경기 성남시 분당구 판교역로 166", "카카오 판교 아지트 1층", "13529",
			new BigDecimal("37.3952000"), new BigDecimal("127.1110000"),
			CategoryType.SPORTS, RegionType.GANGNAM
		);

		updateRequest = new UpdateProductRequestDto(
			"수정상품", 20, "수정설명",
			List.of(UUID.randomUUID()), new BigDecimal("20000"), ProductStatus.DISABLE,
			"서울 강남구 테헤란로 123", "3층", "06234",
			new BigDecimal("37.1234567"), new BigDecimal("127.7654321"),
			CategoryType.SPORTS, RegionType.GANGNAM
		);

		productResponse = new ProductResponseDto(
			PRODUCT_ID,
			"판매자",
			"상품",
			10,
			"설명",
			"userId/fileId/img.jpg",
			List.of("userId/fileId/img.jpg"),
			new BigDecimal("10000"),
			"활성",
			LocalDateTime.now(),
			LocalDateTime.now(),
			"경기 성남시 분당구 판교역로 166",
			"카카오 판교 아지트 1층",
			"13529",
			new BigDecimal("37.3952000"),
			new BigDecimal("127.1110000"),
			0L,
			0L
		);
	}

	@Test
	void 상품_생성_요청이_들어오면_유스케이스를_호출한다() {
		given(productUseCase.create(createRequest, SELLER_ID)).willReturn(productResponse);

		ResponseEntity<ApiResponseDto<ProductResponseDto>> result = productRestController.create(createRequest, SELLER_ID);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(result.getBody()).isNotNull();
		assertThat(result.getBody().getData()).isEqualTo(productResponse);
		then(productUseCase).should().create(createRequest, SELLER_ID);
	}

	@Test
	void 상품_수정_요청이_들어오면_유스케이스를_호출한다() {
		given(productUseCase.update(updateRequest, PRODUCT_ID, SELLER_ID)).willReturn(productResponse);

		ResponseEntity<ApiResponseDto<ProductResponseDto>> result = productRestController.change(updateRequest, PRODUCT_ID, SELLER_ID);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody()).isNotNull();
		assertThat(result.getBody().getData()).isEqualTo(productResponse);
		then(productUseCase).should().update(updateRequest, PRODUCT_ID, SELLER_ID);
	}

	@Test
	void 상품_삭제_요청이_들어오면_유스케이스를_호출한다() {
		DeleteProductResponseDto response = DeleteProductResponseDto.from(PRODUCT_ID, ProductStatus.DISABLE);
		given(productUseCase.delete(PRODUCT_ID, SELLER_ID)).willReturn(response);

		ResponseEntity<ApiResponseDto<DeleteProductResponseDto>> result = productRestController.delete(PRODUCT_ID, SELLER_ID);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody()).isNotNull();
		assertThat(result.getBody().getData()).isEqualTo(response);
		then(productUseCase).should().delete(PRODUCT_ID, SELLER_ID);
	}

	@Test
	void 상품_목록_조회_요청이_들어오면_유스케이스를_호출한다() {
		SearchProductRequestDto request = new SearchProductRequestDto("상품", 0, 10, ProductStatus.ENABLE);
		SearchProductResponseDto response = new SearchProductResponseDto(1L, 1, 0, List.of(productResponse));
		given(productUseCase.searchAll(request)).willReturn(response);

		ResponseEntity<ApiResponseDto<SearchProductResponseDto>> result = productRestController.searchAllProduct(request);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody()).isNotNull();
		assertThat(result.getBody().getData()).isEqualTo(response);
		then(productUseCase).should().searchAll(request);
	}

	@Test
	void 상품_단건_조회_요청이_들어오면_유스케이스를_호출한다() {
		given(productUseCase.searchById(PRODUCT_ID, null)).willReturn(productResponse);

		ResponseEntity<ApiResponseDto<ProductResponseDto>> result = productRestController.searchProduct(PRODUCT_ID, null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody()).isNotNull();
		assertThat(result.getBody().getData()).isEqualTo(productResponse);
		then(productUseCase).should().searchById(PRODUCT_ID, null);
	}

	@Test
	void 일정별_예약자_조회_요청이_들어오면_유스케이스를_호출한다() {
		List<ProductUserResponseDto> response = List.of(
			new ProductUserResponseDto(
				PRODUCT_USER_ID,
				SCHEDULE_ID,
				"사용자",
				2,
				ReservationStatus.CONFIRMED.getStatusName()
			)
		);
		given(productUserUseCase.getUser(SCHEDULE_ID)).willReturn(response);

		ResponseEntity<ApiResponseDto<List<ProductUserResponseDto>>> result = productRestController.schedulesSelectUser(
			SCHEDULE_ID,
			SELLER_ID
		);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody()).isNotNull();
		assertThat(result.getBody().getData()).hasSize(1);
		then(productUserUseCase).should().getUser(SCHEDULE_ID);
	}
}
