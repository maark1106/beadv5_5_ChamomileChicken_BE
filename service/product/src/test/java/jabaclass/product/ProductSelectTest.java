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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import jabaclass.product.application.acl.SellerRepository;
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
import jabaclass.product.presentation.dto.request.SearchProductRequestDto;
import jabaclass.product.presentation.dto.response.ProductResponseDto;
import jabaclass.product.presentation.dto.response.SearchProductResponseDto;

@ExtendWith(MockitoExtension.class)
class ProductSelectTest {

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
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private FavoriteUseCase favoriteUseCase;

	private static final BigDecimal PRICE = new BigDecimal("1000.50");
	private static final UUID SELLER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
	private static final UUID PRODUCT_ID = UUID.fromString("223e4567-e89b-12d3-a456-426614174000");

	private Product product1;

	@BeforeEach
	void setup() {
		product1 = Product.builder()
			.sellerId(SELLER_ID)
			.title("상품A")
			.maxCapacity(10)
			.description("테스트")
			.price(PRICE)
			.status(ProductStatus.ENABLE)
			.roadAddress("서울시 강남구")
			.latitude(BigDecimal.valueOf(37.5))
			.longitude(BigDecimal.valueOf(127.0))
			.category(CategoryType.SPORTS)
			.region(RegionType.GANGNAM)
			.build();
		ReflectionTestUtils.setField(product1, "id", PRODUCT_ID);

		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
	}

	@Test
	void 전체_상품을_조회한다() {
		SearchProductRequestDto request = new SearchProductRequestDto("", 0, 10, ProductStatus.ENABLE);
		Page<Product> dbPage = new PageImpl<>(List.of(product1));

		given(productRepository.findByStatusAndDeleteDtIsNull(any(ProductStatus.class), any(Pageable.class)))
			.willReturn(dbPage);
		given(sellerRepository.findSellerList(any()))
			.willReturn(Optional.of(List.of(new UserResponseDto(SELLER_ID, "판매자", "SELLER"))));
		given(valueOperations.multiGet(any())).willReturn(List.of());

		SearchProductResponseDto result = productService.searchAll(request);

		assertThat(result.content()).extracting(ProductResponseDto::title)
			.containsExactly("상품A");
		assertThat(result.totalCount()).isEqualTo(1);
		then(productRepository).should().findByStatusAndDeleteDtIsNull(any(), any());
	}

	@Test
	void 특정_상품_조회에_성공한다() {
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product1));
		given(sellerRepository.findSeller(SELLER_ID))
			.willReturn(Optional.of(new UserResponseDto(SELLER_ID, "테스트판매자", "SELLER")));
		given(favoriteUseCase.getLikeCount(PRODUCT_ID)).willReturn(0L);
		given(redisTemplate.hasKey(any())).willReturn(false);
		given(valueOperations.setIfAbsent(any(), any())).willReturn(true);
		given(valueOperations.increment(any())).willReturn(1L);

		ProductResponseDto result = productService.searchById(PRODUCT_ID, null);

		assertThat(result.title()).isEqualTo("상품A");
		then(productRepository).should().findById(PRODUCT_ID);
	}

	@Test
	void 특정_상품_조회에_실패한다() {
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> productService.searchById(PRODUCT_ID, null))
			.isInstanceOf(BusinessException.class)
			.hasMessage(CommonErrorCode.PRODUCT_NOT_FOUND.getMessage());
		then(productRepository).should().findById(PRODUCT_ID);
	}
}
