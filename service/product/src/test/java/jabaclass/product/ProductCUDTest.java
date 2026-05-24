package jabaclass.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import jabaclass.product.application.acl.SellerRepository;
import jabaclass.product.application.dto.FileConfirmResponse;
import jabaclass.product.application.exception.BusinessException;
import jabaclass.product.application.service.ProductService;
import jabaclass.product.application.usecase.FavoriteUseCase;
import jabaclass.product.application.usecase.ValidateFileUseCase;
import jabaclass.product.common.exception.CommonErrorCode;
import jabaclass.product.domain.model.Product;
import jabaclass.product.domain.model.status.CategoryType;
import jabaclass.product.domain.model.status.ProductStatus;
import jabaclass.product.domain.model.status.RegionType;
import jabaclass.product.domain.repository.ProductRepository;
import jabaclass.product.domain.repository.ProductSearchRepository;
import jabaclass.product.infrastructure.acl.dto.response.UserResponseDto;
import jabaclass.product.infrastructure.event.dto.ProductEventResponseDto;
import jabaclass.product.infrastructure.outbox.OutboxEvent;
import jabaclass.product.infrastructure.outbox.OutboxRepository;
import jabaclass.product.presentation.dto.request.CreateProductRequestDto;
import jabaclass.product.presentation.dto.request.UpdateProductRequestDto;
import jabaclass.product.presentation.dto.response.ProductResponseDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

@ExtendWith(MockitoExtension.class)
class ProductCUDTest {

	@InjectMocks
	private ProductService productService;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ProductSearchRepository productSearchRepository;

	@Mock
	private SellerRepository sellerRepository;

	@Mock
	private ApplicationEventPublisher publisher;

	@Mock
	private ValidateFileUseCase validateFileUseCase;

	@Mock
	private OutboxRepository outboxRepository;

	@Mock
	private ObjectMapper objectMapper;

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private FavoriteUseCase favoriteUseCase;

	private static final UUID SELLER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
	private static final UUID PRODUCT_ID = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");
	private static final BigDecimal PRICE = new BigDecimal("1000.50");
	private static final BigDecimal LATITUDE = new BigDecimal("37.3952000");
	private static final BigDecimal LONGITUDE = new BigDecimal("127.1110000");

	private Validator validator;
	private Product product;

	@BeforeEach
	void setUp() throws Exception {
		validator = Validation.buildDefaultValidatorFactory().getValidator();
		lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");

		product = Product.builder()
			.sellerId(SELLER_ID)
			.title("상품A")
			.maxCapacity(10)
			.description("테스트 상품")
			.price(PRICE)
			.status(ProductStatus.ENABLE)
			.roadAddress("경기 성남시 분당구 판교역로 166")
			.detailAddress("카카오 판교 아지트 1층")
			.zonecode("13529")
			.latitude(LATITUDE)
			.longitude(LONGITUDE)
			.category(CategoryType.SPORTS)
			.region(RegionType.GANGNAM)
			.build();
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
	}

	@Test
	void 상품_생성시_추가된_주소와_좌표_컬럼까지_저장한다() {
		CreateProductRequestDto request = new CreateProductRequestDto(
			SELLER_ID, "테스트상품", 5, "테스트 상품입니다.",
			List.of(UUID.randomUUID()), PRICE, ProductStatus.ENABLE,
			"경기 성남시 분당구 판교역로 166", "카카오 판교 아지트 1층", "13529",
			LATITUDE, LONGITUDE, CategoryType.SPORTS, RegionType.GANGNAM
		);
		UUID fileId = UUID.randomUUID();
		given(validateFileUseCase.validateAndConfirm(any())).willReturn(new FileConfirmResponse(fileId, "user/file/image.jpg"));
		given(sellerRepository.findSeller(SELLER_ID))
			.willReturn(Optional.of(new UserResponseDto(SELLER_ID, "테스트판매자", "SELLER")));
		given(productRepository.save(any(Product.class))).willAnswer(invocation -> {
			Product saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", PRODUCT_ID);
			return saved;
		});

		ProductResponseDto saved = productService.create(request, SELLER_ID);

		ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
		then(productRepository).should().save(captor.capture());
		Product captured = captor.getValue();

		assertThat(captured.getRoadAddress()).isEqualTo("경기 성남시 분당구 판교역로 166");
		assertThat(captured.getDetailAddress()).isEqualTo("카카오 판교 아지트 1층");
		assertThat(captured.getZonecode()).isEqualTo("13529");
		assertThat(captured.getLatitude()).isEqualByComparingTo(LATITUDE);
		assertThat(captured.getLongitude()).isEqualByComparingTo(LONGITUDE);

		assertThat(saved.id()).isEqualTo(PRODUCT_ID);
		assertThat(saved.title()).isEqualTo("테스트상품");
		assertThat(saved.thumbnailPath()).isEqualTo("user/file/image.jpg");
		assertThat(saved.roadAddress()).isEqualTo("경기 성남시 분당구 판교역로 166");
		then(publisher).should().publishEvent(any(ProductEventResponseDto.class));
		then(outboxRepository).should().save(any(OutboxEvent.class));
	}

	@Test
	void 최대인원이_0이면_상품_생성_검증에_실패한다() {
		CreateProductRequestDto request = new CreateProductRequestDto(
			SELLER_ID, "테스트상품", 0, "테스트 상품입니다.",
			List.of(UUID.randomUUID()), PRICE, ProductStatus.ENABLE,
			"경기 성남시 분당구 판교역로 166", "카카오 판교 아지트 1층", "13529",
			LATITUDE, LONGITUDE, CategoryType.SPORTS, RegionType.GANGNAM
		);

		Set<ConstraintViolation<CreateProductRequestDto>> violations = validator.validate(request);

		assertThat(violations).extracting(ConstraintViolation::getMessage)
			.contains("예약 가능 인원수를 입력해주세요.");
	}

	@Test
	void 상품명이_비어있으면_상품_생성_검증에_실패한다() {
		CreateProductRequestDto request = new CreateProductRequestDto(
			SELLER_ID, "", 10, "테스트 상품입니다.",
			List.of(UUID.randomUUID()), PRICE, ProductStatus.ENABLE,
			"경기 성남시 분당구 판교역로 166", "카카오 판교 아지트 1층", "13529",
			LATITUDE, LONGITUDE, CategoryType.SPORTS, RegionType.GANGNAM
		);

		Set<ConstraintViolation<CreateProductRequestDto>> violations = validator.validate(request);

		assertThat(violations).extracting(ConstraintViolation::getMessage)
			.contains("상품명을 입력해주세요.");
	}

	@Test
	void 판매자ID가_없으면_상품_생성_검증에_실패한다() {
		CreateProductRequestDto request = new CreateProductRequestDto(
			null, "테스트상품", 10, "테스트 상품입니다.",
			List.of(UUID.randomUUID()), PRICE, ProductStatus.ENABLE,
			"경기 성남시 분당구 판교역로 166", "카카오 판교 아지트 1층", "13529",
			LATITUDE, LONGITUDE, CategoryType.SPORTS, RegionType.GANGNAM
		);

		Set<ConstraintViolation<CreateProductRequestDto>> violations = validator.validate(request);

		assertThat(violations).extracting(ConstraintViolation::getMessage)
			.contains("판매자 Id를 입력해주세요.");
	}

	@Test
	void 가격이_0이면_상품_생성_검증에_실패한다() {
		CreateProductRequestDto request = new CreateProductRequestDto(
			SELLER_ID, "테스트상품", 10, "테스트 상품입니다.",
			List.of(UUID.randomUUID()), BigDecimal.ZERO, ProductStatus.ENABLE,
			"경기 성남시 분당구 판교역로 166", "카카오 판교 아지트 1층", "13529",
			LATITUDE, LONGITUDE, CategoryType.SPORTS, RegionType.GANGNAM
		);

		Set<ConstraintViolation<CreateProductRequestDto>> violations = validator.validate(request);

		assertThat(violations).extracting(ConstraintViolation::getMessage)
			.contains("가격은 1원 이상이어야 합니다.");
	}

	@Test
	void 판매자가_존재하지_않으면_상품_생성에_실패한다() {
		CreateProductRequestDto request = new CreateProductRequestDto(
			SELLER_ID, "테스트상품", 5, "테스트 상품입니다.",
			null, PRICE, ProductStatus.ENABLE,
			"경기 성남시 분당구 판교역로 166", "카카오 판교 아지트 1층", "13529",
			LATITUDE, LONGITUDE, CategoryType.SPORTS, RegionType.GANGNAM
		);
		given(sellerRepository.findSeller(SELLER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> productService.create(request, SELLER_ID))
			.isInstanceOf(BusinessException.class)
			.hasMessage(CommonErrorCode.SELLER_NOT_FOUND.getMessage());
	}

	@Test
	void 상품_수정시_추가된_주소와_좌표_컬럼도_변경한다() {
		UpdateProductRequestDto request = new UpdateProductRequestDto(
			"수정상품", 10, "수정 설명", List.of(UUID.randomUUID()),
			new BigDecimal("1200.00"), ProductStatus.ENABLE,
			"서울 강남구 테헤란로 123", "3층", "06234",
			new BigDecimal("37.1234567"), new BigDecimal("127.7654321"),
			CategoryType.SPORTS, RegionType.GANGNAM
		);
		UUID fileId = UUID.randomUUID();
		given(validateFileUseCase.validateAndConfirm(any())).willReturn(new FileConfirmResponse(fileId, "user/file/image.jpg"));
		given(sellerRepository.findSeller(SELLER_ID))
			.willReturn(Optional.of(new UserResponseDto(SELLER_ID, "테스트판매자", "SELLER")));
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
		given(productRepository.findByIdAndSellerId(PRODUCT_ID, SELLER_ID)).willReturn(Optional.of(product));

		ProductResponseDto updated = productService.update(request, PRODUCT_ID, SELLER_ID);

		assertThat(product.getRoadAddress()).isEqualTo("서울 강남구 테헤란로 123");
		assertThat(product.getDetailAddress()).isEqualTo("3층");
		assertThat(product.getZonecode()).isEqualTo("06234");
		assertThat(product.getLatitude()).isEqualByComparingTo("37.1234567");
		assertThat(product.getLongitude()).isEqualByComparingTo("127.7654321");

		assertThat(updated.title()).isEqualTo("수정상품");
		assertThat(updated.roadAddress()).isEqualTo("서울 강남구 테헤란로 123");
		then(outboxRepository).should().save(any(OutboxEvent.class));
	}

	@Test
	void 존재하지_않는_상품은_수정에_실패한다() {
		UpdateProductRequestDto request = new UpdateProductRequestDto(
			"수정상품", 10, "수정 설명", List.of(UUID.randomUUID()),
			new BigDecimal("1200.00"), ProductStatus.ENABLE,
			"서울 강남구 테헤란로 123", "3층", "06234",
			new BigDecimal("37.1234567"), new BigDecimal("127.7654321"),
			CategoryType.SPORTS, RegionType.GANGNAM
		);
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> productService.update(request, PRODUCT_ID, SELLER_ID))
			.isInstanceOf(BusinessException.class)
			.hasMessage(CommonErrorCode.PRODUCT_NOT_FOUND.getMessage());
	}

	@Test
	void 상품_삭제에_성공한다() {
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
		given(productRepository.findByIdAndSellerId(PRODUCT_ID, SELLER_ID)).willReturn(Optional.of(product));

		productService.delete(PRODUCT_ID, SELLER_ID);

		assertThat(product.getDeleteDt()).isNotNull();
		assertThat(product.getStatus()).isEqualTo(ProductStatus.DISABLE);
		then(outboxRepository).should().save(any(OutboxEvent.class));
	}

	@Test
	void 존재하지_않는_상품은_삭제에_실패한다() {
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> productService.delete(PRODUCT_ID, SELLER_ID))
			.isInstanceOf(BusinessException.class)
			.hasMessage(CommonErrorCode.PRODUCT_NOT_FOUND.getMessage());
	}

	@Test
	void 다른_판매자의_상품은_수정할_수_없다() {
		UpdateProductRequestDto request = new UpdateProductRequestDto(
			"수정상품", 10, "수정 설명", List.of(UUID.randomUUID()),
			new BigDecimal("1200.00"), ProductStatus.ENABLE,
			"서울 강남구 테헤란로 123", "3층", "06234",
			new BigDecimal("37.1234567"), new BigDecimal("127.7654321"),
			CategoryType.SPORTS, RegionType.GANGNAM
		);
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
		given(productRepository.findByIdAndSellerId(PRODUCT_ID, SELLER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> productService.update(request, PRODUCT_ID, SELLER_ID))
			.isInstanceOf(BusinessException.class)
			.hasMessage(CommonErrorCode.MATCH_FAIL.getMessage());
	}
}