import { useState } from 'react';
import { AdminHeader, AdminTabBar, Badge, Empty, ErrorBox, Loading } from '../../components/ui.jsx';
import { useApi } from '../../hooks/useApi.js';
import { adminApi } from '../../api/index.js';
import { TICKET_PRODUCT_STATUS, formatPrice } from '../../api/labels.js';

// 관리자가 고를 수 있는 판매 상태 (DELETED 는 "삭제" 버튼으로만 바뀌어서 목록에서 빼요)
const STATUS_OPTIONS = Object.entries(TICKET_PRODUCT_STATUS).map(([code, v]) => ({ code, label: v.label }));

// 입장권 종류 (백엔드 TicketType). 등록할 때만 고를 수 있고, 수정은 안 돼요.
const TYPE_OPTIONS = [
  { code: 'ALL_DAY', label: '종일권' },
  { code: 'AFTERNOON', label: '오후권' },
];

// 글자 수 제한 (백엔드 CreateTicketProductRequest 의 @Size 와 같아요)
const NAME_MAX = 80;
const DESCRIPTION_MAX = 200;

const EMPTY_FORM = { name: '', type: 'ALL_DAY', price: '', description: '', status: 'ON_SALE', stock: '' };

// 입력값 검사: 문제가 있으면 안내 문구, 없으면 빈 문자열
// (서버도 같은 검사를 하지만, 요청 전에 먼저 알려 주면 더 친절해요)
function validate(form) {
  if (!form.name.trim()) return '상품명을 입력해 주세요.';
  if (form.price === '' || Number(form.price) < 0) return '가격은 0 이상으로 입력해 주세요.';
  if (!form.description.trim()) return '설명을 입력해 주세요.';
  if (form.stock === '' || Number(form.stock) < 0) return '재고는 0 이상으로 입력해 주세요.';
  return '';
}

export default function AdminProducts() {
  const { data, loading, error, reload } = useApi(() => adminApi.adminProducts(), []);

  const [creating, setCreating] = useState(false);      // 새 상품 등록 칸 열림 여부
  const [createForm, setCreateForm] = useState(EMPTY_FORM);
  const [openId, setOpenId] = useState(null);           // 수정 중인 상품
  const [editForm, setEditForm] = useState(null);
  const [formError, setFormError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  // 공통 실행기: 요청 → 성공 문구 → 목록 새로고침
  async function run(action, okText, after) {
    setFormError('');
    setMessage('');
    setBusy(true);
    try {
      await action();
      setMessage(okText);
      after?.();
      reload();
    } catch (e) {
      // 409(다른 사람이 먼저 수정함) 같은 서버 안내를 그대로 보여 줘요.
      setFormError(e.message);
    } finally {
      setBusy(false);
    }
  }

  function openEdit(p) {
    if (openId === p.id) {
      setOpenId(null);
      return;
    }
    setOpenId(p.id);
    setCreating(false);
    setFormError('');
    // 화면 입력칸은 글자(string)로 다뤄요. 보낼 때 index.js 가 숫자로 바꿔요.
    setEditForm({ name: p.name, price: String(p.price), description: p.description, status: p.status, stock: String(p.stock), version: p.version });
  }

  function submitCreate() {
    const msg = validate(createForm);
    if (msg) return setFormError(msg);
    run(() => adminApi.createProduct(createForm), `"${createForm.name.trim()}" 상품을 등록했어요.`, () => {
      setCreating(false);
      setCreateForm(EMPTY_FORM);
    });
  }

  function submitUpdate(p) {
    const msg = validate(editForm);
    if (msg) return setFormError(msg);
    run(() => adminApi.updateProduct(p.id, editForm), `"${editForm.name.trim()}" 상품을 저장했어요.`, () => setOpenId(null));
  }

  function remove(p) {
    if (!window.confirm(`"${p.name}" 상품을 삭제할까요?\n삭제하면 구매 화면에서 사라지고, 다시 수정할 수 없어요.`)) return;
    run(() => adminApi.deleteProduct(p.id), `"${p.name}" 상품을 삭제했어요.`, () => setOpenId(null));
  }

  return (
    <div className="screen">
      <div className="screen-body">
        <AdminHeader
          title="입장권 상품"
          right={
            <button type="button" className="btn btn-sm btn-dark" style={{ height: 36 }}
              onClick={() => { setCreating(!creating); setOpenId(null); setFormError(''); }}>
              {creating ? '닫기' : '+ 새 상품'}
            </button>
          }
        />

        <div className="pad stack gap-10" style={{ paddingTop: 14 }}>
          {message && <div className="toast">{message}</div>}

          {/* 새 상품 등록 칸 */}
          {creating && (
            <div className="card stack gap-10" style={{ borderRadius: 20, padding: 14, border: '2px solid var(--blue)' }}>
              <span className="bold">새 상품 등록</span>
              <ProductFields form={createForm} setForm={setCreateForm} withType />
              {formError && <span className="error-text" role="alert">{formError}</span>}
              <button type="button" className="btn btn-primary btn-sm" style={{ height: 46 }} disabled={busy} onClick={submitCreate}>
                {busy ? '등록 중…' : '등록하기'}
              </button>
            </div>
          )}

          {loading && <Loading />}
          {error && <ErrorBox error={error} onRetry={reload} />}
          {data?.length === 0 && <Empty>등록된 상품이 없어요.</Empty>}

          {data?.map((p) => {
            const open = p.id === openId;
            return (
              <div key={p.id} style={{ borderRadius: 20, overflow: 'hidden', background: '#fff', ...(open ? { border: '2px solid var(--blue)', boxShadow: 'var(--shadow-strong)' } : { border: '2px solid #fff', boxShadow: 'var(--shadow)' }) }}>
                <button type="button" aria-expanded={open} onClick={() => openEdit(p)} className="stack"
                  style={{ width: '100%', padding: 14, border: 0, background: 'transparent', textAlign: 'left', gap: 4 }}>
                  <span className="row between gap-8">
                    <span className="bold" style={{ fontSize: 15 }}>{p.name}</span>
                    <Badge status={TICKET_PRODUCT_STATUS[p.status]} small />
                  </span>
                  <span className="row between" style={{ fontSize: 13 }}>
                    <span className="muted">{p.typeLabel} · 재고 {p.stock.toLocaleString('ko-KR')}장</span>
                    <span className="bold">{formatPrice(p.price)}</span>
                  </span>
                </button>

                {open && editForm && (
                  <div className="stack gap-10" style={{ borderTop: '1.5px dashed var(--line)', margin: '0 14px', padding: '12px 0 14px' }}>
                    <ProductFields form={editForm} setForm={setEditForm} />
                    {formError && <span className="error-text" role="alert">{formError}</span>}
                    <div className="row gap-8">
                      <button type="button" className="btn btn-sm btn-danger-outline" style={{ flex: 1, height: 46 }} disabled={busy} onClick={() => remove(p)}>
                        삭제
                      </button>
                      <button type="button" className="btn btn-primary btn-sm" style={{ flex: 2, height: 46, boxShadow: '0 3px 0 var(--blue-dark)' }} disabled={busy} onClick={() => submitUpdate(p)}>
                        {busy ? '저장 중…' : '저장'}
                      </button>
                    </div>
                    <span className="small muted">종류({p.typeLabel})는 바꿀 수 없어요. 주문으로 재고가 0이 되면 서버가 자동으로 품절로 바꿔요.</span>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
      <AdminTabBar />
    </div>
  );
}

// 등록·수정이 같이 쓰는 입력칸 묶음 (withType 이면 종류 선택칸도 보여요)
function ProductFields({ form, setForm, withType }) {
  // 입력칸 하나가 바뀌면 그 칸만 바꿔 끼워요.
  const set = (key) => (e) => setForm({ ...form, [key]: e.target.value });
  // 숫자 칸: 숫자가 아닌 글자는 지워요.
  const setNumber = (key) => (e) => setForm({ ...form, [key]: e.target.value.replace(/\D/g, '') });

  return (
    <>
      <div className="field">
        <label>상품명</label>
        <input className="input" value={form.name} maxLength={NAME_MAX} onChange={set('name')} placeholder="예) 종일권" />
      </div>
      {withType && (
        <div className="field">
          <label>종류</label>
          <select className="input" value={form.type} onChange={set('type')}>
            {TYPE_OPTIONS.map((t) => <option key={t.code} value={t.code}>{t.label}</option>)}
          </select>
        </div>
      )}
      <div className="row gap-8">
        <div className="field" style={{ flex: 1, minWidth: 0 }}>
          <label>가격(원)</label>
          <input className="input" inputMode="numeric" value={form.price} onChange={setNumber('price')} placeholder="50000" />
        </div>
        <div className="field" style={{ flex: 1, minWidth: 0 }}>
          <label>재고(장)</label>
          <input className="input" inputMode="numeric" value={form.stock} onChange={setNumber('stock')} placeholder="1000" />
        </div>
      </div>
      <div className="field">
        <label>설명 (구매 화면에서 상품 이름 아래 보여요)</label>
        <input className="input" value={form.description} maxLength={DESCRIPTION_MAX} onChange={set('description')} placeholder="예) 15:00 ~ 폐장" />
      </div>
      <div className="field">
        <label>판매 상태</label>
        <select className="input" value={form.status} onChange={set('status')}>
          {STATUS_OPTIONS.map((s) => <option key={s.code} value={s.code}>{s.label}</option>)}
        </select>
      </div>
    </>
  );
}
