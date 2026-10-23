import { useEffect, useState } from 'react';
import { Button } from 'primereact/button';
import { Dialog } from 'primereact/dialog';
import { Dropdown } from 'primereact/dropdown';
import { InputNumber } from 'primereact/inputnumber';
import { InputSwitch } from 'primereact/inputswitch';
import { InputText } from 'primereact/inputtext';
import { InputTextarea } from 'primereact/inputtextarea';
import { approveFeeSchedule, feeProposalFor, listFeeSchedules, proposeFeeSchedule, rejectFeeSchedule } from '../api';
import { useLoad, useSession } from '../state/session';
import { now as clockNow } from '../state/meta';
import { ErrorNote, Loading, Pill } from '../components/ui';
import type { FeeBase, FeeComponent, FeeProposalInput, FeeSchedule, FeeScheduleStatus, SourceCode } from '../data/types';
import { fmtDateTime, fmtVnd, type Tone } from '../lib/format';
import { BASE_LABEL, describe, effectiveText, feeOf, inForce } from '../lib/fees';

const STATUS: Record<FeeScheduleStatus, { label: string; tone: Tone; icon: string }> = {
  APPROVED: { label: 'Đã duyệt', tone: 'good', icon: 'pi pi-check' },
  PROPOSED: { label: 'Chờ duyệt', tone: 'pending', icon: 'pi pi-hourglass' },
  REJECTED: { label: 'Từ chối', tone: 'neutral', icon: 'pi pi-times' },
};

const isoDay = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const BASES = (Object.keys(BASE_LABEL) as FeeBase[]).map((b) => ({ value: b, label: BASE_LABEL[b] }));
const KINDS = [{ value: 'FIXED', label: 'Số tiền cố định' }, { value: 'PERCENT', label: 'Phần trăm' }];
type CondKind = 'always' | 'remote' | 'above' | 'atMost';
const CONDS: { value: CondKind; label: string }[] = [
  { value: 'always', label: 'Luôn áp dụng' },
  { value: 'remote', label: 'Nếu vùng xa' },
  { value: 'above', label: 'Nếu lớn hơn' },
  { value: 'atMost', label: 'Nếu không quá' },
];

const condKind = (c: FeeComponent): CondKind =>
  c.when?.remoteArea ? 'remote' : c.when?.above != null ? 'above' : c.when?.atMost != null ? 'atMost' : 'always';

function setCond(c: FeeComponent, kind: CondKind, base?: FeeBase, value?: number | null): FeeComponent {
  const b = base ?? c.when?.base ?? 'ORDER_AMOUNT';
  const v = value === undefined ? (c.when?.above ?? c.when?.atMost ?? 0) : value;
  if (kind === 'always') return { ...c, when: null };
  if (kind === 'remote') return { ...c, when: { remoteArea: true } };
  return { ...c, when: kind === 'above' ? { base: b, above: v } : { base: b, atMost: v } };
}

/** The proposal editor: effective date, the components one per row, a required note, and a fee preview. */
function FeeEditor({ source, initial, evidence, current, busy, onCancel, onSubmit }: {
  source: SourceCode;
  initial: FeeProposalInput;
  evidence?: string[];
  current?: FeeSchedule;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: FeeProposalInput) => void;
}) {
  const [input, setInput] = useState<FeeProposalInput>(initial);
  const [sample, setSample] = useState<number>(source === 'GHN' ? 2_000_000 : 450_000);
  const [remote, setRemote] = useState(false);
  const rows = input.components;
  const setRow = (i: number, c: FeeComponent) => setInput({ ...input, components: rows.map((x, j) => (j === i ? c : x)) });
  const x = { cod: sample, amount: sample, gross: sample, remote };
  const valid = !!input.effectiveFrom && input.note.trim() && rows.length > 0 && rows.every((c) => c.label.trim());

  return (
    <div className="stack" style={{ gap: 14 }}>
      {evidence && evidence.length > 0 && (
        <div className="notice tone-info">
          <i className="pi pi-sparkles" aria-hidden />
          <span>
            Điền sẵn từ các ngoại lệ sai phí giống nhau. Kiểm tra với thông báo của đối tác trước khi gửi.
            {evidence.map((t) => <span key={t} style={{ display: 'block' }}>· {t}</span>)}
          </span>
        </div>
      )}
      <div className="row" style={{ gap: 12, alignItems: 'end' }}>
        <div>
          <label className="label" htmlFor="fee-from" style={{ display: 'block', marginBottom: 6 }}>Áp dụng từ ngày (theo ngày giao dịch)</label>
          <InputText id="fee-from" type="date" value={input.effectiveFrom} onChange={(e) => setInput({ ...input, effectiveFrom: e.target.value })} />
        </div>
      </div>
      <div className="table-wrap">
        <table className="mini-table" style={{ minWidth: 860 }}>
          <thead>
            <tr>
              <th>Khoản phí</th>
              <th>Cách tính</th>
              <th>Điều kiện</th>
              <th aria-label="Xoá" />
            </tr>
          </thead>
          <tbody>
            {rows.map((c, i) => {
              const ck = condKind(c);
              return (
                <tr key={i}>
                  <td><InputText value={c.label} onChange={(e) => setRow(i, { ...c, label: e.target.value })} aria-label="Tên khoản phí" style={{ width: '100%' }} /></td>
                  <td>
                    <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                      <Dropdown value={c.kind} options={KINDS} onChange={(e) => setRow(i, e.value === 'FIXED'
                        ? { label: c.label, kind: 'FIXED', amount: c.amount ?? 0, when: c.when }
                        : { label: c.label, kind: 'PERCENT', rate: c.rate ?? 1, base: c.base ?? 'ORDER_AMOUNT', when: c.when })} aria-label="Loại phí" />
                      {c.kind === 'FIXED' ? (
                        <InputNumber value={c.amount ?? 0} onValueChange={(e) => setRow(i, { ...c, amount: e.value ?? 0 })} locale="vi-VN" suffix=" ₫" min={0} aria-label="Số tiền" inputStyle={{ width: 120 }} />
                      ) : (
                        <>
                          <InputNumber value={c.rate ?? 0} onValueChange={(e) => setRow(i, { ...c, rate: e.value ?? 0 })} locale="vi-VN" suffix="%" min={0} max={100} maxFractionDigits={2} aria-label="Tỷ lệ" inputStyle={{ width: 80 }} />
                          <Dropdown value={c.base} options={BASES} onChange={(e) => setRow(i, { ...c, base: e.value })} aria-label="Tính trên" />
                          <InputNumber value={c.min ?? null} onValueChange={(e) => setRow(i, { ...c, min: e.value ?? null })} locale="vi-VN" prefix="tối thiểu " suffix=" ₫" min={0} aria-label="Tối thiểu" inputStyle={{ width: 150 }} />
                        </>
                      )}
                    </div>
                  </td>
                  <td>
                    <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                      <Dropdown value={ck} options={CONDS} onChange={(e) => setRow(i, setCond(c, e.value))} aria-label="Điều kiện" />
                      {(ck === 'above' || ck === 'atMost') && (
                        <>
                          <Dropdown value={c.when?.base ?? 'ORDER_AMOUNT'} options={BASES} onChange={(e) => setRow(i, setCond(c, ck, e.value))} aria-label="So sánh" />
                          <InputNumber value={c.when?.above ?? c.when?.atMost ?? 0} onValueChange={(e) => setRow(i, setCond(c, ck, undefined, e.value ?? 0))} locale="vi-VN" suffix=" ₫" min={0} aria-label="Ngưỡng" inputStyle={{ width: 130 }} />
                        </>
                      )}
                    </div>
                  </td>
                  <td><Button icon="pi pi-trash" text severity="secondary" aria-label={`Xoá ${c.label}`} onClick={() => setInput({ ...input, components: rows.filter((_, j) => j !== i) })} /></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <div className="row">
        <Button label="Thêm khoản phí" icon="pi pi-plus" text onClick={() => setInput({ ...input, components: [...rows, { label: '', kind: 'FIXED', amount: 0 }] })} />
      </div>
      <div className="small ink-2">{describe(rows) || 'Chưa có khoản phí nào'}</div>
      <div className="row" style={{ gap: 10 }}>
        <span className="label">Thử với đơn</span>
        <InputNumber value={sample} onValueChange={(e) => setSample(e.value ?? 0)} locale="vi-VN" suffix=" ₫" min={0} aria-label="Giá trị đơn thử" inputStyle={{ width: 140 }} />
        <span className="small">vùng xa</span>
        <InputSwitch checked={remote} onChange={(e) => setRemote(!!e.value)} aria-label="Đơn vùng xa" />
        <span className="small">
          Phí theo đề xuất <strong className="num">{fmtVnd(feeOf(rows, x))}</strong>
          {current && <> · theo phiên bản {current.versionNo} đang áp dụng <span className="num">{fmtVnd(feeOf(current.components, x))}</span></>}
        </span>
      </div>
      <div>
        <label className="label" htmlFor="fee-note" style={{ display: 'block', marginBottom: 6 }}>Lý do (bắt buộc)</label>
        <InputTextarea id="fee-note" value={input.note} onChange={(e) => setInput({ ...input, note: e.target.value })} rows={3} autoResize maxLength={500}
          style={{ width: '100%' }} placeholder="Ví dụ: email GHN ngày 12/10 báo thu thêm 2.000 ₫ với đơn COD trên 1.500.000 ₫ từ 13/10" />
      </div>
      <div className="row" style={{ justifyContent: 'flex-end' }}>
        <Button label="Huỷ" text onClick={onCancel} />
        <Button label="Gửi đề xuất" icon="pi pi-send" loading={busy} disabled={!valid} onClick={() => onSubmit({ ...input, note: input.note.trim() })} />
      </div>
    </div>
  );
}

/** "Biểu phí" tab of a source: the versions, pending proposals, and the editor to propose a new one. */
export default function SourceFees({ source, proposeFrom, onDraftOpened }: { source: SourceCode; proposeFrom: string | null; onDraftOpened: () => void }) {
  const { user, notify } = useSession();
  const res = useLoad(() => listFeeSchedules(source), [source]);
  const [editor, setEditor] = useState<{ input: FeeProposalInput; evidence?: string[] } | null>(null);
  const [decision, setDecision] = useState<{ schedule: FeeSchedule; approve: boolean } | null>(null);
  const [decisionNote, setDecisionNote] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!proposeFrom) return;
    feeProposalFor(proposeFrom)
      .then((d) => setEditor({ input: { effectiveFrom: d.effectiveFrom, components: d.components, note: d.note, fromException: d.fromException }, evidence: d.evidence }))
      .catch((e) => notify({ severity: 'warn', summary: 'Không suy ra được biểu phí mới', detail: (e as Error).message, life: 9000 }))
      .finally(onDraftOpened);
  }, [proposeFrom]);

  if (res.error) return <ErrorNote message={res.error} />;
  if (!res.data) return <Loading rows={4} />;
  const list = res.data;
  const current = inForce(list, isoDay(clockNow()));
  const canDecide = (s: FeeSchedule) => s.status === 'PROPOSED' && (user.role === 'APPROVER' || user.role === 'ADMIN') && s.proposedBy !== user.id;

  const openNew = () => setEditor({
    input: { effectiveFrom: isoDay(clockNow()), components: (current?.components ?? []).map((c) => ({ ...c })), note: '' },
  });

  const submit = async (input: FeeProposalInput) => {
    setBusy(true);
    try {
      await proposeFeeSchedule(source, input, user);
      notify({ severity: 'success', summary: 'Đã gửi đề xuất biểu phí', detail: 'Chờ Kế toán trưởng hoặc quản trị duyệt.' });
      setEditor(null);
    } catch (e) {
      notify({ severity: 'error', summary: 'Không gửi được đề xuất', detail: (e as Error).message, life: 7000 });
    } finally {
      setBusy(false);
    }
  };

  const decide = async () => {
    if (!decision) return;
    setBusy(true);
    try {
      const { schedule, approve } = decision;
      const r = approve
        ? await approveFeeSchedule(schedule.id, schedule.version, decisionNote, user)
        : await rejectFeeSchedule(schedule.id, schedule.version, decisionNote, user);
      notify(approve
        ? { severity: 'success', summary: `Đã duyệt phiên bản ${r.versionNo}`, detail: 'feeRevalidationJob đang kiểm tra lại các ngoại lệ sai phí đang mở.' }
        : { severity: 'info', summary: 'Đã từ chối đề xuất' });
      setDecision(null);
    } catch (e) {
      notify({ severity: 'error', summary: 'Không lưu được quyết định', detail: (e as Error).message, life: 7000 });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="stack" style={{ gap: 14 }}>
      {current ? (
        <div className="notice tone-good">
          <i className="pi pi-check-circle" aria-hidden />
          <span>
            <strong>Đang áp dụng: phiên bản {current.versionNo}</strong> · {effectiveText(current)}
            <span style={{ display: 'block' }}>{current.description}</span>
          </span>
        </div>
      ) : (
        <span className="muted">Chưa có biểu phí đã duyệt: giao dịch của nguồn này chưa được kiểm tra phí.</span>
      )}
      <div className="row">
        <span className="small muted" style={{ flex: '1 1 320px' }}>
          Phí kỳ vọng của mỗi giao dịch tính theo phiên bản có hiệu lực vào ngày giao dịch. Ai cũng đề xuất được; Kế toán trưởng hoặc quản trị duyệt,
          không tự duyệt đề xuất của mình. Khi duyệt, feeRevalidationJob đóng các ngoại lệ sai phí mà phiên bản mới giải thích được.
        </span>
        <Button label="Đề xuất phiên bản mới" icon="pi pi-plus" outlined onClick={openNew} />
      </div>
      <div className="table-wrap">
        <table className="mini-table" style={{ minWidth: 860 }}>
          <thead>
            <tr>
              <th>Phiên bản</th>
              <th>Hiệu lực</th>
              <th>Cách tính</th>
              <th>Đề xuất</th>
              <th>Quyết định</th>
            </tr>
          </thead>
          <tbody>
            {list.map((s) => (
              <tr key={s.id}>
                <td>
                  <div className="cell-main">{s.versionNo ? `Phiên bản ${s.versionNo}` : 'Đề xuất'}</div>
                  <Pill tone={STATUS[s.status].tone} icon={STATUS[s.status].icon}>{STATUS[s.status].label}</Pill>
                </td>
                <td className="small">{effectiveText(s)}</td>
                <td className="small" style={{ maxWidth: 360 }}>{s.description}</td>
                <td className="small" style={{ maxWidth: 260 }}>
                  <div>{s.proposedByName ?? 'Dữ liệu ban đầu'} · {fmtDateTime(s.proposedAt)}</div>
                  <div className="muted">{s.note}</div>
                  {s.fromException && <div className="muted mono">từ {s.fromException}</div>}
                </td>
                <td className="small" style={{ maxWidth: 240 }}>
                  {s.status === 'PROPOSED' ? (
                    canDecide(s) ? (
                      <div className="row nowrap-btns" style={{ gap: 6, flexWrap: 'nowrap' }}>
                        <Button label="Duyệt" icon="pi pi-check" size="small" onClick={() => { setDecisionNote(''); setDecision({ schedule: s, approve: true }); }} />
                        <Button label="Từ chối" icon="pi pi-times" size="small" outlined onClick={() => { setDecisionNote(''); setDecision({ schedule: s, approve: false }); }} />
                      </div>
                    ) : (
                      <span className="muted">{s.proposedBy === user.id ? 'Chờ người khác duyệt' : 'Chờ Kế toán trưởng hoặc quản trị duyệt'}</span>
                    )
                  ) : s.decidedAt ? (
                    <>
                      <div>{s.decidedByName ?? 'Dữ liệu ban đầu'} · {fmtDateTime(s.decidedAt)}</div>
                      {s.decisionNote && <div className="muted">{s.decisionNote}</div>}
                    </>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Dialog header={`Đề xuất biểu phí mới · ${source}`} visible={!!editor} onHide={() => setEditor(null)} style={{ width: 'min(1040px, 96vw)' }}>
        {editor && <FeeEditor source={source} initial={editor.input} evidence={editor.evidence} current={current} busy={busy} onCancel={() => setEditor(null)} onSubmit={submit} />}
      </Dialog>

      <Dialog header={decision ? `${decision.approve ? 'Duyệt' : 'Từ chối'} đề xuất biểu phí · ${effectiveText(decision.schedule)}` : ''} visible={!!decision} onHide={() => setDecision(null)}
        style={{ width: 'min(620px, 94vw)' }}
        footer={
          <>
            <Button label="Huỷ" text onClick={() => setDecision(null)} />
            <Button label={decision?.approve ? 'Duyệt' : 'Từ chối'} icon={decision?.approve ? 'pi pi-check' : 'pi pi-times'} loading={busy}
              disabled={!decision?.approve && !decisionNote.trim()} onClick={decide} />
          </>
        }
      >
        {decision && (
          <div className="stack" style={{ gap: 10 }}>
            <span className="small">{decision.schedule.description}</span>
            <span className="small muted">Lý do của người đề xuất: {decision.schedule.note}</span>
            <label className="label" htmlFor="fee-decision">{decision.approve ? 'Ghi chú (không bắt buộc)' : 'Lý do từ chối (bắt buộc)'}</label>
            <InputTextarea id="fee-decision" value={decisionNote} onChange={(e) => setDecisionNote(e.target.value)} rows={3} autoResize maxLength={500} style={{ width: '100%' }} />
          </div>
        )}
      </Dialog>
    </div>
  );
}
