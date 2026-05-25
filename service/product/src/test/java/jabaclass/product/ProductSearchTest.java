package jabaclass.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import jabaclass.product.application.acl.SellerRepository;
import jabaclass.product.application.service.ProductService;
import jabaclass.product.application.usecase.FavoriteUseCase;
import jabaclass.product.application.usecase.ValidateFileUseCase;
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
class ProductSearchTest {

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

	private static final UUID SELLER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
	private static final BigDecimal PRICE = new BigDecimal("1000.50");

	private Product product1;
	private Product product2;

	@BeforeEach
	void setUp() {
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		lenient().when(valueOperations.multiGet(any())).thenReturn(List.of());

		product1 = Product.builder()
			.sellerId(SELLER_ID).title("향수 공방 클래스").maxCapacity(10)
			.description("직접 향수를 만들어보는 클래스입니다.").price(PRICE)
			.status(ProductStatus.ENABLE).roadAddress("서울시 강남구")
			.latitude(BigDecimal.valueOf(37.5)).longitude(BigDecimal.valueOf(127.0))
			.category(CategoryType.ART).region(RegionType.GANGNAM)
			.build();
		ReflectionTestUtils.setField(product1, "id", UUID.randomUUID());

		product2 = Product.builder()
			.sellerId(SELLER_ID).title("캔들 공방 클래스").maxCapacity(5)
			.description("직접 캔들을 만들어보는 클래스입니다.").price(PRICE)
			.status(ProductStatus.ENABLE).roadAddress("서울시 강남구")
			.latitude(BigDecimal.valueOf(37.5)).longitude(BigDecimal.valueOf(127.0))
			.category(CategoryType.ART).region(RegionType.GANGNAM)
			.build();
		ReflectionTestUtils.setField(product2, "id", UUID.randomUUID());
	}

	@Test
	void 키워드가_없으면_전체_ENABLE_상품을_MySQL에서_조회한다() {
		SearchProductRequestDto request = new SearchProductRequestDto("", 0, 10, ProductStatus.ENABLE);
		Page<Product> dbPage = new PageImpl<>(List.of(product1, product2));
		given(productRepository.findByStatusAndDeleteDtIsNull(eq(ProductStatus.ENABLE), any(Pageable.class)))
			.willReturn(dbPage);
		given(sellerRepository.findSellerList(any()))
			.willReturn(Optional.of(List.of(new UserResponseDto(SELLER_ID, "판매자A", "SELLER"))));

		SearchProductResponseDto result = productService.searchAll(request);

		assertThat(result.totalCount()).isEqualTo(2);
		assertThat(result.content()).extracting(ProductResponseDto::title)
			.containsExactlyInAnyOrder("향수 공방 클래스", "캔들 공방 클래스");
		then(productRepository).should().findByStatusAndDeleteDtIsNull(any(), any());
	}

	@Test
	void 키워드가_있으면_MySQL_키워드_검색을_호출한다() {
		SearchProductRequestDto request = new SearchProductRequestDto("향수", 0, 10, ProductStatus.ENABLE);
		Page<Product> dbPage = new PageImpl<>(List.of(product1));
		given(productRepository.findByStatusAndTitleContainingAndDeleteDtIsNull(
			eq(ProductStatus.ENABLE), eq("향수"), any(Pageable.class)))
			.willReturn(dbPage);
		given(sellerRepository.findSellerList(any()))
			.willReturn(Optional.of(List.of(new UserResponseDto(SELLER_ID, "판매자A", "SELLER"))));

		SearchProductResponseDto result = productService.searchAll(request);

		assertThat(result.totalCount()).isEqualTo(1);
		assertThat(result.content()).extracting(ProductResponseDto::title)
			.containsExactly("향수 공방 클래스");
		then(productRepository).should().findByStatusAndTitleContainingAndDeleteDtIsNull(any(), any(), any());
	}

	@Test
	void 검색결과에는_sellerName이_포함된다() {
		SearchProductRequestDto request = new SearchProductRequestDto("공방", 0, 10, ProductStatus.ENABLE);
		Page<Product> dbPage = new PageImpl<>(List.of(product1, product2));
		given(productRepository.findByStatusAndTitleContainingAndDeleteDtIsNull(any(), any(), any()))
			.willReturn(dbPage);
		given(sellerRepository.findSellerList(any()))
			.willReturn(Optional.of(List.of(new UserResponseDto(SELLER_ID, "판매자A", "SELLER"))));

		SearchProductResponseDto result = productService.searchAll(request);

		assertThat(result.content()).extracting(ProductResponseDto::sellerName)
			.containsOnly("판매자A");
	}

	@Test
	void 검색_결과가_없으면_빈_리스트를_반환한다() {
		SearchProductRequestDto request = new SearchProductRequestDto("없는키워드", 0, 10, ProductStatus.ENABLE);
		given(productRepository.findByStatusAndTitleContainingAndDeleteDtIsNull(any(), any(), any()))
			.willReturn(new PageImpl<>(List.of()));
		given(sellerRepository.findSellerList(any())).willReturn(Optional.of(List.of()));

		SearchProductResponseDto result = productService.searchAll(request);

		assertThat(result.totalCount()).isZero();
		assertThat(result.content()).isEmpty();
	}

	@Test
	void 페이지_정보가_올바르게_반환된다() {
		SearchProductRequestDto request = new SearchProductRequestDto("", 2, 5, ProductStatus.ENABLE);
		Page<Product> dbPage = new PageImpl<>(
			List.of(product1),
			PageRequest.of(2, 5),
			11
		);
		given(productRepository.findByStatusAndDeleteDtIsNull(any(), any())).willReturn(dbPage);
		given(sellerRepository.findSellerList(any()))
			.willReturn(Optional.of(List.of(new UserResponseDto(SELLER_ID, "판매자A", "SELLER"))));

		SearchProductResponseDto result = productService.searchAll(request);

		assertThat(result.thisPage()).isEqualTo(2);
		assertThat(result.totalCount()).isEqualTo(11);
		assertThat(result.totalPage()).isEqualTo(3);
	}
}