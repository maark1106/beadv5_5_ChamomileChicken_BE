package jabaclass.product.application.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jabaclass.product.application.acl.SellerRepository;
import jabaclass.product.application.exception.BusinessException;
import jabaclass.product.application.usecase.FavoriteUseCase;
import jabaclass.product.application.usecase.ProductUseCase;
import jabaclass.product.application.usecase.ValidateFileUseCase;
import jabaclass.product.common.exception.CommonErrorCode;
import jabaclass.product.domain.model.Product;
import jabaclass.product.domain.model.ProductImageItem;
import jabaclass.product.domain.model.status.CategoryType;
import jabaclass.product.domain.model.status.ProductStatus;
import jabaclass.product.domain.model.status.RegionType;
import jabaclass.product.domain.repository.ProductRepository;
import jabaclass.product.domain.repository.ProductSearchRepository;
import jabaclass.product.infrastructure.acl.dto.response.UserResponseDto;
import jabaclass.product.infrastructure.elasticsearch.ProductDocument;
import jabaclass.product.infrastructure.event.dto.ProductAiSyncedEvent;
import jabaclass.product.infrastructure.event.dto.ProductDeletedEvent;
import jabaclass.product.infrastructure.event.dto.ProductEventResponseDto;
import jabaclass.product.infrastructure.event.dto.ProductViewedEvent;
import jabaclass.product.infrastructure.kafka.ProductEsIndexMessage;
import jabaclass.product.infrastructure.outbox.EsEventType;
import jabaclass.product.infrastructure.outbox.OutboxEvent;
import jabaclass.product.infrastructure.outbox.OutboxRepository;
import jabaclass.product.application.dto.FileConfirmResponse;
import jabaclass.product.presentation.dto.request.CreateProductRequestDto;
import jabaclass.product.presentation.dto.request.SearchProductRequestDto;
import jabaclass.product.presentation.dto.request.UpdateProductRequestDto;
import jabaclass.product.presentation.dto.response.DeleteProductResponseDto;
import jabaclass.product.presentation.dto.response.ProductResponseDto;
import jabaclass.product.presentation.dto.response.ProductSettlementItemResponseDto;
import jabaclass.product.presentation.dto.response.SearchProductResponseDto;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class ProductService implements ProductUseCase {

	private static final String VIEW_COUNT_KEY = "view_count:product:";
	private static final String LIKE_COUNT_KEY = "like_count:product:";

	private final ProductRepository productRepository;
	private final ProductSearchRepository productSearchRepository;
	private final SellerRepository sellerRepository;
	private final ApplicationEventPublisher publisher;
	private final ValidateFileUseCase validateFileUseCase;
	private final OutboxRepository outboxRepository;
	private final ObjectMapper objectMapper;
	private final StringRedisTemplate redisTemplate;
	private final FavoriteUseCase favoriteUseCase;

	@Override
	@Transactional
	public ProductResponseDto create(CreateProductRequestDto requestDto, UUID sellerId) {
		List<ProductImageItem> images = List.of();
		if (requestDto.imageIds() != null && !requestDto.imageIds().isEmpty()) {
			List<FileConfirmResponse> confirmed = requestDto.imageIds().stream()
				.map(validateFileUseCase::validateAndConfirm)
				.toList();
			images = confirmed.stream()
				.map(r -> new ProductImageItem(r.fileId(), r.storagePath()))
				.toList();
		}

		Product product = Product.builder()
			.sellerId(requestDto.sellerId())
			.title(requestDto.title())
			.maxCapacity(requestDto.maxCapacity())
			.description(requestDto.description())
			.price(requestDto.price())
			.status(requestDto.status())
			.roadAddress(requestDto.roadAddress())
			.detailAddress(requestDto.detailAddress())
			.zonecode(requestDto.zonecode())
			.latitude(requestDto.latitude())
			.longitude(requestDto.longitude())
			.category(requestDto.category())
			.region(requestDto.region())
			.build();

		product.changeImages(images);

		Product saved = productRepository.save(product);
		UserResponseDto seller = findBySellerIdOrThrow(sellerId);

		publisher.publishEvent(new ProductEventResponseDto(saved.getId()));
		publisher.publishEvent(ProductAiSyncedEvent.from(saved));
		saveEsSaveOutbox(saved, seller.name());
		return ProductResponseDto.from(saved, seller.name());
	}

	@Override
	@Transactional
	public ProductResponseDto update(UpdateProductRequestDto requestDto, UUID productId, UUID sellerId) {
		Product product = findByIdOrThrow(productId);

		matchProductAndSellerId(productId, sellerId);

		product.changeTitle(requestDto.title());
		product.changeMaxCapacity(requestDto.maxCapacity());
		product.changeDescription(requestDto.description());
		product.changePrice(requestDto.price());
		product.changeStatus(requestDto.status());
		product.changeRoadAddress(requestDto.roadAddress());
		product.changeDetailAddress(requestDto.detailAddress());
		product.changeZonecode(requestDto.zonecode());
		product.changeLatitude(requestDto.latitude());
		product.changeLongitude(requestDto.longitude());
		product.changeCategory(requestDto.category());
		product.changeRegion(requestDto.region());

		if (requestDto.imageIds() != null) {
			List<ProductImageItem> images = List.of();
			if (!requestDto.imageIds().isEmpty()) {
				List<FileConfirmResponse> confirmed = requestDto.imageIds().stream()
					.map(validateFileUseCase::validateAndConfirm)
					.toList();
				images = confirmed.stream()
					.map(r -> new ProductImageItem(r.fileId(), r.storagePath()))
					.toList();
			}
			product.changeImages(images);
		}
		UserResponseDto seller = findBySellerIdOrThrow(sellerId);
		publisher.publishEvent(ProductAiSyncedEvent.from(product));
		saveEsSaveOutbox(product, seller.name());
		return ProductResponseDto.from(product, seller.name());
	}

	@Override
	@Transactional
	public DeleteProductResponseDto delete(UUID productId, UUID sellerId) {
		Product product = findByIdOrThrow(productId);
		matchProductAndSellerId(productId, sellerId);

		product.changeStatus(ProductStatus.DISABLE);
		product.changeDelete();
		publisher.publishEvent(ProductDeletedEvent.of(productId));
		saveEsDeleteOutbox(productId.toString());

		return DeleteProductResponseDto.from(productId, ProductStatus.DISABLE);
	}

	// MySQL 최신순 조회 + Redis 카운터 조합
	@Override
	public SearchProductResponseDto searchAll(SearchProductRequestDto requestDto) {
		Pageable pageable = PageRequest.of(requestDto.thisPage(), requestDto.pageSize());

		Page<Product> page;
		if (requestDto.title() == null || requestDto.title().isBlank()) {
			page = productRepository.findByStatusAndDeleteDtIsNull(ProductStatus.ENABLE, pageable);
		} else {
			page = productRepository.findByStatusAndTitleContainingAndDeleteDtIsNull(
				ProductStatus.ENABLE, requestDto.title(), pageable);
		}

		List<UUID> productIds = page.getContent().stream().map(Product::getId).toList();
		List<UUID> sellerIds = page.getContent().stream().map(Product::getSellerId).distinct().toList();

		Map<UUID, String> sellerNameMap = sellerRepository.findSellerList(sellerIds)
			.map(list -> list.stream().collect(Collectors.toMap(UserResponseDto::userId, UserResponseDto::name)))
			.orElse(Map.of());

		Map<UUID, Long> likeCounts = batchGetCounts(productIds, LIKE_COUNT_KEY);
		Map<UUID, Long> viewCounts = batchGetCounts(productIds, VIEW_COUNT_KEY);

		List<UUID> likeMissIds = productIds.stream().filter(id -> !likeCounts.containsKey(id)).toList();
		if (!likeMissIds.isEmpty()) {
			likeCounts.putAll(favoriteUseCase.getLikeCountBatch(likeMissIds));
		}

		List<Product> viewMissProducts = page.getContent().stream()
			.filter(p -> !viewCounts.containsKey(p.getId()))
			.toList();
		if (!viewMissProducts.isEmpty()) {
			viewCounts.putAll(writeViewCountCache(viewMissProducts));
		}

		List<ProductResponseDto> content = page.getContent().stream()
			.map(p -> ProductResponseDto.from(
				p,
				sellerNameMap.getOrDefault(p.getSellerId(), ""),
				likeCounts.getOrDefault(p.getId(), 0L),
				viewCounts.getOrDefault(p.getId(), p.getViewCount())))
			.toList();

		return SearchProductResponseDto.from(page, content);
	}

	@Override
	public SearchProductResponseDto searchMy(SearchProductRequestDto requestDto, UUID sellerId) {
		Pageable pageable = PageRequest.of(requestDto.thisPage(), requestDto.pageSize());
		String sellerIdStr = sellerId.toString();

		Page<ProductDocument> page;
		if (requestDto.title() == null || requestDto.title().isBlank()) {
			page = productSearchRepository.findAllBySellerId(sellerIdStr, pageable);
		} else {
			page = productSearchRepository.searchByKeywordAndSellerId(requestDto.title(), sellerIdStr, pageable);
		}

		List<UUID> productIds = page.getContent().stream()
			.map(doc -> UUID.fromString(doc.getId()))
			.toList();

		Map<UUID, Long> likeCounts = batchGetCounts(productIds, LIKE_COUNT_KEY);
		Map<UUID, Long> viewCounts = batchGetCounts(productIds, VIEW_COUNT_KEY);

		List<ProductResponseDto> content = page.getContent().stream()
			.map(doc -> {
				UUID id = UUID.fromString(doc.getId());
				return ProductResponseDto.from(doc, likeCounts.getOrDefault(id, 0L), viewCounts.getOrDefault(id, 0L));
			})
			.toList();

		return SearchProductResponseDto.fromEs(page, content);
	}

	// 정적 데이터(DB) + 동적 데이터(Redis) 조합
	@Override
	public ProductResponseDto searchById(UUID productId, UUID userId) {
		Product product = findByIdOrThrow(productId);
		UserResponseDto seller = findBySellerIdOrThrow(product.getSellerId());

		long likeCount = favoriteUseCase.getLikeCount(productId);
		long viewCount = incrementAndGetViewCount(product);

		if (userId != null) {
			log.info("상품 조회 이벤트 발행 준비: userId={}, productId={}", userId, productId);
			publisher.publishEvent(ProductViewedEvent.of(userId, productId));
			log.info("상품 조회 이벤트 발행 완료: userId={}, productId={}", userId, productId);
		}

		return ProductResponseDto.from(product, seller.name(), likeCount, viewCount);
	}

	@Override
	public Product findByIdOrThrow(UUID productId) {
		return productRepository.findById(productId)
			.orElseThrow(() -> new BusinessException(CommonErrorCode.PRODUCT_NOT_FOUND));
	}

	@Override
	public Product matchProductAndSellerId(UUID productId, UUID sellerId) {
		return productRepository.findByIdAndSellerId(productId, sellerId)
			.orElseThrow(() -> new BusinessException(CommonErrorCode.MATCH_FAIL));
	}

	@Override
	public List<ProductSettlementItemResponseDto> getProductsByIds(List<UUID> productIds) {
		if (productIds == null || productIds.isEmpty()) {
			return List.of();
		}

		List<UUID> distinctProductIds = productIds.stream().distinct().toList();

		Map<UUID, Product> productMap = productRepository.findAllByIds(distinctProductIds).stream()
			.collect(Collectors.toMap(Product::getId, product -> product));

		return distinctProductIds.stream()
			.map(productMap::get)
			.filter(Objects::nonNull)
			.map(ProductSettlementItemResponseDto::from)
			.toList();
	}

	@Override
	public SearchProductResponseDto filterByCategoryAndRegion(CategoryType category, RegionType region, int page, int size) {
		Pageable pageable = PageRequest.of(page, size);
		Page<Product> result = productRepository.findByCategoryAndRegion(category, region, pageable);

		List<UUID> productIds = result.getContent().stream().map(Product::getId).toList();
		Map<UUID, Long> likeCounts = batchGetCounts(productIds, LIKE_COUNT_KEY);
		Map<UUID, Long> viewCounts = batchGetCounts(productIds, VIEW_COUNT_KEY);

		List<UUID> likeMissIds = productIds.stream().filter(id -> !likeCounts.containsKey(id)).toList();
		if (!likeMissIds.isEmpty()) {
			likeCounts.putAll(favoriteUseCase.getLikeCountBatch(likeMissIds));
		}

		List<Product> viewMissProducts = result.getContent().stream()
			.filter(p -> !viewCounts.containsKey(p.getId()))
			.toList();
		if (!viewMissProducts.isEmpty()) {
			viewCounts.putAll(writeViewCountCache(viewMissProducts));
		}

		List<ProductResponseDto> content = result.getContent().stream()
			.map(p -> ProductResponseDto.from(
				p,
				"",
				likeCounts.getOrDefault(p.getId(), 0L),
				viewCounts.getOrDefault(p.getId(), p.getViewCount())))
			.toList();

		return SearchProductResponseDto.from(result, content);
	}

	@Override
	public int migrateToEs() {
		final int BATCH_SIZE = 100;
		int pageNum = 0;
		int totalIndexed = 0;

		while (true) {
			Pageable pageable = PageRequest.of(pageNum, BATCH_SIZE);
			Page<Product> page = productRepository.findAllByDeleteDtIsNull(pageable);

			if (page.isEmpty()) {
				break;
			}

			List<UUID> sellerIds = page.getContent().stream()
				.map(Product::getSellerId)
				.distinct()
				.toList();

			Map<UUID, String> sellerNameMap = sellerRepository.findSellerList(sellerIds)
				.map(list -> list.stream()
					.collect(Collectors.toMap(UserResponseDto::userId, UserResponseDto::name)))
				.orElse(Map.of());

			List<ProductDocument> documents = page.getContent().stream()
				.map(p -> ProductDocument.from(p, sellerNameMap.getOrDefault(p.getSellerId(), "")))
				.toList();

			productSearchRepository.saveAll(documents);
			totalIndexed += documents.size();
			log.debug("ES 마이그레이션 진행 중: {}페이지, 누적 {}건", pageNum, totalIndexed);

			if (page.isLast()) {
				break;
			}
			pageNum++;
		}

		log.info("ES 마이그레이션 완료: 총 {}건", totalIndexed);
		return totalIndexed;
	}

	// 성능 비교용 - 상세 조회 시 DB UPDATE view_count (Redis INCR 없음)
	@Override
	@Transactional
	public ProductResponseDto searchByIdNoCache(UUID productId) {
		Product product = findByIdOrThrow(productId);
		UserResponseDto seller = findBySellerIdOrThrow(product.getSellerId());
		long likeCount = favoriteUseCase.getLikeCount(productId);

		productRepository.incrementViewCount(productId);
		long viewCount = product.getViewCount() + 1;

		return ProductResponseDto.from(product, seller.name(), likeCount, viewCount);
	}

	// 성능 비교용 - Redis 없이 DB 직접 조회
	@Override
	public SearchProductResponseDto searchAllNoCache(SearchProductRequestDto requestDto) {
		Pageable pageable = PageRequest.of(requestDto.thisPage(), requestDto.pageSize());

		Page<Product> page;
		if (requestDto.title() == null || requestDto.title().isBlank()) {
			page = productRepository.findByStatusAndDeleteDtIsNull(ProductStatus.ENABLE, pageable);
		} else {
			page = productRepository.findByStatusAndTitleContainingAndDeleteDtIsNull(
				ProductStatus.ENABLE, requestDto.title(), pageable);
		}

		List<UUID> productIds = page.getContent().stream().map(Product::getId).toList();
		List<UUID> sellerIds = page.getContent().stream().map(Product::getSellerId).distinct().toList();

		Map<UUID, String> sellerNameMap = sellerRepository.findSellerList(sellerIds)
			.map(list -> list.stream().collect(Collectors.toMap(UserResponseDto::userId, UserResponseDto::name)))
			.orElse(Map.of());

		Map<UUID, Long> likeCounts = favoriteUseCase.getLikeCountBatchNoCache(productIds);

		List<ProductResponseDto> content = page.getContent().stream()
			.map(p -> ProductResponseDto.from(
				p,
				sellerNameMap.getOrDefault(p.getSellerId(), ""),
				likeCounts.getOrDefault(p.getId(), 0L),
				p.getViewCount()))
			.toList();

		return SearchProductResponseDto.from(page, content);
	}

	// Redis view_count: 없으면 DB값으로 초기화 후 INCR
	private long incrementAndGetViewCount(Product product) {
		String key = VIEW_COUNT_KEY + product.getId();
		try {
			if (Boolean.FALSE.equals(redisTemplate.hasKey(key))) {
				redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(product.getViewCount()));
			}
			Long count = redisTemplate.opsForValue().increment(key);
			return count != null ? count : product.getViewCount();
		} catch (Exception e) {
			log.warn("Redis view_count INCR 실패 (productId={}): {}", product.getId(), e.getMessage());
			return product.getViewCount();
		}
	}

	private Map<UUID, Long> writeViewCountCache(List<Product> products) {
		Map<UUID, Long> result = products.stream()
			.collect(Collectors.toMap(Product::getId, Product::getViewCount));
		try {
			result.forEach((id, count) ->
				redisTemplate.opsForValue().setIfAbsent(VIEW_COUNT_KEY + id, String.valueOf(count)));
		} catch (Exception e) {
			log.warn("Redis view_count write-through 실패: {}", e.getMessage());
		}
		return result;
	}

	// Redis multiGet으로 카운터 배치 조회 (목록 조회 N+1 방지)
	private Map<UUID, Long> batchGetCounts(List<UUID> productIds, String keyPrefix) {
		if (productIds.isEmpty()) {
			return new HashMap<>();
		}
		List<String> keys = productIds.stream().map(id -> keyPrefix + id).toList();
		try {
			List<String> values = redisTemplate.opsForValue().multiGet(keys);
			if (values == null) {
				return new HashMap<>();
			}
			Map<UUID, Long> result = new HashMap<>();
			for (int i = 0; i < productIds.size(); i++) {
				String value = values.get(i);
				if (value != null) {
					result.put(productIds.get(i), Long.parseLong(value));
				}
			}
			return result;
		} catch (Exception e) {
			log.warn("Redis batch count 조회 실패: {}", e.getMessage());
			return new HashMap<>();
		}
	}

	private void saveEsOutbox(String aggregateId, EsEventType eventType, Object message) {
		try {
			String payload = objectMapper.writeValueAsString(message);
			outboxRepository.save(OutboxEvent.create("PRODUCT", aggregateId, eventType, payload));
		} catch (JsonProcessingException e) {
			log.error("ES outbox 직렬화 실패: type={}, id={}", eventType, aggregateId, e);
			throw new RuntimeException("ES outbox 직렬화 실패", e);
		}
	}

	private void saveEsSaveOutbox(Product product, String sellerName) {
		saveEsOutbox(product.getId().toString(), EsEventType.ES_SAVE,
			ProductEsIndexMessage.save(ProductDocument.from(product, sellerName)));
	}

	private void saveEsDeleteOutbox(String productId) {
		saveEsOutbox(productId, EsEventType.ES_DELETE, ProductEsIndexMessage.delete(productId));
	}

	private UserResponseDto findBySellerIdOrThrow(UUID sellerId) {
		return sellerRepository.findSeller(sellerId)
			.orElseThrow(() -> new BusinessException(CommonErrorCode.SELLER_NOT_FOUND));
	}
}