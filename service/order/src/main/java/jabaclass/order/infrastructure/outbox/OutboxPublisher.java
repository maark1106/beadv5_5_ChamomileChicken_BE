package jabaclass.order.infrastructure.outbox;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

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

	private final OutboxRepository outboxRepository;
	private final OutboxService outboxService;
	private final KafkaTemplate<String, String> kafkaTemplate;

	@Scheduled(fixedDelay = 3000)
	public void publish() {
		LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
		List<OutboxEvent> events =
			outboxRepository.findProcessableEvents(threshold, 100);

		outboxService.markSending(events);

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

				kafkaTemplate.send(record).get();
				outboxService.markPublished(event);
				log.info("[Outbox] 발행 완료. eventType={}, aggregateId={}", event.getEventType(), event.getAggregateId());

			} catch (Exception e) {
				log.error("[Outbox] 발행 실패. eventId={}, eventType={}, error={}",
					event.getId(), event.getEventType(), e.getMessage());
				outboxService.retry(event);
			}
		}
	}
}
