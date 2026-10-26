package com.example.attractionreservation.domain.payment.entity.enums;

// PG 보상 취소 요청 처리 상태
public enum CompensationStatus {
    // 보상 요청 저장, 처리 대기
    REQUESTED {
        @Override
        public boolean canTransitTo(CompensationStatus nextStatus) {
            return nextStatus == PROCESSING;
        }
    },
    // 즉시 시도 또는 스케줄러가 점유해 PG 취소 중
    PROCESSING {
        @Override
        public boolean canTransitTo(CompensationStatus nextStatus) {
            // PROCESSING → PROCESSING은 처리 중 멈춘 행을 다시 점유하는 경우
            return nextStatus == PROCESSING
                    || nextStatus == DONE
                    || nextStatus == FAILED
                    || nextStatus == DEAD;
        }
    },
    // PG 취소 실패, 재시도 대기
    FAILED {
        @Override
        public boolean canTransitTo(CompensationStatus nextStatus) {
            return nextStatus == PROCESSING;
        }
    },
    // PG 취소 완료
    DONE {
        @Override
        public boolean canTransitTo(CompensationStatus nextStatus) {
            return false;
        }
    },
    // 최대 시도 횟수 초과, 수동 환불 필요
    DEAD {
        @Override
        public boolean canTransitTo(CompensationStatus nextStatus) {
            return false;
        }
    };

    public abstract boolean canTransitTo(CompensationStatus nextStatus);
}
