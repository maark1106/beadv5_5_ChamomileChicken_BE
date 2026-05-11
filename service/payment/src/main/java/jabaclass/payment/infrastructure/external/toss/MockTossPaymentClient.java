package jabaclass.payment.infrastructure.external.toss;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jabaclass.payment.application.port.external.PaymentGatewayPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component("mockTossPaymentClient")
@RequiredArgsConstructor
public class MockTossPaymentClient implements PaymentGatewayPort {

	// Toss Payments API 실측 기준: P50=230ms, P95=440ms, 범위 100~500ms
	private static final int MIN_DELAY_MS = 100;
	private static final int MAX_DELAY_MS = 500;

	private final ObservationRegistry observationRegistry;

	@Override
	public void confirm(String paymentKey, String orderId, int amount) {
		Observation.createNotStarted("toss.pg.confirm", observationRegistry)
			.lowCardinalityKeyValue("pg", "toss")
			.observe(() -> {
				long delay = networkDelay();
				log.info("[MockToss] confirm 완료. orderId={}, amount={}, delay={}ms", orderId, amount, delay);
			});
	}

	@Override
	public void refund(String paymentKey, int cancelAmount, String idempotencyKey) {
		Observation.createNotStarted("toss.pg.refund", observationRegistry)
			.lowCardinalityKeyValue("pg", "toss")
			.observe(() -> {
				long delay = networkDelay();
				log.info("[MockToss] refund 완료. cancelAmount={}, delay={}ms", cancelAmount, delay);
			});
	}

	private long networkDelay() {
		long delay = ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1);
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return delay;
	}
}