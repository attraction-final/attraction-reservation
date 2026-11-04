import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Icon from '../../components/Icon.jsx';
import { BackHeader, ErrorBox, Loading } from '../../components/ui.jsx';
import { useApi } from '../../hooks/useApi.js';
import { adminApi } from '../../api/index.js';

/*
 * 어트랙션 등록 / 수정 화면 (하나의 화면을 같이 써요)
 *  - /admin/attractions/new        → 등록 (빈 칸)
 *  - /admin/attractions/:id/edit   → 수정 (값이 채워진 칸 + 운영 상태 + 삭제)
 */

const EMPTY = { name: '', description: '', location: '', durationMinutes: '', entranceMoveMinutes: '' };

// 운영 상태 버튼 (백엔드 OperationStatus 중 관리자가 고를 수 있는 3가지)
const STATUS_OPTIONS = [
  { code: 'OPERATING', label: '운영' },
  { code: 'MAINTENANCE', label: '점검' },
  { code: 'SUSPENDED', label: '중단' },
];

// 백엔드 CreateAttractionRequest 와 같은 규칙으로 먼저 검사해요.
// (서버도 다시 검사하지만, 여기서 막으면 사용자가 바로 이유를 알 수 있어요)
function validate(form) {
  if (!form.name.trim()) return '이름을 입력해 주세요.';
  if (form.name.trim().length > 50) return '이름은 50자까지 쓸 수 있어요.';
  if (!form.description.trim()) return '설명을 입력해 주세요.';
  if (form.description.trim().length > 200) return '설명은 200자까지 쓸 수 있어요.';
  if (!form.location.trim()) return '위치를 입력해 주세요.';
  if (form.location.trim().length > 50) return '위치는 50자까지 쓸 수 있어요.';
  if (!(Number(form.durationMinutes) >= 1)) return '탑승 시간은 1분 이상이어야 해요.';
  if (!(Number(form.entranceMoveMinutes) >= 1)) return '입구에서 이동 시간은 1분 이상이어야 해요.';
  return '';
}

export default function AdminAttractionForm() {
  const navigate = useNavigate();
  const { id } = useParams();       // 주소에 id 가 있으면 수정, 없으면 등록
  const isEdit = Boolean(id);

  const detail = useApi(() => (isEdit ? adminApi.attractionForEdit(id) : Promise.resolve(null)), [id]);
  const [form, setForm] = useState(EMPTY);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [saving, setSaving] = useState(false);

  // 수정 화면이면 불러온 값으로 칸을 채워요.
  useEffect(() => {
    if (detail.data) {
      const { status: s, ...rest } = detail.data;
      setForm(rest);
      setStatus(s);
    }
  }, [detail.data]);

  const onChange = (e) => { setForm({ ...form, [e.target.name]: e.target.value }); setError(''); };

  async function save() {
    const problem = validate(form);
    if (problem) return setError(problem);
    setSaving(true);
    setMessage('');
    try {
      if (isEdit) {
        await adminApi.updateAttraction(id, form);
        setMessage('어트랙션 정보를 저장했어요.');
      } else {
        await adminApi.createAttraction(form);
        navigate('/admin/slots', { state: { message: `${form.name.trim()} 어트랙션을 등록했어요.` } });
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving(false);
    }
  }

  async function changeStatus(next) {
    if (next === status) return;
    const label = STATUS_OPTIONS.find((o) => o.code === next).label;
    if (!window.confirm(`운영 상태를 "${label}"(으)로 바꿀까요?`)) return;
    setError('');
    setMessage('');
    try {
      await adminApi.updateAttractionStatus(id, next);
      setStatus(next);
      setMessage(`운영 상태를 "${label}"(으)로 바꿨어요.`);
    } catch (e) {
      setError(e.message);
    }
  }

  async function remove() {
    if (!window.confirm(`${form.name} 어트랙션을 삭제할까요? 삭제하면 목록에서 사라져요.`)) return;
    setError('');
    try {
      await adminApi.deleteAttraction(id);
      navigate('/admin/slots', { state: { message: `${form.name} 어트랙션을 삭제했어요.` } });
    } catch (e) {
      setError(e.message);
    }
  }

  const title = isEdit ? '어트랙션 수정' : '어트랙션 등록';
  if (detail.loading) return <div className="screen"><BackHeader title={title} to="/admin/slots" /><Loading /></div>;
  if (detail.error) return <div className="screen"><BackHeader title={title} to="/admin/slots" /><ErrorBox error={detail.error} onRetry={detail.reload} /></div>;

  return (
    <div className="screen">
      <BackHeader title={title} to="/admin/slots" />
      <div className="screen-body pad stack gap-14">
        {message && <div className="toast"><Icon name="check" size={18} strokeWidth={2.4} />{message}</div>}

        {/* 기본 정보 */}
        <div className="card stack gap-12" style={{ borderRadius: 22, padding: 16, flexShrink: 0 }}>
          <div className="field">
            <label htmlFor="name">이름</label>
            <input id="name" name="name" className="input" maxLength={50} placeholder="예: 회전목마" value={form.name} onChange={onChange} />
          </div>
          <div className="field">
            <label htmlFor="description">설명</label>
            <textarea id="description" name="description" className="input" maxLength={200} rows={3} placeholder="어떤 놀이기구인지 알려 주세요"
              value={form.description} onChange={onChange} style={{ height: 'auto', padding: '12px 16px', resize: 'none', fontFamily: 'inherit' }} />
            <span className="small muted" style={{ alignSelf: 'flex-end' }}>{form.description.length}/200</span>
          </div>
          <div className="field">
            <label htmlFor="location">위치</label>
            <input id="location" name="location" className="input" maxLength={50} placeholder="예: 키즈존" value={form.location} onChange={onChange} />
          </div>
          <div className="row gap-10">
            <div className="field grow">
              <label htmlFor="durationMinutes">탑승 시간(분)</label>
              <input id="durationMinutes" name="durationMinutes" type="number" min={1} className="input" placeholder="5" value={form.durationMinutes} onChange={onChange} />
            </div>
            <div className="field grow">
              <label htmlFor="entranceMoveMinutes">입구에서 이동(분)</label>
              <input id="entranceMoveMinutes" name="entranceMoveMinutes" type="number" min={1} className="input" placeholder="10" value={form.entranceMoveMinutes} onChange={onChange} />
            </div>
          </div>
          <span className="small muted">탑승 시간으로 슬롯 종료 시각이, 이동 시간으로 루트 추천이 계산돼요.</span>
        </div>

        {/* 수정 화면에서만: 운영 상태 + 삭제 */}
        {isEdit && (
          <div className="card stack gap-10" style={{ borderRadius: 22, padding: 16, flexShrink: 0 }}>
            <h2 className="h2">운영 상태</h2>
            <div className="row gap-8">
              {STATUS_OPTIONS.map((o) => (
                <button key={o.code} type="button" aria-pressed={status === o.code} className={`chip grow${status === o.code ? ' on' : ''}`}
                  onClick={() => changeStatus(o.code)} style={{ height: 40, justifyContent: 'center' }}>
                  {o.label}
                </button>
              ))}
            </div>
            <span className="small muted">운영 상태는 누르는 즉시 바뀌어요. 이용 예정 예약이 있으면 점검·중단으로 바꿀 수 없어요.</span>
            <button type="button" className="btn btn-sm btn-danger-outline" style={{ width: '100%', height: 46 }} onClick={remove}>
              어트랙션 삭제
            </button>
          </div>
        )}

        {error && <span className="error-text" role="alert"><Icon name="alert" size={14} strokeWidth={2} />{error}</span>}
      </div>

      <div className="bottom-bar">
        <button type="button" className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? '저장 중…' : isEdit ? '정보 저장' : '등록하기'}
        </button>
      </div>
    </div>
  );
}
