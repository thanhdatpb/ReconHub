import { useState, type ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button } from 'primereact/button';
import { Dialog } from 'primereact/dialog';
import { Dropdown } from 'primereact/dropdown';
import { InputText } from 'primereact/inputtext';
import { InputTextarea } from 'primereact/inputtextarea';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import {
  addComment,
  assignExceptions,
  decideWriteOff,
  getException,
  markDuplicate,
  matchException,
  requestWriteOff,
  searchOrders,
  sendClaim,
} from '../api';
import { useLoad, useSession } from '../state/session';
import { Avatar, Card, ErrorNote, Loading, Meter, Money, PageHeader, Pill, SourceBadge } from '../components/ui';
import { META } from '../state/meta';
import type { AiCandidate, ExceptionAction, Order, PaymentLine, ReconException } from '../data/types';
import { ACTION_LABEL, SOURCE_LABEL, STATUS_META, TYPE_META, fmtDateTime, fmtSla, fmtVnd } from '../lib/format';

type DialogState =
  | null
  | { kind: 'match'; codes: string[]; amount: number; viaAi: boolean }
  | { kind: 'search' }
  | { kind: 'writeoff' }
  | { kind: 'claim' }
  | { kind: 'duplicate' }
  | { kind: 'decide'; approve: boolean };

const WRITE_OFF_REASONS = ['Phí đối tác thay đổi', 'Đối tác trả thiếu', 'Chênh lệch làm tròn', 'Khách trả thiếu', 'Không thu hồi được'];

function OrderRecord({ o }: { o: Order }) {
  return (
    <div className="record">
      <dl className="kv">
        <dt>Mã đơn</dt>
        <dd className="mono">{o.code}</dd>
        <dt>Kênh bán</dt>
        <dd>{o.channel === 'WEBSITE' ? 'Website' : o.channel === 'SHOPEE' ? 'Shopee' : 'TikTok Shop'}</dd>
        <dt>Khách hàng</dt>
        <dd>
          {o.customer} <span className="muted">· {o.phone}</span>
        </dd>
        <dt>Giá trị đơn</dt>
        <dd><Money value={o.amount} /></dd>
        {o.codAmount > 0 && (
          <>
            <dt>Thu hộ (COD)</dt>
            <dd><Money value={o.codAmount} /></dd>
          </>
        )}
        {o.trackingCode && (
          <>
            <dt>Mã vận đơn</dt>
            <dd className="mono">{o.trackingCode}</dd>
          </>
        )}
        <dt>Đặt lúc</dt>
        <dd>{fmtDateTime(o.createdAt)}</dd>
        {o.deliveredAt && (
          <>
            <dt>Giao lúc</dt>
            <dd>{fmtDateTime(o.deliveredAt)}</dd>
          </>
        )}
      </dl>
    </div>
  );
}

function PaymentRecord({ p, note }: { p: PaymentLine; note?: string }) {
  return (
    <div className="record">
      {note && <span className="pill tone-neutral" style={{ justifySelf: 'start' }}>{note}</span>}
      <dl className="kv">
        <dt>Mã tham chiếu</dt>
        <dd className="mono">{p.externalRef}</dd>
        <dt>Số tiền nhận</dt>
        <dd><Money value={p.amount} /></dd>
        {p.fee > 0 && (
          <>
            <dt>Phí bị trừ</dt>
            <dd><Money value={p.fee} /></dd>
          </>
        )}
        <dt>Thời gian</dt>
        <dd>{fmtDateTime(p.txnDate)}</dd>
        {p.description && (
          <>
            <dt>Nội dung</dt>
            <dd className="mono">"{p.description}"</dd>
          </>
        )}
        <dt>File</dt>
        <dd className="mono small">{p.fileName}</dd>
      </dl>
    </div>
  );
}

function DiffBox({ e }: { e: ReconException }) {
  const cells: [string, ReactNode][] = [];
  switch (e.type) {
    case 'UNMATCHED_PAYMENT':
      cells.push(['Tiền nhận', <Money value={e.payments[0].amount} />], ['Đã gán cho đơn', <Money value={0} />], ['Chưa xác định', <Money value={e.diffAmount} />]);
      break;
    case 'AMOUNT_DIFF':
      cells.push(['Phải nhận (sau phí)', <Money value={e.meta.expectedAmount ?? 0} />], ['Thực nhận', <Money value={e.meta.paidAmount ?? 0} />], ['Chênh lệch', <Money value={e.diffAmount} signed tone />]);
      break;
    case 'FEE_DIFF':
      cells.push(['Phí theo biểu phí', <Money value={e.meta.expectedFee ?? 0} />], ['Phí thực trừ', <Money value={e.meta.actualFee ?? 0} />], ['Chênh lệch', <Money value={e.diffAmount} signed tone />]);
      break;
    case 'UNPAID_ORDER':
      cells.push(['Tiền thu hộ', <Money value={e.orders[0].codAmount} />], ['Đã nhận', <Money value={0} />], ['Từ khi giao', <span className="num">{e.meta.daysSinceDelivery} ngày</span>]);
      break;
    case 'DUPLICATE':
      cells.push(['Số dòng trùng', <span className="num">2</span>], ['Tiền bị tính hai lần', <Money value={e.diffAmount} />]);
      break;
  }
  return (
    <>
      <div className="diff-box">
        {cells.map(([k, v]) => (
          <div key={k}>
            <div className="label">{k}</div>
            <div className="v">{v}</div>
          </div>
        ))}
      </div>
      {e.type === 'FEE_DIFF' && e.meta.feeRule && <p className="small muted" style={{ margin: '10px 0 0' }}>Biểu phí đang áp dụng: {e.meta.feeRule}</p>}
    </>
  );
}

function Candidate({ c, top, canAct, onPick }: { c: AiCandidate; top: boolean; canAct: boolean; onPick: () => void }) {
  return (
    <div className={`cand ${top ? 'top' : ''}`}>
      <div className="row">
        <span className="mono cell-main">{c.orderCodes.join(' + ')}</span>
        <Money value={c.amount} />
        <span className="spacer" />
        <span className={`ai-chip ${c.score < 0.7 ? 'low' : ''}`}>{Math.round(c.score * 100)}% phù hợp</span>
      </div>
      <ul className="reasons">
        {c.reasons.map((r) => (
          <li key={r.text}>
            <i className={r.ok ? 'pi pi-check' : 'pi pi-times'} aria-label={r.ok ? 'Khớp' : 'Không khớp'} />
            <span>{r.text}</span>
          </li>
        ))}
      </ul>
      {canAct && (
        <div>
          <Button label={c.orderCodes.length > 1 ? 'Khớp với các đơn này' : 'Khớp với đơn này'} size="small" outlined={!top} icon="pi pi-check" onClick={onPick} />
        </div>
      )}
    </div>
  );
}

function dotClass(a: ExceptionAction) {
  if (a.kind === 'AI_SUGGESTED') return 'ai';
  if (a.kind === 'MATCHED' || a.kind === 'APPROVED' || a.kind === 'MARKED_DUPLICATE' || a.kind === 'FEE_RULE_UPDATED') return 'done';
  if (a.kind === 'WRITE_OFF_REQUESTED' || a.kind === 'REJECTED') return 'warn';
  return '';
}

function OrderSearch({ onPick }: { onPick: (o: Order) => void }) {
  const [q, setQ] = useState('');
  const res = useLoad(() => searchOrders(q), [q]);
  return (
    <div className="stack" style={{ gap: 10 }}>
      <InputText id="order-search" autoFocus value={q} onChange={(e) => setQ(e.target.value)} placeholder="Mã đơn, tên khách, mã vận đơn hoặc số tiền (vd 485000)" />
      <DataTable value={res.data ?? []} loading={res.loading} size="small" scrollable scrollHeight="320px" emptyMessage="Không tìm thấy đơn phù hợp." dataKey="code">
        <Column header="Mã đơn" body={(o: Order) => <span className="mono">{o.code}</span>} />
        <Column header="Khách" field="customer" />
        <Column header="Số tiền" align="right" body={(o: Order) => <Money value={o.amount} />} />
        <Column header="Đặt lúc" body={(o: Order) => <span className="small">{fmtDateTime(o.createdAt)}</span>} />
        <Column body={(o: Order) => <Button label="Chọn" size="small" text onClick={() => onPick(o)} />} />
      </DataTable>
    </div>
  );
}

export default function ExceptionDetail() {
  const { id } = useParams();
  const { user, notify } = useSession();
  const nav = useNavigate();
  const { data: e, loading, error } = useLoad(() => getException(id!), [id]);
  const [dialog, setDialog] = useState<DialogState>(null);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState(WRITE_OFF_REASONS[1]);
  const [note, setNote] = useState('');
  const [comment, setComment] = useState('');

  if (error) return <ErrorNote message={error} />;
  if (loading && !e) return <Loading rows={8} />;
  if (!e) return null;

  const open = e.status === 'OPEN' || e.status === 'IN_REVIEW';
  const sla = fmtSla(e.slaDueAt);
  const tm = TYPE_META[e.type];
  const rec = e.ai?.recommended;
  const top = e.ai?.candidates[0];
  const order = e.orders[0];

  const run = async (fn: () => Promise<unknown>, success: string) => {
    setBusy(true);
    try {
      await fn();
      notify({ severity: 'success', summary: success });
      setDialog(null);
      setNote('');
    } catch (err) {
      notify({ severity: 'error', summary: 'Không thực hiện được', detail: (err as Error).message, life: 6000 });
    } finally {
      setBusy(false);
    }
  };

  const openClaim = () => {
    const ref = order ? `đơn ${order.code}${order.trackingCode ? ` (mã vận đơn ${order.trackingCode})` : ''}` : `giao dịch ${e.payments[0]?.externalRef}`;
    setNote(
      e.type === 'UNMATCHED_PAYMENT'
        ? `Gọi khách xác nhận khoản chuyển ${fmtVnd(e.diffAmount)} ngày ${fmtDateTime(e.payments[0].txnDate)} là cho đơn nào.`
        : `Kính gửi ${SOURCE_LABEL[e.source]}, đề nghị kiểm tra ${ref}: chênh lệch ${fmtVnd(Math.abs(e.diffAmount))} so với số phải trả. Vui lòng phản hồi trong kỳ đối soát tới.`,
    );
    setDialog({ kind: 'claim' });
  };
  const openWriteOff = () => {
    setReason(e.type === 'FEE_DIFF' ? WRITE_OFF_REASONS[0] : e.type === 'UNPAID_ORDER' ? WRITE_OFF_REASONS[4] : Math.abs(e.diffAmount) < 5000 ? WRITE_OFF_REASONS[2] : WRITE_OFF_REASONS[1]);
    setNote('');
    setDialog({ kind: 'writeoff' });
  };

  const actionButtons: ReactNode[] = [];
  if (open) {
    if (e.type === 'UNMATCHED_PAYMENT') {
      if (top) actionButtons.push(<Button key="ai" label={`Khớp theo gợi ý AI (${top.orderCodes.join(' + ')})`} icon="pi pi-sparkles" onClick={() => setDialog({ kind: 'match', codes: top.orderCodes, amount: top.amount, viaAi: true })} />);
      actionButtons.push(<Button key="search" label="Tìm đơn để khớp thủ công…" icon="pi pi-search" outlined onClick={() => setDialog({ kind: 'search' })} />);
      actionButtons.push(<Button key="claim" label="Chờ xác minh với khách…" icon="pi pi-phone" outlined onClick={openClaim} />);
    }
    if (e.type === 'AMOUNT_DIFF' || e.type === 'FEE_DIFF' || e.type === 'UNPAID_ORDER') {
      const claimFirst = rec === 'CLAIM' || rec === 'UPDATE_FEE_RULE';
      const claim = <Button key="claim" label={`Gửi yêu cầu đối soát cho ${SOURCE_LABEL[e.source]}…`} icon="pi pi-envelope" outlined={!claimFirst} onClick={openClaim} />;
      const wo = <Button key="wo" label="Ghi giảm chênh lệch…" icon="pi pi-minus-circle" outlined={claimFirst} onClick={openWriteOff} />;
      actionButtons.push(...(claimFirst ? [claim, wo] : [wo, claim]));
      if (rec === 'UPDATE_FEE_RULE' && e.type === 'FEE_DIFF') {
        actionButtons.push(<Button key="fee" label="Đề xuất cập nhật biểu phí…" icon="pi pi-sliders-h" text onClick={() => nav(`/sources?code=${e.source}&tab=fees&proposeFrom=${e.id}`)} />);
      } else if (rec === 'UPDATE_FEE_RULE') {
        actionButtons.push(<Button key="fee" label="Mở biểu phí của nguồn này" icon="pi pi-sliders-h" text onClick={() => nav(`/sources?code=${e.source}&tab=fees`)} />);
      }
    }
    if (e.type === 'DUPLICATE') {
      actionButtons.push(<Button key="dup" label="Đánh dấu dòng thứ hai là trùng" icon="pi pi-copy" onClick={() => setDialog({ kind: 'duplicate' })} />);
    }
  }

  const wo = e.writeOff;
  const canApprove = e.status === 'WRITE_OFF_PENDING' && user.role === 'APPROVER' && wo?.requestedById !== user.id;

  return (
    <>
      <PageHeader
        back={{ to: '/exceptions', label: 'Hàng chờ ngoại lệ' }}
        title={
          <span className="row" style={{ gap: 10 }}>
            <span className="mono">{e.id}</span>
            <span style={{ fontWeight: 600 }}>{tm.label}</span>
          </span>
        }
        sub={
          <span className="row" style={{ gap: 10, marginTop: 6 }}>
            <Pill tone={STATUS_META[e.status].tone} icon={STATUS_META[e.status].icon}>{STATUS_META[e.status].label}</Pill>
            <SourceBadge code={e.source} />
            <span>Phát hiện {fmtDateTime(e.createdAt)}</span>
            {open && <span className={sla.overdue ? 'sla-over' : sla.soon ? 'sla-soon' : ''}>· {sla.text} hạn xử lý</span>}
          </span>
        }
      />

      <div className="grid cols-detail">
        <div className="stack">
          <Card title="Hai phía cần khớp" sub="Bên trái là dữ liệu từ hệ thống đơn hàng (OMS), bên phải là dữ liệu tiền về từ đối tác">
            <div className="compare">
              <div className={`side ${e.orders.length ? '' : 'side-empty'}`}>
                <span className="label">Đơn hàng · OMS</span>
                {e.orders.length ? e.orders.map((o) => <OrderRecord key={o.code} o={o} />) : <span className="small">Chưa xác định được đơn hàng.<br />Xem gợi ý của AI bên dưới.</span>}
              </div>
              <div className="compare-mid" aria-hidden>
                <i className="pi pi-arrows-h" style={{ fontSize: 18 }} />
              </div>
              <div className={`side ${e.payments.length ? '' : 'side-empty'}`}>
                <span className="label">Tiền về · {SOURCE_LABEL[e.source]}</span>
                {e.payments.length ? (
                  e.payments.map((p, i) => <PaymentRecord key={p.id} p={p} note={e.type === 'DUPLICATE' ? (i === 0 ? 'Dòng 1 · đã khớp' : 'Dòng 2 · nghi trùng') : undefined} />)
                ) : (
                  <span className="small">Chưa có dòng nào trong các file đối soát của {SOURCE_LABEL[e.source]}.</span>
                )}
              </div>
            </div>
            <DiffBox e={e} />
          </Card>

          {e.ai && (
            <section className="ai-card">
              <div className="card-head">
                <h2 className="card-title row" style={{ gap: 8 }}>
                  <i className="pi pi-sparkles" style={{ color: 'var(--accent)' }} aria-hidden /> Gợi ý của AI
                </h2>
                <div style={{ marginLeft: 'auto', width: 180 }}>
                  <Meter value={e.ai.confidence} bad={0.5} warn={0.75} label={`Tin cậy ${Math.round(e.ai.confidence * 100)}%`} />
                </div>
              </div>
              <div className="card-body stack" style={{ gap: 12 }}>
                <p style={{ margin: 0 }}>{e.ai.summary}</p>
                {e.ai.candidates.length > 0 && (
                  <div className="stack" style={{ gap: 8 }}>
                    {e.ai.candidates.map((c, i) => (
                      <Candidate key={c.orderCodes.join()} c={c} top={i === 0} canAct={open && e.type === 'UNMATCHED_PAYMENT'} onPick={() => setDialog({ kind: 'match', codes: c.orderCodes, amount: c.amount, viaAi: true })} />
                    ))}
                  </div>
                )}
                {e.ai.evidence.length > 0 && (
                  <div>
                    <div className="label" style={{ marginBottom: 4 }}>Căn cứ</div>
                    <ul className="evidence">
                      {e.ai.evidence.map((x) => (
                        <li key={x}>{x}</li>
                      ))}
                    </ul>
                  </div>
                )}
                <p className="small muted" style={{ margin: 0 }}>
                  AI chỉ gợi ý; hệ thống chỉ khớp khi bạn xác nhận. Phản hồi sau {(e.ai.latencyMs / 1000).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} giây · {e.ai.tokens.toLocaleString('vi-VN')} token.
                </p>
              </div>
            </section>
          )}
        </div>

        <div className="stack">
          <Card title="Xử lý">
            <div className="stack" style={{ gap: 14 }}>
              <div>
                <label className="label" htmlFor="assignee" style={{ display: 'block', marginBottom: 6 }}>Người xử lý</label>
                <Dropdown
                  inputId="assignee"
                  value={e.assigneeId}
                  options={[{ id: null, name: 'Chưa giao' }, ...META.users.filter((u) => u.role === 'ACCOUNTANT')]}
                  optionLabel="name"
                  optionValue="id"
                  onChange={(ev) => run(() => assignExceptions([e.id], ev.value, user), 'Đã cập nhật người xử lý')}
                  valueTemplate={() => <Avatar userId={e.assigneeId} showName />}
                  style={{ width: '100%' }}
                  disabled={!open}
                />
              </div>

              {e.status === 'WRITE_OFF_PENDING' && wo && (
                <div className="notice tone-pending" style={{ display: 'grid', gap: 8 }}>
                  <strong className="row" style={{ gap: 8 }}><i className="pi pi-shield" aria-hidden /> Chờ Kế toán trưởng duyệt ghi giảm</strong>
                  <span>
                    {wo.requestedBy} đề nghị ghi giảm <strong className="num">{fmtVnd(wo.amount)}</strong> · {wo.reason}
                    {wo.note ? ` · ${wo.note}` : ''}
                  </span>
                  {canApprove ? (
                    <div className="row">
                      <Button label="Duyệt" icon="pi pi-check" size="small" severity="success" onClick={() => { setNote(''); setDialog({ kind: 'decide', approve: true }); }} />
                      <Button label="Từ chối" icon="pi pi-times" size="small" severity="danger" outlined onClick={() => { setNote(''); setDialog({ kind: 'decide', approve: false }); }} />
                    </div>
                  ) : (
                    <span className="small">
                      {user.role !== 'APPROVER'
                        ? 'Chỉ Kế toán trưởng được duyệt. Để thử luồng duyệt, đổi người dùng sang Lê Hoàng Nam ở góc trên bên phải.'
                        : 'Bạn là người đề nghị nên không thể tự duyệt.'}
                    </span>
                  )}
                </div>
              )}

              {actionButtons.length > 0 && <div className="actions-list">{actionButtons}</div>}

              {!open && e.status !== 'WRITE_OFF_PENDING' && (
                <div className="notice tone-good">
                  <i className="pi pi-check-circle" aria-hidden />
                  <span>{e.actions.filter((a) => ['MATCHED', 'APPROVED', 'MARKED_DUPLICATE', 'FEE_RULE_UPDATED'].includes(a.kind)).slice(-1)[0]?.note ?? 'Đã xử lý'}</span>
                </div>
              )}
              <p className="small muted" style={{ margin: 0 }}>
                Ghi giảm trên {fmtVnd(META.writeOffApprovalThreshold)} cần người thứ hai duyệt. Mọi thao tác đều được ghi vào lịch sử bên dưới. Phiên bản bản ghi: {e.version}.
              </p>
            </div>
          </Card>

          <Card title="Lịch sử xử lý">
            <ol className="timeline">
              {e.actions.map((a, i) => (
                <li key={i}>
                  <span className={`tl-dot ${dotClass(a)}`} aria-hidden />
                  <div>
                    <div className="tl-head">
                      <strong>{ACTION_LABEL[a.kind]}</strong> · {a.actor}
                    </div>
                    {a.note && <div className="tl-note">{a.note}</div>}
                    <div className="tl-time">{fmtDateTime(a.at)}</div>
                  </div>
                </li>
              ))}
            </ol>
            <form
              className="stack"
              style={{ gap: 8, marginTop: 4 }}
              onSubmit={(ev) => {
                ev.preventDefault();
                if (!comment.trim()) return;
                run(() => addComment(e.id, e.version, comment.trim(), user), 'Đã thêm bình luận').then(() => setComment(''));
              }}
            >
              <label className="label" htmlFor="comment">Bình luận</label>
              <InputTextarea id="comment" value={comment} onChange={(ev) => setComment(ev.target.value)} rows={2} autoResize placeholder="Ghi chú cho đồng nghiệp, ví dụ: đã gọi khách lúc 10:15" />
              <div>
                <Button type="submit" label="Gửi" size="small" outlined disabled={!comment.trim() || busy} />
              </div>
            </form>
          </Card>
        </div>
      </div>

      <Dialog header="Xác nhận khớp" visible={dialog?.kind === 'match'} onHide={() => setDialog(null)} style={{ width: 'min(520px, 94vw)' }}
        footer={dialog?.kind === 'match' && (
          <>
            <Button label="Huỷ" text onClick={() => setDialog(null)} />
            <Button label="Xác nhận khớp" icon="pi pi-check" loading={busy} onClick={() => run(() => matchException(e.id, e.version, dialog.codes, dialog.viaAi, user), `Đã khớp ${e.id}`)} />
          </>
        )}
      >
        {dialog?.kind === 'match' && (
          <dl className="props">
            <dt>Giao dịch</dt>
            <dd className="mono">{e.payments[0]?.externalRef ?? '—'}</dd>
            <dt>Đơn hàng</dt>
            <dd className="mono">{dialog.codes.join(', ')}</dd>
            <dt>Số tiền</dt>
            <dd><Money value={dialog.amount} /></dd>
            <dt>Phương thức</dt>
            <dd>{dialog.viaAi ? 'AI gợi ý, người xác nhận' : 'Thủ công'}</dd>
          </dl>
        )}
      </Dialog>

      <Dialog header="Tìm đơn để khớp" visible={dialog?.kind === 'search'} onHide={() => setDialog(null)} style={{ width: 'min(760px, 96vw)' }}>
        {dialog?.kind === 'search' && <OrderSearch onPick={(o) => setDialog({ kind: 'match', codes: [o.code], amount: o.amount, viaAi: false })} />}
      </Dialog>

      <Dialog header="Đề nghị ghi giảm chênh lệch" visible={dialog?.kind === 'writeoff'} onHide={() => setDialog(null)} style={{ width: 'min(520px, 94vw)' }}
        footer={
          <>
            <Button label="Huỷ" text onClick={() => setDialog(null)} />
            <Button label={Math.abs(e.diffAmount) > META.writeOffApprovalThreshold ? 'Gửi đề nghị duyệt' : 'Ghi giảm'} loading={busy}
              onClick={() => run(async () => {
                const r = await requestWriteOff(e.id, e.version, reason, note, user);
                if (!r.autoApproved) notify({ severity: 'info', summary: 'Đã gửi Kế toán trưởng duyệt' });
              }, 'Đã ghi nhận đề nghị ghi giảm')} />
          </>
        }
      >
        <div className="stack" style={{ gap: 12 }}>
          <div className={`notice ${Math.abs(e.diffAmount) > META.writeOffApprovalThreshold ? 'tone-pending' : 'tone-info'}`}>
            <i className="pi pi-info-circle" aria-hidden />
            <span>
              Số tiền ghi giảm: <strong className="num">{fmtVnd(Math.abs(e.diffAmount))}</strong>.{' '}
              {Math.abs(e.diffAmount) > META.writeOffApprovalThreshold ? 'Vượt ngưỡng 50.000 ₫ nên cần Kế toán trưởng duyệt.' : 'Không vượt ngưỡng 50.000 ₫ nên được ghi giảm ngay.'} Bút toán: Nợ 811 / Có 131.
            </span>
          </div>
          <div>
            <label className="label" htmlFor="wo-reason" style={{ display: 'block', marginBottom: 6 }}>Lý do</label>
            <Dropdown inputId="wo-reason" value={reason} options={WRITE_OFF_REASONS} onChange={(ev) => setReason(ev.value)} style={{ width: '100%' }} />
          </div>
          <div>
            <label className="label" htmlFor="wo-note" style={{ display: 'block', marginBottom: 6 }}>Ghi chú</label>
            <InputTextarea id="wo-note" value={note} onChange={(ev) => setNote(ev.target.value)} rows={3} style={{ width: '100%' }} placeholder="Căn cứ ghi giảm, ví dụ: email xác nhận của đối tác ngày 21/10" />
          </div>
        </div>
      </Dialog>

      <Dialog header={e.type === 'UNMATCHED_PAYMENT' ? 'Chờ xác minh với khách' : `Gửi yêu cầu đối soát cho ${SOURCE_LABEL[e.source]}`} visible={dialog?.kind === 'claim'} onHide={() => setDialog(null)} style={{ width: 'min(560px, 94vw)' }}
        footer={
          <>
            <Button label="Huỷ" text onClick={() => setDialog(null)} />
            <Button label="Ghi nhận" icon="pi pi-send" loading={busy} onClick={() => run(() => sendClaim(e.id, e.version, note, user), 'Đã chuyển sang trạng thái chờ phản hồi')} />
          </>
        }
      >
        <div className="stack" style={{ gap: 8 }}>
          <label className="label" htmlFor="claim-note">Nội dung</label>
          <InputTextarea id="claim-note" value={note} onChange={(ev) => setNote(ev.target.value)} rows={5} style={{ width: '100%' }} />
          <span className="small muted">Bản demo chỉ ghi nhận vào lịch sử. Bản thật sẽ gửi email qua hàng đợi JMS và theo dõi phản hồi.</span>
        </div>
      </Dialog>

      <Dialog header="Đánh dấu giao dịch trùng" visible={dialog?.kind === 'duplicate'} onHide={() => setDialog(null)} style={{ width: 'min(520px, 94vw)' }}
        footer={
          <>
            <Button label="Huỷ" text onClick={() => setDialog(null)} />
            <Button label="Đánh dấu trùng" icon="pi pi-copy" loading={busy} onClick={() => run(() => markDuplicate(e.id, e.version, user), 'Đã đánh dấu trùng')} />
          </>
        }
      >
        <p style={{ marginTop: 0 }}>
          Dòng thứ hai (<span className="mono">{e.payments[1]?.fileName}</span>) sẽ bị loại khỏi đối soát. Dòng thứ nhất vẫn giữ kết quả khớp với đơn <span className="mono">{order?.code}</span>.
        </p>
      </Dialog>

      <Dialog header={dialog?.kind === 'decide' && dialog.approve ? 'Duyệt ghi giảm' : 'Từ chối ghi giảm'} visible={dialog?.kind === 'decide'} onHide={() => setDialog(null)} style={{ width: 'min(480px, 94vw)' }}
        footer={dialog?.kind === 'decide' && (
          <>
            <Button label="Huỷ" text onClick={() => setDialog(null)} />
            <Button label={dialog.approve ? 'Duyệt' : 'Từ chối'} severity={dialog.approve ? 'success' : 'danger'} loading={busy}
              onClick={() => run(() => decideWriteOff(e.id, e.version, dialog.approve, note, user), dialog.approve ? 'Đã duyệt ghi giảm' : 'Đã từ chối, ngoại lệ quay lại hàng chờ')} />
          </>
        )}
      >
        <div className="stack" style={{ gap: 8 }}>
          <label className="label" htmlFor="decide-note">Ý kiến</label>
          <InputTextarea id="decide-note" value={note} onChange={(ev) => setNote(ev.target.value)} rows={3} style={{ width: '100%' }} placeholder={dialog?.kind === 'decide' && !dialog.approve ? 'Lý do từ chối' : 'Không bắt buộc'} />
        </div>
      </Dialog>
    </>
  );
}
