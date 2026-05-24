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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import jabaclass.product.application.exception.BusinessException;
import jabaclass.product.application.service.FavoriteService;
import jabaclass.product.common.exception.CommonErrorCode;
import jabaclass.product.domain.model.Favorite;
import jabaclass.product.domain.model.Product;
import jabaclass.product.domain.model.status.ProductStatus;
import jabaclass.product.domain.repository.FavoriteRepository;
import jabaclass.product.domain.repository.ProductRepository;
import jabaclass.product.infrastructure.event.dto.ProductWishlistedEvent;
import jabaclass.product.presentation.dto.response.FavoritesResponseDto;

@ExtendWith(MockitoExtension.class)
class FavoriteTest {

	@InjectMocks
	private FavoriteService favoriteService;

	@Mock
	private FavoriteRepository favoriteRepository;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private ApplicationEventPublisher publisher;

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private static final UUID USER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
	private static final UUID PRODUCT_ID = UUID.fromString("423e4567-e89b-12d3-a456-426614174000");
	private static final UUID FAVORITE_ID = UUID.fromString("323e4567-e89b-12d3-a456-426614174000");

	private Favorite favorite;
	private Product product;

	@BeforeEach
	void setUp() {
		favorite = Favorite.builder()
			.productId(PRODUCT_ID)
			.userId(USER_ID)
			.build();
		ReflectionTestUtils.setField(favorite, "id", FAVORITE_ID);

		product = Product.builder()
			.sellerId(USER_ID)
			.title("테스트 상품")
			.thumbnailPath("/images/test.jpg")
			.price(BigDecimal.valueOf(35000))
			.maxCapacity(10)
			.description("테스트 설명")
			.status(ProductStatus.ENABLE)
			.roadAddress("서울시 강남구")
			.latitude(BigDecimal.valueOf(37.5))
			.longitude(BigDecimal.valueOf(127.0))
			.build();
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);

		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
	}

	@Test
	void 즐겨찾기를_생성한다() {
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
		given(favoriteRepository.findByUserIdAndProductIdAndDeleteDtIsNull(USER_ID, PRODUCT_ID)).willReturn(null);
		given(favoriteRepository.save(any(Favorite.class))).willAnswer(invocation -> {
			Favorite saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", FAVORITE_ID);
			return saved;
		});
		given(redisTemplate.hasKey(any())).willReturn(true);
		given(valueOperations.increment(any())).willReturn(1L);

		FavoritesResponseDto result = favoriteService.createFavorite(PRODUCT_ID, USER_ID);

		assertThat(result.id()).isEqualTo(FAVORITE_ID);
		assertThat(result.productId()).isEqualTo(PRODUCT_ID);
		then(publisher).should().publishEvent(any(ProductWishlistedEvent.class));
	}

	@Test
	void 이미_찜한_상품은_다시_찜할_수_없다() {
		given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
		given(favoriteRepository.findByUserIdAndProductIdAndDeleteDtIsNull(USER_ID, PRODUCT_ID)).willReturn(favorite);

		assertThatThrownBy(() -> favoriteService.createFavorite(PRODUCT_ID, USER_ID))
			.isInstanceOf(BusinessException.class)
			.hasMessage(CommonErrorCode.ALREADY_LIKED.getMessage());
	}

	@Test
	void 즐겨찾기를_삭제한다() {
		given(favoriteRepository.findByIdAndUserIdAndDeleteDtIsNull(FAVORITE_ID, USER_ID)).willReturn(favorite);
		given(redisTemplate.hasKey(any())).willReturn(true);
		given(valueOperations.decrement(any())).willReturn(0L);

		favoriteService.deleteFavorite(FAVORITE_ID, USER_ID);

		assertThat(favorite.getDeleteDt()).isNotNull();
	}

	@Test
	void 다른_사용자의_즐겨찾기는_삭제할_수_없다() {
		given(favoriteRepository.findByIdAndUserIdAndDeleteDtIsNull(FAVORITE_ID, USER_ID)).willReturn(null);

		assertThatThrownBy(() -> favoriteService.deleteFavorite(FAVORITE_ID, USER_ID))
			.isInstanceOf(BusinessException.class)
			.hasMessage(CommonErrorCode.NOT_MATCH_USER_LIKE.getMessage());
	}

	@Test
	void 찜목록_조회_성공_상품정보_포함() {
		given(favoriteRepository.findByUserIdAndDeleteDtIsNull(USER_ID)).willReturn(List.of(favorite));
		given(productRepository.findAllByIdsAndDeleteDtIsNull(List.of(PRODUCT_ID))).willReturn(List.of(product));

		List<FavoritesResponseDto> result = favoriteService.findByUserIdAndDeleteDtIsNull(USER_ID);

		assertThat(result).hasSize(1);
		FavoritesResponseDto dto = result.get(0);
		assertThat(dto.id()).isEqualTo(FAVORITE_ID);
		assertThat(dto.productId()).isEqualTo(PRODUCT_ID);
		assertThat(dto.productTitle()).isEqualTo("테스트 상품");
		assertThat(dto.thumbnailPath()).isEqualTo("/images/test.jpg");
		assertThat(dto.price()).isEqualByComparingTo(BigDecimal.valueOf(35000));
	}

	@Test
	void 찜목록_조회_상품없음_해당항목_제외() {
		given(favoriteRepository.findByUserIdAndDeleteDtIsNull(USER_ID)).willReturn(List.of(favorite));
		given(productRepository.findAllByIdsAndDeleteDtIsNull(List.of(PRODUCT_ID))).willReturn(List.of());

		List<FavoritesResponseDto> result = favoriteService.findByUserIdAndDeleteDtIsNull(USER_ID);

		assertThat(result).isEmpty();
	}
}