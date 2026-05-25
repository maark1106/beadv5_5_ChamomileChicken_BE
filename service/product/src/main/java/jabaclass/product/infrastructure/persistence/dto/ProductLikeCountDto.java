package jabaclass.product.infrastructure.persistence.dto;

import java.util.UUID;

public record ProductLikeCountDto(UUID productId, long count) {
}