package com.example.attractionreservation.domain.payment.entity.enums;

// 결제 상태
public enum PaymentStatus {
    // 결제 대기
    PENDING {
        @Override
        public boolean canTransitTo(PaymentStatus nextStatus) {
            return nextStatus != REFUNDED && nextStatus != PARTIALLY_REFUNDED;
        }
    },
    // 결제 완료
    PAID {
        @Override
        public boolean canTransitTo(PaymentStatus nextStatus) {
            return nextStatus == PARTIALLY_REFUNDED || nextStatus == REFUNDED;
        }
    },
    // 부분 환불
    PARTIALLY_REFUNDED {
        @Override
        public boolean canTransitTo(PaymentStatus nextStatus) {
            return nextStatus == REFUNDED ||  nextStatus == PARTIALLY_REFUNDED;
        }
    },
    // 전체 환불
    REFUNDED {
        @Override
        public boolean canTransitTo(PaymentStatus nextStatus) {
            return false;
        }
    },
    // 결제 실패
    FAILED {
        @Override
        public boolean canTransitTo(PaymentStatus nextStatus) {
            return false;
        }
    },
    // 결제 취소
    CANCELLED {
        @Override
        public boolean canTransitTo(PaymentStatus nextStatus) {
            return false;
        }
    };

    public abstract boolean canTransitTo(PaymentStatus nextStatus);
}
