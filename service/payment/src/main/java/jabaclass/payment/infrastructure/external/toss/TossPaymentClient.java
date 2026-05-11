package jabaclass.payment.infrastructure.external.toss;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import jabaclass.payment.application.port.external.PaymentGatewayPort;
import lombok.RequiredArgsConstructor;

@Primary
@Component
@RequiredArgsConstructor
public class TossPaymentClient implements PaymentGatewayPort {

	private final RestTemplate restTemplate;

	@Value("${toss.secret-key}")
	private String secretKey;

	@Override
	public void confirm(String paymentKey, String orderId, int amount) {

		String url = "https://api.tosspayments.com/v1/payments/confirm";

		HttpHeaders headers = new HttpHeaders();
		headers.setBasicAuth(secretKey, "");
		headers.setContentType(MediaType.APPLICATION_JSON);

		Map<String, Object> body = Map.of(
			"paymentKey", paymentKey,
			"orderId", orderId,
			"amount", amount
		);

		HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

		try {
			restTemplate.postForEntity(url, request, String.class);
		} catch (HttpClientErrorException e) {
			// 이미 처리된 결제는 성공으로 간주 (멱등 처리)
			if (e.getResponseBodyAsString().contains("ALREADY_PROCESSED_PAYMENT")) {
				return;
			}
			throw new RuntimeException("결제 승인 실패", e);
		} catch (Exception e) {
			throw new RuntimeException("결제 승인 실패", e);
		}
	}

	@Override
	public void refund(String paymentKey, int cancelAmount, String idempotencyKey) {
		String url = "https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel";

		HttpHeaders headers = new HttpHeaders();
		headers.setBasicAuth(secretKey, "");
		headers.setContentType(MediaType.APPLICATION_JSON);
		// 동일 키로 재시도 시 Toss가 이전 결과를 그대로 반환 — 타임아웃 후 재시도 시 이중 환불 방지
		headers.set("Idempotency-Key", idempotencyKey);

		Map<String, Object> body = Map.of(
			"cancelReason", "order refund",
			"cancelAmount", cancelAmount
		);

		HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

		try {
			restTemplate.postForEntity(url, request, String.class);
		} catch (Exception e) {
			throw new RuntimeException("환불 요청 실패", e);
		}
	}
}
