package jabaclass.payment.application.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jabaclass.payment.application.port.external.OrderPort;
import jabaclass.payment.application.port.external.PaymentGatewayPort;
import jabaclass.payment.application.usecase.PaymentSettlementQueryUseCase;
import jabaclass.payment.application.usecase.PaymentUseCase;
import jabaclass.payment.common.error.PaymentErrorCode;
import jabaclass.payment.common.error.PaymentException;
import jabaclass.payment.domain.model.Payment;
import jabaclass.payment.domain.repository.PaymentRepository;
import jabaclass.payment.domain.repository.RefundRepository;
import jabaclass.payment.application.service.handler.PaymentConfirmHandler;
import jabaclass.payment.application.service.handler.PaymentRefundHandler;
import jabaclass.payment.infrastructure.kafka.PaymentCompletedEvent;
import jabaclass.payment.infrastructure.outbox.EventType;
import jabaclass.payment.infrastructure.outbox.OutboxEvent;
import jabaclass.payment.infrastructure.outbox.OutboxRepository;
import jabaclass.payment.presentation.dto.request.ConfirmPaymentRequestDto;
import jabaclass.payment.presentation.dto.request.InternalRefundRequestDto;
import jabaclass.payment.presentation.dto.request.PreparePaymentRequestDto;
import jabaclass.payment.presentation.dto.response.InternalRefundDetailResponseDto;
import jabaclass.payment.presentation.dto.response.InternalRefundResponseDto;
import jabaclass.payment.presentation.dto.response.PaymentResponseDto;
import jabaclass.payment.presentation.dto.response.PaymentSettlementSliceResponseDto;
import jabaclass.payment.presentation.dto.response.PaymentSettlementTargetItemResponseDto;
import jabaclass.payment.presentation.dto.response.RefundSettlementSliceResponseDto;
import jabaclass.payment.presentation.dto.response.RefundSettlementTargetItemResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService implements PaymentUseCase, PaymentSettlementQueryUseCase {

	private final PaymentRepository paymentRepository;
	private final RefundRepository refundRepository;
	private final PaymentGatewayPort paymentGatewayPort;
	private final OrderPort orderPort;
	private final OutboxRepository outboxRepository;
	private final PaymentConfirmHandler paymentConfirmHandler;
	private final PaymentRefundHandler paymentRefundHandler;
	private final ObjectMapper objectMapper;

	@Override
	@Transactional
	public PaymentResponseDto create(UUID userId, PreparePaymentRequestDto request) {
		Optional<Payment> existingPayment = paymentRepository.findReusableByOrderId(request.orderId());
		if (existingPayment.isPresent()) {
			Payment reusablePayment = existingPayment.get();
			if (!reusablePayment.getUserId().equals(userId)) {
				throw new PaymentException(PaymentErrorCode.UNAUTHORIZED_PAYMENT_ACCESS);
			}
			return PaymentResponseDto.from(reusablePayment);
		}

		Payment payment = Payment.create(
			userId,
			request.productId(),
			request.orderId(),
			request.paymentMethod(),
			request.paymentAmount(),
			request.depositAmount()
		);

		// 예치금 100% 결제 → PG 호출 없이 즉시 완료, Outbox에 PAYMENT_COMPLETED 저장
		// Payment 저장 + Outbox 저장이 같은 트랜잭션 → 원자성 보장
		if (payment.getPaymentAmount().compareTo(BigDecimal.ZERO) == 0) {
			payment.markDone("DEPOSIT_ONLY");
		}

		Payment savedPayment = paymentRepository.save(payment);

		if (savedPayment.getPaymentAmount().compareTo(BigDecimal.ZERO) == 0) {
			outboxRepository.save(OutboxEvent.create(
				"PAYMENT",
				savedPayment.getId().toString(),
				EventType.PAYMENT_COMPLETED,
				toJson(new PaymentCompletedEvent(
					UUID.randomUUID(),
					savedPayment.getId(),
					savedPayment.getOrderId(),
					savedPayment.getProductId(),
					savedPayment.getTotalAmount(),
					savedPayment.getPaidAt()
				))
			));
		}

		return PaymentResponseDto.from(savedPayment);
	}

	// [오케스트레이터] @Transactional 없음 — PG 응답 대기 중 DB 커넥션을 점유하지 않기 위해 트랜잭션 분리
	// PG 호출 결과가 확정된 뒤에만 핸들러(@Transactional)에서 짧은 로컬 트랜잭션으로 DB 상태 변경
	public PaymentResponseDto confirm(UUID userId, ConfirmPaymentRequestDto request) {

		Payment payment = paymentRepository.findByOrderId(request.orderId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		if (!payment.getUserId().equals(userId)) {
			throw new PaymentException(PaymentErrorCode.UNAUTHORIZED_PAYMENT_ACCESS);
		}

		// 이미 결제 완료된 경우 멱등 처리 — 중복 confirm 요청 방어
		if (payment.isDone()) {
			return PaymentResponseDto.from(payment);
		}

		boolean valid = orderPort.validateOrder(payment.getOrderId(), payment.getTotalAmount().intValue());
		if (!valid) {
			throw new PaymentException(PaymentErrorCode.INVALID_ORDER_AMOUNT);
		}

		if (payment.getPaymentAmount().compareTo(BigDecimal.valueOf(request.amount())) != 0) {
			throw new PaymentException(PaymentErrorCode.INVALID_PAYMENT_AMOUNT);
		}

		try {
			// PG 승인 요청 — 이 시점부터 외부 상태 변경 발생
			paymentGatewayPort.confirm(request.paymentKey(), payment.getOrderId().toString(), request.amount());
			// 성공: Payment PAID + Outbox PAYMENT_COMPLETED 저장 (같은 트랜잭션)
			return paymentConfirmHandler.onSuccess(payment.getId(), request.paymentKey());
		} catch (Exception e) {
			// 실패: Payment FAILED + Outbox PAYMENT_FAILED 저장 (같은 트랜잭션)
			// PG 성공 후 DB 저장 실패 케이스는 웹훅/정합성 배치로 별도 복구
			paymentConfirmHandler.onFailure(payment.getId(), payment.getOrderId(), payment.getDepositAmount());
			throw new PaymentException(PaymentErrorCode.PAYMENT_CONFIRM_FAILED, e);
		}
	}

	// [성능 테스트 Before] @Transactional로 PG 호출 포함 전체 감쌈 → DB 커넥션을 PG 응답 대기 중에도 점유
	// gateway를 주입받아 MockTossPaymentClient 사용 가능 — perf 프로파일 없이도 실 Toss와 동시 운영 가능
	@Transactional
	public PaymentResponseDto confirmBefore(UUID userId, ConfirmPaymentRequestDto request, PaymentGatewayPort gateway) {
		Payment payment = paymentRepository.findByOrderId(request.orderId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		if (!payment.getUserId().equals(userId)) {
			throw new PaymentException(PaymentErrorCode.UNAUTHORIZED_PAYMENT_ACCESS);
		}

		if (payment.isDone()) {
			return PaymentResponseDto.from(payment);
		}

		boolean valid = orderPort.validateOrder(payment.getOrderId(), payment.getTotalAmount().intValue());
		if (!valid) {
			throw new PaymentException(PaymentErrorCode.INVALID_ORDER_AMOUNT);
		}

		if (payment.getPaymentAmount().compareTo(BigDecimal.valueOf(request.amount())) != 0) {
			throw new PaymentException(PaymentErrorCode.INVALID_PAYMENT_AMOUNT);
		}

		try {
			gateway.confirm(request.paymentKey(), payment.getOrderId().toString(), request.amount());
			return paymentConfirmHandler.onSuccess(payment.getId(), request.paymentKey());
		} catch (Exception e) {
			paymentConfirmHandler.onFailure(payment.getId(), payment.getOrderId(), payment.getDepositAmount());
			throw new PaymentException(PaymentErrorCode.PAYMENT_CONFIRM_FAILED, e);
		}
	}

	// [성능 테스트 After] @Transactional 없음 — PG 호출 대기 중 커넥션 미점유
	// confirm()과 동일한 구조지만 gateway를 주입받아 MockTossPaymentClient 사용 가능
	public PaymentResponseDto confirmAfterWithMock(UUID userId, ConfirmPaymentRequestDto request, PaymentGatewayPort gateway) {
		Payment payment = paymentRepository.findByOrderId(request.orderId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		if (!payment.getUserId().equals(userId)) {
			throw new PaymentException(PaymentErrorCode.UNAUTHORIZED_PAYMENT_ACCESS);
		}

		if (payment.isDone()) {
			return PaymentResponseDto.from(payment);
		}

		boolean valid = orderPort.validateOrder(payment.getOrderId(), payment.getTotalAmount().intValue());
		if (!valid) {
			throw new PaymentException(PaymentErrorCode.INVALID_ORDER_AMOUNT);
		}

		if (payment.getPaymentAmount().compareTo(BigDecimal.valueOf(request.amount())) != 0) {
			throw new PaymentException(PaymentErrorCode.INVALID_PAYMENT_AMOUNT);
		}

		try {
			gateway.confirm(request.paymentKey(), payment.getOrderId().toString(), request.amount());
			return paymentConfirmHandler.onSuccess(payment.getId(), request.paymentKey());
		} catch (Exception e) {
			paymentConfirmHandler.onFailure(payment.getId(), payment.getOrderId(), payment.getDepositAmount());
			throw new PaymentException(PaymentErrorCode.PAYMENT_CONFIRM_FAILED, e);
		}
	}

	// [오케스트레이터] Order 서비스의 동기 HTTP 호출로 진입 — @Transactional 없음
	// PG 환불 실패 시 예외를 Order로 전파 → Order가 PAID 상태 유지 (보상 불필요)
	// PG 성공 후 DB 저장 실패는 알려진 한계 — 웹훅/정합성 배치로 별도 복구
	@Override
	public InternalRefundResponseDto refundByOrder(InternalRefundRequestDto request) {
		Payment payment = paymentRepository.findByOrderId(request.orderId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		if (!payment.isDone()) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_COMPLETED);
		}

		try {
			// 예치금 100% 결제는 PG 환불 스킵 (카드 청구 없음)
			BigDecimal paymentRefundAmount = payment.getPaymentAmount().multiply(request.refundRate());
			if (paymentRefundAmount.signum() > 0) {
				// orderId를 멱등키로 전달 — 타임아웃 후 재시도 시 Toss가 이전 결과 반환, 이중 환불 방지
				paymentGatewayPort.refund(payment.getPaymentKey(), paymentRefundAmount.intValue(), request.orderId().toString());
			}
			// PG 성공 확정 후 → Payment CANCELLED + Refund 생성 (같은 트랜잭션)
			return paymentRefundHandler.onSuccess(payment.getId(), request.refundRate());
		} catch (PaymentException e) {
			throw e;
		} catch (Exception e) {
			throw new PaymentException(PaymentErrorCode.PAYMENT_REFUND_FAILED, e);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public InternalRefundDetailResponseDto getRefundInfoByOrder(UUID orderId) {
		Payment payment = paymentRepository.findByOrderId(orderId)
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		return refundRepository.findLatestCompletedByPaymentId(payment.getId())
			.map(refund -> InternalRefundDetailResponseDto.from(orderId, refund))
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}

	// Outbox payload 직렬화
	// - 이벤트 객체 → JSON 문자열로 변환
	// - Kafka 전송 시 payload로 사용됨
	private String toJson(Object obj) {
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (Exception e) {
			throw new RuntimeException("Outbox 직렬화 실패", e);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public PaymentSettlementSliceResponseDto getPaymentSettlementTargets(
		LocalDateTime from,
		LocalDateTime to,
		LocalDateTime cursorUpdatedAt,
		UUID cursorId,
		int size
	) {
		List<PaymentRepository.SettlementPaymentSource> sources = paymentRepository.findSettlementPaymentSources(
			from,
			to,
			cursorUpdatedAt,
			cursorId,
			size + 1
		);

		boolean hasNext = sources.size() > size;
		List<PaymentRepository.SettlementPaymentSource> page = hasNext ? sources.subList(0, size) : sources;

		LocalDateTime nextCursorUpdatedAt = hasNext ? page.get(page.size() - 1).occurredAt() : null;
		UUID nextCursorId = hasNext ? page.get(page.size() - 1).paymentId() : null;

		return new PaymentSettlementSliceResponseDto(
			page.stream()
				.map(item -> new PaymentSettlementTargetItemResponseDto(
					item.paymentId(),
					item.orderId(),
					item.productId(),
					item.paymentStatus(),
					item.totalPaymentAmount(),
					item.occurredAt(),
					item.occurredAt()
				))
				.toList(),
			hasNext,
			nextCursorUpdatedAt,
			nextCursorId
		);
	}

	@Override
	@Transactional(readOnly = true)
	public RefundSettlementSliceResponseDto getRefundSettlementTargets(
		LocalDateTime from,
		LocalDateTime to,
		LocalDateTime cursorUpdatedAt,
		UUID cursorId,
		int size
	) {
		List<RefundRepository.SettlementRefundSource> sources = refundRepository.findSettlementRefundSources(
			from,
			to,
			cursorUpdatedAt,
			cursorId,
			size + 1
		);

		boolean hasNext = sources.size() > size;
		List<RefundRepository.SettlementRefundSource> page = hasNext ? sources.subList(0, size) : sources;

		LocalDateTime nextCursorUpdatedAt = hasNext ? page.get(page.size() - 1).occurredAt() : null;
		UUID nextCursorId = hasNext ? page.get(page.size() - 1).refundId() : null;

		return new RefundSettlementSliceResponseDto(
			page.stream()
				.map(item -> new RefundSettlementTargetItemResponseDto(
					item.refundId(),
					item.paymentId(),
					item.orderId(),
					item.productId(),
					item.refundStatus(),
					item.totalRefundAmount(),
					item.occurredAt(),
					item.occurredAt()
				))
				.toList(),
			hasNext,
			nextCursorUpdatedAt,
			nextCursorId
		);
	}
}
