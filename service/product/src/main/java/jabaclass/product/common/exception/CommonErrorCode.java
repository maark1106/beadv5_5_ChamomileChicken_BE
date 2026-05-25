package jabaclass.product.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum CommonErrorCode {
	//400 BAD_REQUEST 잘못된 요청
	INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "파라미터 값을 확인해주세요."),
	//500 INTERNAL SERVER ERROR
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 에러입니다"),
	//404 NOT_FOUND 잘못된 리소스 접근
	PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 상품 ID 입니다."),
	// 404 판매자 없음
	SELLER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 유저 입니다."),
	// 403 판매자 권한이 없음
	NOT_SELLER(HttpStatus.FORBIDDEN, "판매자가 아닙니다."),
	// 404 룰 이름이 바뀐 경우
	INVALID_ROLE(HttpStatus.BAD_REQUEST, "판매자 확인이 다시 필요합니다. 관리자에게 문의 주세요."),
	// 400 상품명 확인 필요
	NOT_TITLE(HttpStatus.BAD_REQUEST, "상품명을 입력 해주세요."),
	// 400 유저 아이디 확인 필요
	NOT_USERID(HttpStatus.BAD_REQUEST, "판매자 Id를 입력 해주세요."),
	// 400 가격 확인 필요
	NOT_PRICE(HttpStatus.BAD_REQUEST, "가격은 1원 이상이어야 합니다."),
	// 400 총인원수 확인 필요
	NOT_MAXCAPACITY(HttpStatus.BAD_REQUEST, "예약 가능 인원수를 입력 해주세요."),
	// 400 상품 ID 확인 필요
	NOT_PRODUCTID(HttpStatus.BAD_REQUEST, "프로젝트 ID 입력 해주세요."),
	//500 삭제 실패
	FAIL_DELETE(HttpStatus.INTERNAL_SERVER_ERROR, "삭제 에러입니다."),
	//404 판매자와 상품의 매치 실패
	MATCH_FAIL(HttpStatus.FORBIDDEN, "해당 상품은 본인 상품이 아닙니다."),
	// 401 인증 되지 않은 유저->sellerId가 비어있을 경우
	EMPTY_USER(HttpStatus.UNAUTHORIZED, "로그인 후 다시 이용해주세요."),
	// 400 이전 날짜 불가
	INVALID_SCHEDULE_DATE(HttpStatus.BAD_REQUEST, "오늘보다 이전 날짜는 등록 할 수 없습니다."),
	// 400 잘못된 날짜
	INVALID_DATE_FORMAT(HttpStatus.BAD_REQUEST, "입력하신 날짜를 다시 확인해주세요."),
	// 400 시작 시간 보다 종료 시간이 일찍은 안됨
	INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "시작 시간보다 종료 시간이 빠를 수 없습니다."),
	// 400 동일 날짜에 시간대가 겹치는 스케줄
	SCHEDULE_CONFLICT(HttpStatus.BAD_REQUEST, "동일한 날짜에 같은 일정이 존재합니다."),
	// 400 날짜 형식이 맞지 않음
	DATE_BAD_FORMAT(HttpStatus.BAD_REQUEST, "날짜 형식이 맞지 않습니다."),
	// 400 시간 형식이 맞지 않음
	TIME_BAD_FORMAT(HttpStatus.BAD_REQUEST, "시간 형식이 맞지 않습니다."),
	//404 상품 일자가 존재하지 않습니다.
	SCHDULES_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 상품 일자 ID 입니다."),
	// 400 일자가 존재하지 않습니다.
	DATE_NOT_FOUND(HttpStatus.NOT_FOUND, "일자를 입력해주세요."),
	// 400 시작 시간이 존재하지 않습니다.
	STTIME_NOT_FOUND(HttpStatus.NOT_FOUND, "시작 시간을 입력해주세요."),
	// 400 종료 시간이 존재하지 않습니다.
	EDTIME_NOT_FOUND(HttpStatus.NOT_FOUND, "종료 시간을 입력해주세요."),
	// 404 유저가 없음
	NOT_BUY_USER(HttpStatus.NOT_FOUND, "예약자가 존재하지 않습니다."),
	// 404 본인 즐겨찾기가 아닙니다.
	NOT_MATCH_USER_LIKE(HttpStatus.NOT_FOUND, "본인의 즐겨찾기가 아닙니다."),
	// 409 이미 찜한 상품입니다.
	ALREADY_LIKED(HttpStatus.CONFLICT, "이미 찜한 상품입니다."),
	// 404 본인 즐겨찾기가 아닙니다.
	NOT_MATCH_USER_REVIEW(HttpStatus.NOT_FOUND, "본인의 리뷰가 아닙니다."),
	// 404 리뷰가 존재하지 않습니다.
	NOT_FOUND_REVIEW(HttpStatus.NOT_FOUND, "리뷰가 존재하지 않습니다."),
	// 이미지 최대 10장
	IMAGE_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "이미지는 최대 10장까지 등록 가능합니다."),
	// 파일을 찾을수 없음.
	FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."),
	// 파일 검증 실패
	FILE_CONFIRM_FAIL(HttpStatus.BAD_REQUEST, "파일 검증에 실패했습니다."),
	// 재고 복구 실패 에러들
	RESTORE_INVENTORY_FAILED(HttpStatus.CONFLICT, "재고 복구에 실패했습니다."),
	UPDATE_RESERVATION_STATUS_FAILED(HttpStatus.CONFLICT, "예약 상태 변경에 실패했습니다."),
	// 파일 서비스 연결 불가
	FILE_MODULE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "파일 서비스에 연결할 수 없습니다.");

	private final HttpStatus status;
	private final String message;

	CommonErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public static String from(CommonErrorCode code) {
		return code.getMessage();
	}

}
