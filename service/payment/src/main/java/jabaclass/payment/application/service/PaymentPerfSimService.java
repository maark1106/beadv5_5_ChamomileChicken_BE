package jabaclass.payment.application.service;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jabaclass.payment.application.port.external.OrderPort;
import jabaclass.payment.application.port.external.PaymentGatewayPort;
import jabaclass.payment.common.error.PaymentErrorCode;
import jabaclass.payment.common.error.PaymentException;
import jabaclass.payment.domain.model.Payment;
import jabaclass.payment.domain.repository.PaymentRepository;
import jabaclass.payment.presentation.dto.request.ConfirmPaymentRequestDto;
import jabaclass.payment.presentation.dto.response.PaymentResponseDto;

@Service
public class PaymentPerfSimService {

	private final JdbcTemplate jdbcTemplate;
	private final PaymentRepository paymentRepository;
	private final PaymentGatewayPort mockGateway;
	private final OrderPort orderPort;

	public PaymentPerfSimService(
		JdbcTemplate jdbcTemplate,
		PaymentRepository paymentRepository,
		@Qualifier("mockTossPaymentClient") PaymentGatewayPort mockGateway,
		OrderPort orderPort
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.paymentRepository = paymentRepository;
		this.mockGateway = mockGateway;
		this.orderPort = orderPort;
	}

	// Before: @Transactional이 order 검증 + Mock PG 호출 전체를 감쌈 → 커넥션 ~300ms 점유
	@Transactional
	public PaymentResponseDto confirmPerfBefore(UUID userId, ConfirmPaymentRequestDto request) {
		Payment payment = paymentRepository.findByOrderId(request.orderId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		if (!payment.getUserId().equals(userId)) {
			throw new PaymentException(PaymentErrorCode.UNAUTHORIZED_PAYMENT_ACCESS);
		}

		orderPort.validateOrder(request.orderId(), request.amount());
		mockGateway.confirm(request.paymentKey(), payment.getOrderId().toString(), request.amount());

		return PaymentResponseDto.from(payment);
	}

	// After: @Transactional 없음 → order 검증/PG 호출 중 커넥션 미점유
	public PaymentResponseDto confirmPerfAfter(UUID userId, ConfirmPaymentRequestDto request) {
		Payment payment = paymentRepository.findByOrderId(request.orderId())
			.orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_NOT_FOUND));

		if (!payment.getUserId().equals(userId)) {
			throw new PaymentException(PaymentErrorCode.UNAUTHORIZED_PAYMENT_ACCESS);
		}

		orderPort.validateOrder(request.orderId(), request.amount());
		mockGateway.confirm(request.paymentKey(), payment.getOrderId().toString(), request.amount());

		jdbcTemplate.queryForObject("SELECT 1", Integer.class);

		return PaymentResponseDto.from(payment);
	}
}