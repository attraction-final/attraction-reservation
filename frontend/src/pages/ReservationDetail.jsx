import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { BackHeader, Badge, ErrorBox, Loading } from '../components/ui.jsx';
import ReservationCancelSheet from '../components/ReservationCancelSheet.jsx';
import { useApi } from '../hooks/useApi.js';
import { useAuth } from '../context/AuthContext.jsx';
import { reservationApi } from '../api/index.js';
import { RESERVATION_STATUS, dayOfWeek } from '../api/labels.js';

/*
 * 예약 상세 화면 (/my/reservations/:id)
 * [GET /api/reservations/{id}] 로 예약 한 건을 불러와서 보여 줘요.
 * 예약 완료(RESERVED) 상태면 여기서 바로 취소할 수 있어요.
 */

// 서버의 Instant("2026-11-05T01:23:45Z", 세계 표준시) → 한국 시간 글자 "2026. 11. 5. 오전 10:23"
function toLocalDateTime(instant) {
  if (!instant) return '-';
  return new Date(instant).toLocaleString('ko-KR', {
    year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: '2-digit',
  });
}

export default function ReservationDetail() {
  const { id } = useParams(); // 주소의 :id 부분
  const { user, checking } = useAuth();
  const [cancelOpen, setCancelOpen] = useState(false);

  // 로그인 확인이 끝나고 로그인한 경우에만 불러와요. (새로고침 직후에는 잠깐 user 가 비어 있어요)
  const { data: r, loading, error, reload } = useApi(
    () => (user ? reservationApi.detail(id) : Promise.resolve(null)),
    [id, user],
  );

  if (checking) return <div className="screen"><Loading /></div>;

  return (
    <div className="screen">
      <div className="screen-body">
        <BackHeader title="예약 상세" to="/my/reservations" />

        <div className="pad stack gap-12" style={{ paddingTop: 8 }}>
          {!user && (
            <div className="state-box">로그인이 필요해요.<br /><Link to="/login">로그인하러 가기</Link></div>
          )}
          {user && loading && <Loading />}
          {user && error && <ErrorBox error={error} onRetry={reload} />}

          {r && (
            <>
              {/* 어트랙션 + 상태 */}
              <div className="card row gap-12" style={{ borderRadius: 22, padding: 12 }}>
                <img src={r.image} alt={r.attractionName} style={{ width: 72, height: 72, borderRadius: 16, objectFit: 'cover' }} />
                <div className="stack grow" style={{ gap: 4 }}>
                  <span className="bold" style={{ fontSize: 17 }}>{r.attractionName}</span>
                  <span className="small muted">
                    {[r.zone, r.rideMinutes ? `탑승 ${r.rideMinutes}분` : null].filter(Boolean).join(' · ') || '어트랙션 정보 없음'}
                  </span>
                </div>
                <Badge status={RESERVATION_STATUS[r.status]} small />
              </div>

              {/* 예약 정보 */}
              <div className="card stack gap-10" style={{ borderRadius: 22, padding: 16 }}>
                <Row label="예약번호" value={r.number} strong />
                <Row label="이용 날짜" value={`${r.date} (${dayOfWeek(r.date)})`} />
                <Row label="이용 시간" value={r.endTime ? `${r.startTime} ~ ${r.endTime}` : r.startTime} strong />
                <Row label="입장권 번호" value={`#${r.ticketId}`} />
                <Row label="예약한 시각" value={toLocalDateTime(r.createdAt)} />
              </div>

              {/* 취소된 예약이면 취소 정보 */}
              {r.status === 'CANCELED' && (
                <div className="card stack gap-10" style={{ borderRadius: 22, padding: 16 }}>
                  <Row label="취소 사유" value={r.cancelReason ?? '-'} />
                  <Row label="취소한 시각" value={toLocalDateTime(r.cancelledAt)} />
                </div>
              )}

              {/* 버튼: 어트랙션 보기 / 예약 취소(예약 완료일 때만) */}
              <div className="row gap-10">
                <Link to={`/attractions/${r.attractionId}`} className="btn btn-outline" style={{ flex: 1 }}>어트랙션 보기</Link>
                {r.status === 'RESERVED' && (
                  <button type="button" onClick={() => setCancelOpen(true)} className="bold"
                    style={{ flex: 1, height: 52, borderRadius: 16, border: 0, background: '#C2571A', color: '#fff', fontSize: 15, boxShadow: '0 4px 0 #8F3D0E' }}>
                    예약 취소
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      {cancelOpen && r && (
        <ReservationCancelSheet
          reservation={{ id: r.id, attractionName: r.attractionName, time: r.startTime }}
          onClose={() => setCancelOpen(false)}
          onDone={() => { setCancelOpen(false); reload(); }} // 다시 불러오면 상태가 "예약 취소"로 바뀌어요
        />
      )}
    </div>
  );
}

// "이름 ........ 값" 한 줄
function Row({ label, value, strong }) {
  return (
    <div className="row between" style={{ gap: 12 }}>
      <span className="small muted">{label}</span>
      <span className={strong ? 'bold' : ''} style={{ fontSize: 14, textAlign: 'right' }}>{value}</span>
    </div>
  );
}
