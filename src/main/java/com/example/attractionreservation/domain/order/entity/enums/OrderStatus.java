package com.example.attractionreservation.domain.order.entity.enums;


// 주문 상태
// TODO: 팀에서 확정한 값으로 수정하세요.
public enum OrderStatus {
    // 결제 대기
    PENDING {
        @Override
        public boolean canTransitTo(OrderStatus nextStatus) {
            return nextStatus != REFUNDED && nextStatus != PARTIALLY_REFUNDED;
        }
    },
    // 결제 완료
    COMPLETED {
        @Override
        public boolean canTransitTo(OrderStatus nextStatus) {
            return nextStatus == PARTIALLY_REFUNDED || nextStatus == REFUNDED;
        }
    },
    // 부분 환불
    PARTIALLY_REFUNDED {
        @Override
        public boolean canTransitTo(OrderStatus nextStatus) {
            return nextStatus == REFUNDED ||  nextStatus == PARTIALLY_REFUNDED;
        }
    },
    // 전체 환불
    REFUNDED {
        @Override
        public boolean canTransitTo(OrderStatus nextStatus) {
            return false;
        }
    },
    // 결제 취소
    CANCELLED {
        @Override
        public boolean canTransitTo(OrderStatus nextStatus) {
            return false;
        }
    };

    public abstract boolean canTransitTo(OrderStatus nextStatus);
}
