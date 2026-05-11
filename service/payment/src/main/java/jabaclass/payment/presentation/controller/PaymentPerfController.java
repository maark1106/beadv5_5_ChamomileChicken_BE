package jabaclass.payment.presentation.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jabaclass.payment.application.service.PaymentPerfSimService;
import jabaclass.payment.common.auth.CurrentUser;
import jabaclass.payment.common.dto.ApiResponseDto;
import jabaclass.payment.presentation.dto.request.ConfirmPaymentRequestDto;
import jabaclass.payment.presentation.dto.response.PaymentResponseDto;
import lombok.RequiredArgsConstructor;

/**
 * 성능 테스트 전용 컨트롤러
 *
 * [실제 결제 흐름 기반 — setup()에서 주문+결제 사전 생성 필요]
 * POST /perf/confirm/before  — @Transactional이 Mock PG 호출 포함, 커넥션 ~300ms 점유
 * POST /perf/confirm/after   — 트랜잭션 분리, PG 호출 중 커넥션 미점유
 *
 * [순수 커넥션 풀 시뮬레이션 — DB 레코드 불필요]
 * POST /perf/sim/before  — 커넥션 획득 후 300ms 점유
 * POST /perf/sim/after   — 300ms 대기 후 커넥션 잠깐 사용
 */
@RestController
@RequestMapping("/api/v1/payments/perf")
@RequiredArgsConstructor
public class PaymentPerfController {

	private final PaymentPerfSimService paymentPerfSimService;

	@PostMapping("/confirm/before")
	public ResponseEntity<ApiResponseDto<PaymentResponseDto>> confirmBefore(
		@RequestBody ConfirmPaymentRequestDto request,
		@CurrentUser UUID userId) {
		PaymentResponseDto response = paymentPerfSimService.confirmPerfBefore(userId, request);
		return ResponseEntity.ok(ApiResponseDto.success(HttpStatus.OK, "perf confirm before", response));
	}

	@PostMapping("/confirm/after")
	public ResponseEntity<ApiResponseDto<PaymentResponseDto>> confirmAfter(
		@RequestBody ConfirmPaymentRequestDto request,
		@CurrentUser UUID userId) {
		PaymentResponseDto response = paymentPerfSimService.confirmPerfAfter(userId, request);
		return ResponseEntity.ok(ApiResponseDto.success(HttpStatus.OK, "perf confirm after", response));
	}

}