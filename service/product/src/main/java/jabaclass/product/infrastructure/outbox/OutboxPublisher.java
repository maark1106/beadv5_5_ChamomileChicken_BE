package jabaclass.product.infrastructure.outbox;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

	private final OutboxService outboxService;
	private final KafkaTemplate<String, String> kafkaTemplate;

	@Scheduled(fixedDelay = 3000)
	public void publish() {
		LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
		List<OutboxEvent> events = outboxService.findAndMarkSending(threshold, 100);

		for (OutboxEvent event : events) {
			if (event.isRetryExceeded()) {
				outboxService.markFailed(event);
				log.warn("Outbox 재시도 초과. eventId={}, eventType={}", event.getId(), event.getEventType());
				continue;
			}

			try {
				ProducerRecord<String, String> record = new ProducerRecord<>(
					event.getEventType().getTopic(),
					event.getAggregateId(),
					event.getPayload()
				);
				record.headers().add(
					"eventType",
					event.getEventType().name().getBytes(StandardCharsets.UTF_8)
				);

				kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
				outboxService.markPublished(event);

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.error("Outbox 발행 중 인터럽트 발생. eventId={}", event.getId(), e);
				break;
			} catch (Exception e) {
				log.error("Outbox 발행 실패. eventId={}, eventType={}, error={}",
					event.getId(), event.getEventType(), e.getMessage());
				outboxService.retry(event);
			}
		}
	}
}
