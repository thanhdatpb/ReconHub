import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { DataTable, type DataTableExpandedRows, type DataTableValueArray } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Dropdown } from 'primereact/dropdown';
import { Button } from 'primereact/button';
import { Dialog } from 'primereact/dialog';
import { InputTextarea } from 'primereact/inputtextarea';
import { acknowledgeExecution, explainExecution, listExecutions, restartExecution, runJob } from '../api';
import { failedOrStopped, needsAction } from '../lib/batch';
import { useLoad, useSession } from '../state/session';
import { Card, ErrorNote, Loading, PageHeader, Pill } from '../components/ui';
import { META } from '../state/meta';
import type { BatchStatus, JobExecution, JobName, StepExecution } from '../data/types';
import { BATCH_STATUS_META, fmtDateTime, fmtDuration, fmtNumber } from '../lib/format';

const JOBS: JobName[] = ['fileIngestionJob', 'matchingJob', 'feeValidationJob', 'feeRevalidationJob', 'agingAndReportJob', 'archiveAndPurgeJob'];
/** Jobs that can be started by hand; file ingestion starts when a file arrives. */
const RUNNABLE = ['matchingJob', 'feeValidationJob', 'agingAndReportJob', 'archiveAndPurgeJob'];

function Explain({ exec }: { exec: JobExecution }) {
  const res = useLoad(() => explainExecution(exec.id), [exec.id]);
  if (res.loading || !res.data) return (
    <div className="stack" style={{ gap: 10 }}>
      <span className="ink-2 small"><i className="pi pi-spin pi-spinner" aria-hidden /> AI đang đọc log và các bảng BATCH_STEP_EXECUTION…</span>
      <Loading rows={4} />
    </div>
  );
  const x = res.data;
  return (
    <div className="stack" style={{ gap: 14 }}>
      <div>
        <div className="label" style={{ marginBottom: 4 }}>Nguyên nhân</div>
        <p style={{ margin: 0 }}>{x.cause}</p>
      </div>
      {x.evidence.length > 0 && (
        <div>
          <div className="label" style={{ marginBottom: 4 }}>Căn cứ trong log</div>
          <pre className="log">{x.evidence.join('\n')}</pre>
        </div>
      )}
      {x.steps.length > 0 && (
        <div>
          <div className="label" style={{ marginBottom: 4 }}>Cách xử lý đề xuất</div>
          <ol style={{ margin: 0, paddingLeft: 20, display: 'grid', gap: 4 }}>
            {x.steps.map((s) => <li key={s}>{s}</li>)}
          </ol>
        </div>
      )}
      <span className="small muted">AI chỉ được đọc log và metadata của Spring Batch. AI không tự chạy lại job hay sửa dữ liệu.</span>
    </div>
  );
}

function Steps({ steps }: { steps: StepExecution[] }) {
  return (
    <div className="table-wrap" style={{ padding: '4px 8px 8px 48px' }}>
      <table className="mini-table" style={{ minWidth: 640 }}>
        <thead>
          <tr>
            <th>Bước</th>
            <th>Trạng thái</th>
            <th className="r">Đọc</th>
            <th className="r">Ghi</th>
            <th className="r">Bỏ qua</th>
            <th className="r">Commit</th>
            <th className="r">Thời gian</th>
          </tr>
        </thead>
        <tbody>
          {steps.map((s) => (
            <tr key={s.name}>
              <td>
                <span className="mono">{s.name}</span>
                {s.exitMessage && <div className={`small ${s.status === 'FAILED' ? 'neg' : 'muted'}`}>{s.exitMessage}</div>}
              </td>
              <td><Pill tone={BATCH_STATUS_META[s.status].tone} icon={BATCH_STATUS_META[s.status].icon}>{BATCH_STATUS_META[s.status].label}</Pill></td>
              <td className="r num">{fmtNumber(s.read)}</td>
              <td className="r num">{fmtNumber(s.write)}</td>
              <td className={`r num ${s.skip ? 'neg' : ''}`}>{fmtNumber(s.skip)}</td>
              <td className="r num">{fmtNumber(s.commits)}</td>
              <td className="r num">{s.durationMs ? fmtDuration(s.durationMs) : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Expanded row: how a failed run was dealt with (replacement file, who acknowledged it), then its steps. */
function RunDetail({ x }: { x: JobExecution }) {
  return (
    <div>
      {(x.supersededBy || x.acknowledged) && (
        <div className="stack" style={{ gap: 8, padding: '8px 8px 4px 48px', maxWidth: 760 }}>
          {x.supersededBy && (
            <div className="notice tone-good">
              <i className="pi pi-check-circle" aria-hidden />
              <span>
                Đã có file thay thế <span className="mono">{x.supersededBy.fileName}</span>, nạp thành công lúc {fmtDateTime(x.supersededBy.receivedAt)}.
                Không cần chạy lại lần chạy này; chạy lại sẽ nạp trùng tiền.
              </span>
            </div>
          )}
          {x.acknowledged && (
            <div className="notice tone-info">
              <i className="pi pi-user-edit" aria-hidden />
              <span>
                <strong>{x.acknowledged.byName}</strong> đánh dấu đã xử lý lúc {fmtDateTime(x.acknowledged.at)}.
                <span style={{ display: 'block', whiteSpace: 'pre-wrap' }}>Ghi chú: {x.acknowledged.note}</span>
              </span>
            </div>
          )}
        </div>
      )}
      <Steps steps={x.steps} />
    </div>
  );
}

export default function Batch() {
  const { notify, user } = useSession();
  const [params] = useSearchParams();
  const focus = Number(params.get('focus')) || null;
  const res = useLoad(listExecutions, []);
  const [job, setJob] = useState<JobName | null>(null);
  const [status, setStatus] = useState<BatchStatus | null>(null);
  const [expanded, setExpanded] = useState<DataTableExpandedRows | DataTableValueArray | undefined>(focus ? { [focus]: true } : undefined);
  const [explain, setExplain] = useState<JobExecution | null>(null);
  const [ackFor, setAckFor] = useState<JobExecution | null>(null);
  const [ackNote, setAckNote] = useState('');
  const [acking, setAcking] = useState(false);

  if (res.error) return <ErrorNote message={res.error} />;
  const rows = (res.data ?? []).filter((x) => (!job || x.job === job) && (!status || x.status === status));

  const runNow = async (name: string) => {
    try {
      const r = await runJob(name);
      notify({ severity: 'info', summary: name, detail: r.message });
    } catch (e) {
      notify({ severity: 'error', summary: `Không chạy được ${name}`, detail: (e as Error).message, life: 6000 });
    }
  };

  const restart = async (x: JobExecution) => {
    try {
      const r = await restartExecution(x.id);
      notify({ severity: 'info', summary: `Chạy lại #${x.id}`, detail: r.message });
    } catch (e) {
      notify({ severity: 'error', summary: `Không chạy lại được #${x.id}`, detail: (e as Error).message, life: 6000 });
    }
  };

  const openAck = (x: JobExecution) => {
    setAckNote('');
    setAckFor(x);
  };

  const acknowledge = async () => {
    if (!ackFor || !ackNote.trim()) return;
    setAcking(true);
    try {
      await acknowledgeExecution(ackFor.id, ackNote, user);
      notify({ severity: 'success', summary: `Đã đánh dấu #${ackFor.id} đã xử lý`, detail: 'Tổng quan không còn cảnh báo lần chạy này.' });
      setExpanded((cur) => ({ ...(cur && !Array.isArray(cur) ? cur : {}), [ackFor.id]: true }));
      setAckFor(null);
    } catch (e) {
      notify({ severity: 'error', summary: `Không đánh dấu được #${ackFor.id}`, detail: (e as Error).message, life: 6000 });
    } finally {
      setAcking(false);
    }
  };

  return (
    <>
      <PageHeader title="Theo dõi batch" sub="Các job Spring Batch: nạp file, khớp, kiểm tra phí, báo cáo tuổi nợ, dọn dữ liệu. Lấy từ các bảng BATCH_JOB_EXECUTION và BATCH_STEP_EXECUTION." />
      <Card title="Lịch chạy" flush>
        <div className="schedule">
          {META.batchSchedule.map((s) => (
            <div key={s.job}>
              <div className="mono small cell-main">{s.job}</div>
              <div className="small ink-2">{s.human}</div>
              <div className="small muted mono">{s.cron}</div>
              {RUNNABLE.includes(s.job) && (
                <Button label="Chạy ngay" icon="pi pi-play" size="small" text style={{ padding: '2px 0', marginTop: 4 }} onClick={() => runNow(s.job)} />
              )}
            </div>
          ))}
        </div>
      </Card>
      <div className="filters" style={{ marginTop: 16 }}>
        <Dropdown inputId="batch-job" value={job} onChange={(e) => setJob(e.value)} options={JOBS} placeholder="Mọi job" showClear />
        <Dropdown inputId="batch-status" value={status} onChange={(e) => setStatus(e.value)} options={(Object.keys(BATCH_STATUS_META) as BatchStatus[]).map((s) => ({ value: s, label: BATCH_STATUS_META[s].label }))} optionLabel="label" optionValue="value" placeholder="Mọi trạng thái" showClear />
      </div>
      <Card flush>
        {!res.data ? (
          <div style={{ padding: 16 }}><Loading rows={6} /></div>
        ) : (
          <DataTable value={rows} dataKey="id" size="small" expandedRows={expanded} onRowToggle={(e) => setExpanded(e.data)} rowExpansionTemplate={(x: JobExecution) => <RunDetail x={x} />}
            paginator rows={15} scrollable paginatorTemplate="CurrentPageReport PrevPageLink NextPageLink" currentPageReportTemplate="{first}–{last} / {totalRecords}" tableStyle={{ minWidth: '72rem' }}
            rowClassName={(x: JobExecution) => (needsAction(x) ? 'row-overdue' : '')}>
            <Column expander style={{ width: '3rem' }} />
            <Column header="Lần chạy" body={(x: JobExecution) => <span className="mono">#{x.id}</span>} />
            <Column header="Job" body={(x: JobExecution) => (
              <div style={{ minWidth: 0 }}>
                <div className="mono cell-main">{x.job}</div>
                <div className="cell-sub mono">{Object.entries(x.params).map(([k, v]) => `${k}=${v}`).join(' ')}</div>
              </div>
            )} />
            <Column header="Bắt đầu" body={(x: JobExecution) => <span className="small">{fmtDateTime(x.startTime)}</span>} />
            <Column header="Thời lượng" body={(x: JobExecution) => <span className="small num">{x.endTime ? fmtDuration(x.endTime.getTime() - x.startTime.getTime()) : 'đang chạy'}</span>} />
            <Column header="Đọc / ghi / bỏ qua" align="right" body={(x: JobExecution) => {
              const r = x.steps.reduce((s, st) => s + st.read, 0);
              const w = x.steps.reduce((s, st) => s + st.write, 0);
              const k = x.steps.reduce((s, st) => s + st.skip, 0);
              return <span className="num small">{fmtNumber(r)} / {fmtNumber(w)} / <span className={k ? 'neg' : ''}>{fmtNumber(k)}</span></span>;
            }} />
            <Column header="Trạng thái" body={(x: JobExecution) => (
              <div>
                <Pill tone={BATCH_STATUS_META[x.status].tone} icon={BATCH_STATUS_META[x.status].icon}>{BATCH_STATUS_META[x.status].label}</Pill>
                {x.restartedAs && <div className="small muted">Đã chạy lại → #{x.restartedAs}</div>}
                {x.supersededBy && <div className="small muted">Đã có file thay thế <span className="mono">{x.supersededBy.fileName}</span></div>}
                {x.acknowledged && <div className="small muted">Đã xử lý · {x.acknowledged.byName}</div>}
              </div>
            )} />
            <Column style={{ minWidth: 380 }} body={(x: JobExecution) => !failedOrStopped(x) ? null : (
              <div className="row nowrap-btns" style={{ flexWrap: 'nowrap', justifyContent: 'flex-end' }}>
                <Button label="AI giải thích" icon="pi pi-sparkles" size="small" text onClick={() => setExplain(x)} />
                {needsAction(x) && <Button label="Đánh dấu đã xử lý" icon="pi pi-check" size="small" text onClick={() => openAck(x)} />}
                {!x.restartedAs && !x.supersededBy && <Button label="Chạy lại" icon="pi pi-replay" size="small" outlined onClick={() => restart(x)} />}
              </div>
            )} />
          </DataTable>
        )}
        {res.data && rows.some((x) => x.status === 'FAILED' && x.exitMessage) && (
          <div style={{ padding: '10px 16px 14px' }} className="small muted">
            Lỗi gần nhất: <span className="mono">{rows.find((x) => x.status === 'FAILED')?.exitMessage}</span>
          </div>
        )}
      </Card>
      <Dialog header={explain ? `Vì sao #${explain.id} ${explain.job} lỗi?` : ''} visible={!!explain} onHide={() => setExplain(null)} style={{ width: 'min(760px, 96vw)' }}>
        {explain && <Explain exec={explain} />}
      </Dialog>
      <Dialog header={ackFor ? `Đánh dấu #${ackFor.id} ${ackFor.job} đã xử lý` : ''} visible={!!ackFor} onHide={() => setAckFor(null)} style={{ width: 'min(560px, 94vw)' }}
        footer={
          <>
            <Button label="Huỷ" text onClick={() => setAckFor(null)} />
            <Button label="Đánh dấu đã xử lý" icon="pi pi-check" loading={acking} disabled={!ackNote.trim()} onClick={acknowledge} />
          </>
        }
      >
        <div className="stack" style={{ gap: 8 }}>
          <span className="small ink-2">
            Lần chạy vẫn hiện ở đây kèm tên bạn, thời điểm và ghi chú; Tổng quan không cảnh báo lần chạy này nữa.
          </span>
          <label className="label" htmlFor="ack-note">Ghi chú (bắt buộc)</label>
          <InputTextarea id="ack-note" value={ackNote} onChange={(ev) => setAckNote(ev.target.value)} rows={4} maxLength={500} autoResize
            style={{ width: '100%' }} placeholder="Đã xử lý thế nào, ví dụ: đã gọi GHTK, đối tác gửi lại file qua email và đã tải lên" />
          <span className="small muted">{ackNote.trim().length}/500</span>
        </div>
      </Dialog>
    </>
  );
}
