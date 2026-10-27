package com.example.attractionreservation.domain.payment.entity.enums;

// 웹훅 처리 상태
public enum WebhookStatus {
    // 수신 완료, 처리 대기
    RECEIVED {
        @Override
        public boolean canTransitTo(WebhookStatus nextStatus) {
            return nextStatus == PROCESSING;
        }
    },
    // 스케줄러가 점유해 처리 중
    PROCESSING {
        @Override
        public boolean canTransitTo(WebhookStatus nextStatus) {
            // PROCESSING → PROCESSING은 처리 중 멈춘 행을 다시 점유하는 경우
            return nextStatus == PROCESSING
                    || nextStatus == PROCESSED
                    || nextStatus == IGNORED
                    || nextStatus == FAILED
                    || nextStatus == DEAD;
        }
    },
    // 처리 실패, 재시도 대기
    FAILED {
        @Override
        public boolean canTransitTo(WebhookStatus nextStatus) {
            return nextStatus == PROCESSING;
        }
    },
    // 처리 완료
    PROCESSED {
        @Override
        public boolean canTransitTo(WebhookStatus nextStatus) {
            return false;
        }
    },
    // 결제와 무관해 처리하지 않음
    IGNORED {
        @Override
        public boolean canTransitTo(WebhookStatus nextStatus) {
            return false;
        }
    },
    // 최대 시도 횟수 초과, 수동 처리 필요
    DEAD {
        @Override
        public boolean canTransitTo(WebhookStatus nextStatus) {
            return false;
        }
    };

    public abstract boolean canTransitTo(WebhookStatus nextStatus);
}
