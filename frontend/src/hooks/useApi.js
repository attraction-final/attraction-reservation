import { useCallback, useEffect, useState } from 'react';

/**
 * 화면이 열릴 때 서버에서 데이터를 불러오는 작은 도우미(커스텀 훅)입니다.
 *
 * 사용 예)
 *   const { data, loading, error, reload } = useApi(() => attractionApi.list(filter), [filter]);
 *
 * - loading : 불러오는 중이면 true
 * - error   : 실패하면 에러 객체 (error.message 에 이유가 들어 있어요)
 * - data    : 성공하면 서버가 준 데이터
 * - reload  : 다시 불러오고 싶을 때 부르는 함수 (예: 예약 취소 후 목록 새로고침)
 * - deps    : 이 값들이 바뀌면 자동으로 다시 불러와요 (예: 필터를 바꿨을 때)
 */
export function useApi(fetcher, deps = []) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(fetcher, deps);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setData(await load());
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [load]);

  useEffect(() => {
    let alive = true; // 화면을 떠난 뒤 늦게 온 응답은 무시하기 위한 표시
    setLoading(true);
    setError(null);
    load()
      .then((d) => alive && setData(d))
      .catch((e) => alive && setError(e))
      .finally(() => alive && setLoading(false));
    return () => { alive = false; };
  }, [load]);

  return { data, loading, error, reload, setData };
}
