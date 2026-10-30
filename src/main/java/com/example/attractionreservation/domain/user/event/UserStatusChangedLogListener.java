package com.example.attractionreservation.domain.user.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class UserStatusChangedLogListener {

    // DB 변경이 커밋된 경우에만 상태 변경 완료를 기록한다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserStatusChanged(UserStatusChangedEvent event) {
        log.info(
                "event=USER_STATUS_CHANGED result=SUCCESS "
                        + "adminId={} userId={} previousStatus={} currentStatus={}",
                event.adminId(),
                event.userId(),
                event.previousStatus(),
                event.currentStatus()
        );
    }
}