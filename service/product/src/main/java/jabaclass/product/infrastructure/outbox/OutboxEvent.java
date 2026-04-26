package jabaclass.product.infrastructure.outbox;

import java.time.LocalDateTime;

import jabaclass.product.domain.model.EntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "product_outbox_events")
@Getter
public class OutboxEvent extends EntityBase {

	@Column(nullable = false)
	private String aggregateType;

	@Column(nullable = false)
	private String aggregateId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private EsEventType eventType;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OutboxStatus status;

	@Column(nullable = false)
	private int retryCount;

	private LocalDateTime lastAttemptAt;

	protected OutboxEvent() {}

	public static OutboxEvent create(
		String aggregateType,
		String aggregateId,
		EsEventType eventType,
		String payload
	) {
		OutboxEvent event = new OutboxEvent();
		event.aggregateType = aggregateType;
		event.aggregateId = aggregateId;
		event.eventType = eventType;
		event.payload = payload;
		event.status = OutboxStatus.PENDING;
		event.retryCount = 0;
		return event;
	}

	public void markSending() {
		this.status = OutboxStatus.SENDING;
		this.lastAttemptAt = LocalDateTime.now();
	}

	public void markPending() {
		this.status = OutboxStatus.PENDING;
	}

	public void markPublished() {
		this.status = OutboxStatus.PUBLISHED;
	}

	public void markFailed() {
		this.status = OutboxStatus.FAILED;
	}

	public void increaseRetry() {
		this.retryCount++;
		this.lastAttemptAt = LocalDateTime.now();
	}

	public boolean isRetryExceeded() {
		return this.retryCount >= 5;
	}
}
