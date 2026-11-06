import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { BackButton, Empty, ErrorBox, Loading, TabBar } from '../components/ui.jsx';
import { attractionApi } from '../api/index.js';
import { ATTRACTION_STATUS } from '../api/labels.js';

const PAGE_SIZE = 10;       // 한 번에 불러올 개수 (백엔드 최대 50)
const SEARCH_DELAY_MS = 300; // 타자를 멈추고 0.3초 뒤에 검색해요

// 키 제한 필터는 백엔드에 키 정보가 없어서 뺐어요. (MVP: "이용 가능 키 및 나이 제한(추후)")
const FILTERS = [
  { code: 'ALL', label: '전체' },
  { code: 'AVAILABLE', label: '예약 가능' },
  { code: 'OPERATING', label: '운영 중' },
];

// 어트랙션 상태 코드 → 배지 정보 (운영 중인데 슬롯이 다 찼으면 '예약 마감')
export function statusOf(a) {
  if (a.status === 'OPERATING' && a.soldOut) return ATTRACTION_STATUS.SOLD_OUT;
  return ATTRACTION_STATUS[a.status];
}
const BADGE_COLOR = { green: 'var(--green-ink)', pink: 'var(--pink-dark)', gray: 'var(--gray-ink)', orange: 'var(--orange-ink)' };

export default function Attractions() {
  const [filter, setFilter] = useState('ALL');
  const [keyword, setKeyword] = useState('');           // 입력칸에 보이는 글자
  const [searchWord, setSearchWord] = useState('');     // 실제로 서버에 보낼 검색어
  const [sort, setSort] = useState('NAME');

  const [rides, setRides] = useState([]);               // 지금까지 불러온 어트랙션 (페이지를 이어 붙여요)
  const [page, setPage] = useState(0);                  // 마지막으로 불러온 페이지 번호
  const [total, setTotal] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // 마지막 요청 번호. 응답이 늦게 온 "옛날 요청"은 무시하려고 써요.
  // 예) "롤" 검색 → 바로 "롤러" 검색: "롤" 응답이 늦게 오면 화면이 "롤" 결과로 덮이는 걸 막아요.
  const requestId = useRef(0);

  // 1) 검색어 디바운스: 글자를 칠 때마다 요청하지 않고, 멈춘 뒤 0.3초가 지나면 검색어를 확정해요.
  useEffect(() => {
    const timer = setTimeout(() => setSearchWord(keyword), SEARCH_DELAY_MS);
    return () => clearTimeout(timer); // 0.3초 안에 또 치면 이전 타이머는 취소
  }, [keyword]);

  // 2) 서버에서 한 페이지 불러오기. append=true 면 기존 목록 뒤에 붙이고, false 면 새로 바꿔요.
  async function load(nextPage, append) {
    const myId = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const res = await attractionApi.search({ page: nextPage, size: PAGE_SIZE, keyword: searchWord, sort, filter });
      if (myId !== requestId.current) return; // 더 새로운 요청이 있으면 이 응답은 버려요
      setRides((prev) => (append ? [...prev, ...res.items] : res.items));
      setPage(res.page);
      setTotal(res.totalElements);
      setHasNext(res.hasNext);
    } catch (e) {
      if (myId === requestId.current) setError(e);
    } finally {
      if (myId === requestId.current) setLoading(false);
    }
  }

  // 3) 필터·정렬·검색어가 바뀌면 첫 페이지부터 다시 불러와요.
  useEffect(() => {
    load(0, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter, sort, searchWord]);

  return (
    <div className="screen">
      <div className="screen-body">
        <div className="card stack gap-12" style={{ padding: '18px 16px 12px', borderRadius: '0 0 26px 26px' }}>
          <div className="row between">
            <div className="title-with-back">
              <BackButton to="/" />
              <h1 className="h1">어트랙션</h1>
            </div>
            <span className="small muted">총 {total}개</span>
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
              <option value="NAME">이름순</option>
              <option value="LATEST">최신순</option>
            </select>
          </div>
        </div>

        {/* 첫 페이지를 불러올 때만 전체 "불러오는 중"을 보여 줘요. (더 보기 중에는 목록을 그대로 둬요) */}
        {loading && rides.length === 0 && <Loading />}
        {error && <ErrorBox error={error} onRetry={() => load(rides.length ? page + 1 : 0, rides.length > 0)} />}
        {!loading && !error && rides.length === 0 && (
          <Empty>{searchWord.trim() ? `'${searchWord.trim()}' 검색 결과가 없어요.` : '조건에 맞는 어트랙션이 없어요.'}</Empty>
        )}
        {rides.length > 0 && (
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
        {/* 다음 페이지가 있으면 "더 보기" 버튼 */}
        {hasNext && !error && (
          <div style={{ padding: '14px 16px 0' }}>
            <button type="button" className="btn btn-outline" style={{ width: '100%' }} disabled={loading}
              onClick={() => load(page + 1, true)}>
              {loading ? '불러오는 중…' : `더 보기 (${rides.length} / ${total})`}
            </button>
          </div>
        )}
      </div>
      <TabBar />
    </div>
  );
}
