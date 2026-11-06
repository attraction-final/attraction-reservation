import { useState } from 'react';
import { Link } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { ErrorBox, Loading, TabBar } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { attractionApi } from '../api/index.js';
import { ATTRACTION_STATUS } from '../api/labels.js';

const FILTERS = [
  { code: 'ALL', label: '전체' },
  { code: 'AVAILABLE', label: '예약 가능' },
  { code: 'HEIGHT', label: '키 제한' },
];

// 어트랙션 상태 코드 → 배지 정보 (운영 중인데 슬롯이 다 찼으면 '예약 마감')
export function statusOf(a) {
  if (a.status === 'OPERATING' && a.soldOut) return ATTRACTION_STATUS.SOLD_OUT;
  return ATTRACTION_STATUS[a.status];
}
const BADGE_COLOR = { green: 'var(--green-ink)', pink: 'var(--pink-dark)', gray: 'var(--gray-ink)', orange: 'var(--orange-ink)' };

export default function Attractions() {
  const [filter, setFilter] = useState('ALL');
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('name');

  // filter 가 바뀔 때마다 서버에서 다시 불러와요.
  const { data, loading, error, reload } = useApi(() => attractionApi.list(filter), [filter]);

  // 검색어·정렬은 받아온 목록 안에서 처리 (데이터가 많아지면 서버로 넘겨도 돼요)
  const rides = (data || [])
    .filter((a) => a.name.includes(keyword.trim()))
    .sort((a, b) => (sort === 'name'
      ? a.name.localeCompare(b.name, 'ko')
      : (a.nextSlot?.time || '99').localeCompare(b.nextSlot?.time || '99')));

  return (
    <div className="screen">
      <div className="screen-body">
        <div className="card stack gap-12" style={{ padding: '18px 16px 12px', borderRadius: '0 0 26px 26px' }}>
          <div className="row between" style={{ alignItems: 'baseline' }}>
            <h1 className="h1">어트랙션</h1>
            <span className="small muted">총 {rides.length}개</span>
          </div>
          <div className="search">
            <Icon name="search" size={20} strokeWidth={2} color="var(--muted)" />
            <input type="search" aria-label="어트랙션 이름 검색" placeholder="어트랙션 이름 검색" value={keyword} onChange={(e) => setKeyword(e.target.value)} />
          </div>
          <div className="row gap-8">
            {FILTERS.map((f) => (
              <button key={f.code} type="button" className={`chip${filter === f.code ? ' on' : ''}`} aria-pressed={filter === f.code} onClick={() => setFilter(f.code)}>
                {f.label}
              </button>
            ))}
            <span className="grow" />
            <select aria-label="정렬" value={sort} onChange={(e) => setSort(e.target.value)}
              style={{ height: 36, borderRadius: 999, border: '1.5px solid var(--line)', background: '#fff', padding: '0 10px', fontSize: 13 }}>
              <option value="name">이름순</option>
              <option value="time">빠른 예약순</option>
            </select>
          </div>
        </div>

        {loading && <Loading />}
        {error && <ErrorBox error={error} onRetry={reload} />}
        {!loading && !error && (
          <div style={{ padding: '14px 16px 0', display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 12 }}>
            {rides.map((a) => {
              const st = statusOf(a);
              return (
                <Link key={a.id} to={`/attractions/${a.id}`} className="card card-link stack gap-8"
                  style={{ borderRadius: 22, padding: '8px 8px 12px', opacity: a.status === 'MAINTENANCE' ? 0.72 : 1 }}>
                  <span style={{ position: 'relative', display: 'block' }}>
                    <img src={a.image} alt={a.name} style={{ width: '100%', aspectRatio: '1 / 1', objectFit: 'cover', borderRadius: 16 }} />
                    <span className="badge sm" style={{ position: 'absolute', top: 8, left: 8, background: '#fff', color: BADGE_COLOR[st.tone], boxShadow: '0 2px 6px rgba(31,42,68,0.15)' }}>
                      {st.label}
                    </span>
                  </span>
                  <span className="bold" style={{ fontSize: 15, padding: '0 4px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{a.name}</span>
                </Link>
              );
            })}
          </div>
        )}
      </div>
      <TabBar />
    </div>
  );
}
