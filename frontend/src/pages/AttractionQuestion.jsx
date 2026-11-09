import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { BackHeader, ErrorBox, Loading } from '../components/ui.jsx';
import { attractionQuestionApi } from '../api/index.js';
import { useAuth } from '../context/AuthContext.jsx';

/*
 * 어트랙션 질문 화면 (/attractions/ask)
 *
 * 방문객이 "모니모 러쉬 키 제한 있어?" 처럼 물어보면 답이 채팅처럼 쌓여요.
 * 서버 응답의 type 값에 따라 보여 주는 모양이 달라져요.
 *  - ANSWERED           : 답변 + "OO 보러 가기" 링크
 *  - NEED_CLARIFICATION : 되묻는 문장 + 후보 어트랙션 버튼
 *  - UNSUPPORTED        : 안내 문장만 (회색 말풍선)
 */

const MAX_LENGTH = 200; // 백엔드 CreateAttractionQuestionRequest 의 @Size(max = 200) 과 같아요.

// 예시 버튼: 누르면 입력칸만 채워요. 질문마다 비용이 들어서 바로 보내지 않아요.
const EXAMPLES = ['롤링 엑스 트레인 키 제한 알려줘', '아마존 익스프레스 몇시까지 해?', '썬더폴스 지금 탈 수 있어?'];

// 말풍선 공통 모양
const bubble = {
  maxWidth: '86%',
  padding: '10px 14px',
  borderRadius: 16,
  fontSize: 14,
  lineHeight: 1.5,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
};

export default function AttractionQuestion() {
  const { user, checking } = useAuth();
  const [draft, setDraft] = useState('');      // 입력칸에 쓰고 있는 글
  const [turns, setTurns] = useState([]);      // 주고받은 기록. 개인정보가 있을 수 있어 저장소에 넣지 않아요.
  const [pending, setPending] = useState(null); // 지금 답을 기다리는 질문 (없으면 null)
  const endRef = useRef(null);                 // 대화 맨 아래 표시

  // 새 말풍선이 생기면 맨 아래로 스크롤해요.
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' });
  }, [turns, pending]);

  async function ask(question) {
    const text = question.trim();
    if (!text || pending) return; // 빈 질문이거나 이미 기다리는 중이면 안 보내요. (두 번 클릭 방지)
    setPending(text);
    setDraft('');
    try {
      const result = await attractionQuestionApi.ask(text);
      setTurns((prev) => [...prev, { question: text, result }]);
    } catch (error) {
      setTurns((prev) => [...prev, { question: text, error }]);
    } finally {
      setPending(null);
    }
  }

  // 로그인 확인 중
  if (checking) return <div className="screen"><Loading /></div>;

  // 로그인 안 했으면 로그인 링크
  if (!user) {
    return (
      <div className="screen">
        <BackHeader title="어트랙션 질문" to="/attractions" />
        <ErrorBox error={{ status: 401, message: '로그인하면 어트랙션을 물어볼 수 있어요.' }} />
      </div>
    );
  }

  return (
    <div className="screen">
      <BackHeader title="어트랙션 질문" to="/attractions" />

      {/* 대화 영역 (스크롤) */}
      <div className="screen-body stack gap-12" style={{ padding: '4px 16px 16px' }}>
        <div className="stack gap-8">
          <p className="muted" style={{ margin: 0, fontSize: 14, lineHeight: 1.5 }}>키 제한, 운영 시간, 운영 상태를 물어보세요.</p>
          {turns.length === 0 && !pending && (
            <div className="row gap-8" style={{ flexWrap: 'wrap' }}>
              {EXAMPLES.map((example) => (
                <button key={example} type="button" className="chip"
                  style={{ height: 'auto', minHeight: 36, whiteSpace: 'normal', textAlign: 'left' }}
                  onClick={() => setDraft(example)}>
                  {example}
                </button>
              ))}
            </div>
          )}
        </div>

        {turns.map((turn, index) => (
          <div key={index} className="stack gap-8">
            <Question text={turn.question} />
            {turn.error
              ? <ErrorBox error={turn.error} onRetry={pending ? undefined : () => ask(turn.question)} />
              : <Answer
                  result={turn.result}
                  // 서버는 이전 대화를 기억하지 않아서 "후보 이름 + 원래 질문" 을 새 질문으로 보내요.
                  onPick={(candidate) => ask(`${candidate.name} ${turn.question}`.slice(0, MAX_LENGTH))}
                  disabled={Boolean(pending)} />}
          </div>
        ))}

        {pending && (
          <div className="stack gap-8">
            <Question text={pending} />
            <div className="muted" role="status" style={{ ...bubble, alignSelf: 'flex-start', background: 'var(--soft)' }}>답을 찾고 있어요…</div>
          </div>
        )}
        <div ref={endRef} />
      </div>

      {/* 입력 영역 (화면 아래 고정) */}
      <form className="stack gap-4"
        style={{ flexShrink: 0, background: '#fff', borderTop: '1px solid #E3ECF6', padding: '10px 16px 14px' }}
        onSubmit={(e) => { e.preventDefault(); ask(draft); }}>
        <div className="row gap-8">
          <input className="input grow" style={{ width: 'auto' }} aria-label="질문 입력"
            placeholder="어트랙션에 대해 물어보세요" maxLength={MAX_LENGTH}
            value={draft} onChange={(e) => setDraft(e.target.value)} />
          <button type="submit" className="btn btn-primary btn-sm"
            style={{ width: 72, height: 50, padding: 0, flexShrink: 0, whiteSpace: 'nowrap' }}
            disabled={Boolean(pending) || !draft.trim()}>보내기</button>
        </div>
        <div className="row between">
          <span className="small muted">질문은 외부 AI로 전달돼요. 이름이나 연락처는 적지 마세요.</span>
          <span className="small muted" style={{ flexShrink: 0, marginLeft: 8 }}>{draft.length}/{MAX_LENGTH}</span>
        </div>
      </form>
    </div>
  );
}

// 내 질문 (오른쪽 파란 말풍선)
function Question({ text }) {
  return <div style={{ ...bubble, alignSelf: 'flex-end', background: 'var(--blue)', color: '#fff' }}>{text}</div>;
}

// 답변 하나 (왼쪽 말풍선). type 에 따라 아래에 붙는 내용이 달라져요.
function Answer({ result, onPick, disabled }) {
  const unsupported = result.type === 'UNSUPPORTED';
  return (
    <div className="stack gap-8" style={{ alignItems: 'flex-start' }}>
      <div style={{
        ...bubble,
        background: unsupported ? 'var(--gray-soft)' : 'var(--soft)',
        color: unsupported ? 'var(--gray-ink)' : 'var(--ink)',
      }}>
        {result.answer}
      </div>

      {result.type === 'ANSWERED' && (
        <Link to={`/attractions/${result.attractionId}`} className="small bold">{result.attractionName} 보러 가기</Link>
      )}

      {result.type === 'NEED_CLARIFICATION' && (
        <div className="row gap-8" style={{ flexWrap: 'wrap' }}>
          {result.candidates.map((candidate) => (
            <button key={candidate.attractionId} type="button" className="chip" disabled={disabled} onClick={() => onPick(candidate)}>
              {candidate.name}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
