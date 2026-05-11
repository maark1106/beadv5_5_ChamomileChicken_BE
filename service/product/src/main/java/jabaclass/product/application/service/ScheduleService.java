package jabaclass.product.application.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jabaclass.product.application.acl.SellerRepository;
import jabaclass.product.application.exception.BusinessException;
import jabaclass.product.application.usecase.ProductUseCase;
import jabaclass.product.application.usecase.ProductUserUseCase;
import jabaclass.product.application.usecase.ScheduleUseCase;
import jabaclass.product.common.exception.CommonErrorCode;
import jabaclass.product.domain.model.Product;
import jabaclass.product.domain.model.ProductUser;
import jabaclass.product.domain.model.Schedule;
import jabaclass.product.domain.model.status.ReservationStatus;
import jabaclass.product.domain.model.status.ReservedStatus;
import jabaclass.product.domain.repository.ScheduleRepository;
import jabaclass.product.infrastructure.acl.dto.response.UserResponseDto;
import jabaclass.product.presentation.dto.request.CreateProductUserRequestDto;
import jabaclass.product.presentation.dto.request.CreateScheduleRequestDto;
import jabaclass.product.presentation.dto.request.OrderRequestDto;
import jabaclass.product.presentation.dto.request.UpdateScheduleRequestDto;
import jabaclass.product.presentation.dto.response.AvailabilityScheduleResponseDto;
import jabaclass.product.presentation.dto.response.DeleteScheduleResponseDto;
import jabaclass.product.presentation.dto.response.OrderResponseDto;
import jabaclass.product.presentation.dto.response.OrderValid;
import jabaclass.product.presentation.dto.response.ProductUserResponseDto;
import jabaclass.product.presentation.dto.response.SchedulesResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class ScheduleService implements ScheduleUseCase {

	private final ScheduleRepository scheduleRepository;
	private final ProductUseCase productUseCase;
	private final SellerRepository sellerRepository;
	private final ProductUserUseCase productUserUseCase;

	private static final int BATCH_SIZE = 100;

	@Override
	@Transactional
	public SchedulesResponseDto create(CreateScheduleRequestDto requestDto, UUID productId, UUID sellerId) {
		// 상품 존재하는지 확인
		Product product = productUseCase.findByIdOrThrow(productId);
		// 본인 상품인지 확인
		productUseCase.matchProductAndSellerId(productId, sellerId);

		// 날짜/시간 형태
		Schedule schedule = new Schedule();
		LocalDate date = schedule.fDt(requestDto.scheduleDt());
		LocalTime startTime = schedule.fTime(requestDto.startTime());
		LocalTime endTime = schedule.fTime(requestDto.endTime());

		// 날짜 검증 추가
		validatePastDate(date);
		// 시간 검증
		validateTime(startTime, endTime);

		// 중복 검증
		// 동시에 같은 날짜 시간대를 저장할경우를 대비해 lock(비관적 lock)
		List<Schedule> conflicts = scheduleRepository.findConflictSchedules(productId, date, startTime, endTime);

		if (!conflicts.isEmpty()) {
			throw new BusinessException(CommonErrorCode.SCHEDULE_CONFLICT);
		}

		Schedule save = Schedule.builder()
			.productId(productId)
			.scheduleDt(date)
			.startTime(startTime)
			.endTime(endTime)
			.status(requestDto.status())
			.capacity(product.getMaxCapacity())
			.build();

		Schedule saved = scheduleRepository.save(save);

		return SchedulesResponseDto.from(saved);
	}

	@Override
	@Transactional
	public DeleteScheduleResponseDto delete(UUID productId, UUID scheduleId, UUID sellerId) {
		// 상품 존재하는지 확인
		Product product = productUseCase.findByIdOrThrow(productId);
		// 상품 일자가 존재하는지
		Schedule schedule = findByIdOrThrow(scheduleId);
		// 본인 상품인지 확인
		productUseCase.matchProductAndSellerId(product.getId(), sellerId);

		if (!schedule.getProductId().equals(product.getId())) {
			throw new BusinessException(CommonErrorCode.MATCH_FAIL);
		}

		schedule.changeStatus(ReservedStatus.CLOSED);
		schedule.changeDelete();

		return DeleteScheduleResponseDto.from(scheduleId, ReservedStatus.CLOSED);
	}

	@Override
	@Transactional
	public SchedulesResponseDto update(UpdateScheduleRequestDto requestDto, UUID productId, UUID scheduleId,
		UUID sellerId) {
		// 상품 존재하는지 확인
		productUseCase.findByIdOrThrow(productId);
		// 상품 일자 데이터가 존재하는지 확인
		Schedule schedule = findByIdOrThrow(scheduleId);
		// 본인 상품인지 확인
		productUseCase.matchProductAndSellerId(productId, sellerId);
		// 시간 형태
		LocalTime startTime = schedule.fTime(requestDto.startTime());
		LocalTime endTime = schedule.fTime(requestDto.endTime());

		// 시간 검증
		validateTime(startTime, endTime);

		// 중복 검증
		// 동시에 같은 날짜 시간대를 저장할경우를 대비해 lock(비관적 lock)
		List<Schedule> conflicts = scheduleRepository.findConflictSchedulesNoId(productId, schedule.getScheduleDt(),
			startTime, endTime, scheduleId);

		if (!conflicts.isEmpty()) {
			throw new BusinessException(CommonErrorCode.SCHEDULE_CONFLICT);
		}

		// 업데이트
		schedule.changeStartTime(startTime);
		schedule.changeEndTime(endTime);
		schedule.changeStatus(requestDto.status());
		schedule.changeCapacity(requestDto.maxCapacity());

		return SchedulesResponseDto.from(schedule);
	}

	@Override
	public List<SchedulesResponseDto> schedulesList(UUID productId) {
		List<Schedule> list = scheduleRepository.findByProductIdAndDeleteDtIsNull(productId);

		return list.stream()
			.map(SchedulesResponseDto::from)
			.toList();
	}

	// 상품 검증 -> 예약 가능 상태 return => api 호출
	// 2026-04-09 수정
	@Override
	@Transactional
	public OrderResponseDto verification(OrderRequestDto requestDto) {
		// 스케줄 검색
		Schedule schedule = findByIdOrThrow(requestDto.productScheduleId());
		// 상품 검색
		Product product = productUseCase.findByIdOrThrow(schedule.getProductId());

		// 가격 검증 => 실패의 경우
		if (product.getPrice().compareTo(requestDto.price()) != 0) {
			return OrderResponseDto.from(product, requestDto.quantity(), OrderValid.PRICE_MISMATCH, null);
		}

		// 스케줄에 예약 가능 인원과 받아온 인원에 대한 체크
		ProductUserResponseDto saved = null;

		// 예약 가능 인원수
		// 예약 검증 및 재고 차감/재고가 0일 경우 상태값 변경
		int count = scheduleRepository.verification(requestDto.quantity(), schedule.getId());

		// count 0일경우 실패, 1일경우 성공
		if (count == 0) { // 실패
			return OrderResponseDto.from(product, requestDto.quantity(), OrderValid.OUT_OF_STOCK, null);
		} else { // 성공
			CreateProductUserRequestDto dto = new CreateProductUserRequestDto(
				requestDto.productScheduleId(),
				requestDto.userId(),
				requestDto.quantity(),
				ReservationStatus.RESERVED
			);
			saved = productUserUseCase.create(dto);
		}

		UUID puserId = saved == null ? null : saved.id();

		return OrderResponseDto.from(product, requestDto.quantity(), OrderValid.OK, puserId);
	}

	// 2026-04-09 결제 성공 시 상태값 수정 => kafka
	@Override
	@Transactional
	public OrderValid reservationCompleted(UUID productUserId) {
		// 예약자 존재 여부 확인 => 없을 경우 예외
		ProductUser user = productUserUseCase.innerFindById(productUserId);
		// 스게줄 존재 확인 => 없을 경우 예외
		findByIdOrThrow(user.getProductScheduleId());

		int count = scheduleRepository.updateStatus(user.getId(), ReservationStatus.CONFIRMED,
			List.of(ReservationStatus.RESERVED));
		if (count == 0)
			return OrderValid.MODI_FAIL;
		else
			return OrderValid.OK;
	}

	@Override
	public SchedulesResponseDto selectSchedules(UUID productUserId) {
		ProductUser user = productUserUseCase.innerFindById(productUserId);
		Schedule schedule = findByIdOrThrow(user.getProductScheduleId());
		return SchedulesResponseDto.from(schedule);
	}

	@Override
	@Transactional
	public int closeExpiredSchedulesOnce() {
		LocalDate today = LocalDate.now();
		Pageable pageable = PageRequest.of(0, BATCH_SIZE);
		List<UUID> ids = scheduleRepository.findClosableIds(
			today,
			ReservedStatus.CLOSED,
			pageable
		);

		if (ids.isEmpty()) {
			return 0;
		}

		return scheduleRepository.bulkClose(
			ids,
			List.of(ReservedStatus.AVAILABLE, ReservedStatus.FULL),
			ReservedStatus.CLOSED
		);
	}

	// 2026-04-09 결제 실패/취소/환불 시 재고 복구  및 상태값 변경 => kafka
	@Override
	@Transactional
	public OrderValid restoringInventory(UUID productUserId, ReservationStatus orderStatus) {

		// 예약자 존재 여부 확인 및 재고 적용 상태로값 수정
		// 0일경우 재고 복구 x, 1일 경우 복구
		// 예약 대기 or 예약 확정일 경우에만 진행
		int claim = scheduleRepository.claimRestore(productUserId);

		// 복구 가능한 예약자가 없으면 실패
		// 복구 중일 경우 실패
		if (claim == 0) {
			return OrderValid.MODI_FAIL;
		}

		ProductUser user = productUserUseCase.innerFindById(productUserId);

		// 스게줄 존재 확인 => 없을 경우 예외
		Schedule schedule = findByIdOrThrow(user.getProductScheduleId());
		Product product = productUseCase.findByIdOrThrow(schedule.getProductId());

		// 복구는 예약 대기 or 예약 확정일 경우에만 이루어 질 수 있음
		// 재고 복구
		int inventory = scheduleRepository.restoreCapacity(user.getProductScheduleId(), user.getGuestCount(),
			product.getMaxCapacity());
		// 복구 실패
		if (inventory == 0) { // 재고 복구 상태값 원복
			throw new BusinessException(CommonErrorCode.RESTORE_INVENTORY_FAILED);
		}

		// 상태값 변경
		// 예약 보류, 예약 확정일 경우 및 재고 복구 중일 경우에 수정
		int count = scheduleRepository.updateStatus(user.getId(), orderStatus
			, List.of(ReservationStatus.RESERVED, ReservationStatus.CONFIRMED));
		if (count == 0) {
			throw new BusinessException(CommonErrorCode.UPDATE_RESERVATION_STATUS_FAILED);
		}

		return OrderValid.OK;

	}

	@Override
	public int restoreCapacity(UUID scheduleId, int quantity, int capacity) {
		return scheduleRepository.restoreCapacity(scheduleId, quantity, capacity);
	}

	@Override
	public AvailabilityScheduleResponseDto availabilitySchedule(UUID scheduleId) {
		Schedule schedule = findByIdOrThrow(scheduleId);
		Product product = productUseCase.findByIdOrThrow(schedule.getProductId());
		// 총 예약 완료 인원 수
		int reservedCount = product.getMaxCapacity() - schedule.getCapacity();

		return AvailabilityScheduleResponseDto.from(schedule, product.getMaxCapacity(), reservedCount);
	}

	// 상품 일자 존재 여부/단일 상품 일자 검색
	private Schedule findByIdOrThrow(UUID schedulesId) {
		return scheduleRepository.findByIdAndDeleteDtIsNull(schedulesId)
			.orElseThrow(() -> new BusinessException(CommonErrorCode.SCHDULES_NOT_FOUND));
	}

	// 로그인 계정 여부
	public UserResponseDto findBySellerIdOrThrow(UUID sellerId) {
		UserResponseDto sellerInfo = sellerRepository.findSeller(sellerId)
			.orElseThrow(() -> new BusinessException(CommonErrorCode.SELLER_NOT_FOUND));

		return sellerInfo;
	}

	// 이전 날짜 확인
	private void validatePastDate(LocalDate scheduleDt) {
		if (scheduleDt.isBefore(LocalDate.now())) {
			throw new BusinessException(CommonErrorCode.INVALID_SCHEDULE_DATE);
		}
	}

	// 시작 시간, 종료 시간 확인
	private void validateTime(LocalTime start, LocalTime end) {
		if (!start.isBefore(end)) {
			throw new BusinessException(CommonErrorCode.INVALID_TIME_RANGE);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public LocalDate getScheduleStartDate(UUID scheduleId) {
		return findByIdOrThrow(scheduleId).getScheduleDt();
	}

}
