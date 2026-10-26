package com.example.attractionreservation.domain.refund.entity.enums;

// 환불 상태. 환불 자체가 PG 취소 요청(Outbox)이며 상태로 처리 단계를 관리한다.
public enum RefundStatus {
    // 환불 요청 저장, PG 취소 대기
    REQUESTED {
        @Override
        public boolean canTransitTo(RefundStatus nextStatus) {
            return nextStatus == PROCESSING;
        }
    },
    // 즉시 시도 또는 스케줄러가 점유해 PG 취소 중
    PROCESSING {
        @Override
        public boolean canTransitTo(RefundStatus nextStatus) {
            // PROCESSING → PROCESSING은 처리 중 멈춘 환불을 다시 점유하는 경우
            return nextStatus == PROCESSING
                    || nextStatus == COMPLETED
                    || nextStatus == FAILED
                    || nextStatus == DEAD;
        }
    },
    // PG 취소 실패, 재시도 대기
    FAILED {
        @Override
        public boolean canTransitTo(RefundStatus nextStatus) {
            return nextStatus == PROCESSING;
        }
    },
    // PG 취소 완료
    COMPLETED {
        @Override
        public boolean canTransitTo(RefundStatus nextStatus) {
            return false;
        }
    },
    // 재시도하지 않음. PG에서 취소됐는지 수동으로 확인해야 한다
    DEAD {
        @Override
        public boolean canTransitTo(RefundStatus nextStatus) {
            return false;
        }
    };

    public abstract boolean canTransitTo(RefundStatus nextStatus);
}
