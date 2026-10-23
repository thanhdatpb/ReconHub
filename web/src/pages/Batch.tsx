import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { DataTable, type DataTableExpandedRows, type DataTableValueArray } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Dropdown } from 'primereact/dropdown';
import { Button } from 'primereact/button';
import { Dialog } from 'primereact/dialog';
import { explainExecution, listExecutions, restartExecution, runJob } from '../api';
import { useLoad, useSession } from '../state/session';
import { Card, ErrorNote, Loading, PageHeader, Pill } from '../components/ui';
import { META } from '../state/meta';
import type { BatchStatus, JobExecution, JobName, StepExecution } from '../data/types';
import { BATCH_STATUS_META, fmtDateTime, fmtDuration, fmtNumber } from '../lib/format';

const JOBS: JobName[] = ['fileIngestionJob', 'matchingJob', 'feeValidationJob', 'agingAndReportJob', 'archiveAndPurgeJob'];
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

export default function Batch() {
  const { notify } = useSession();
  const [params] = useSearchParams();
  const focus = Number(params.get('focus')) || null;
  const res = useLoad(listExecutions, []);
  const [job, setJob] = useState<JobName | null>(null);
  const [status, setStatus] = useState<BatchStatus | null>(null);
  const [expanded, setExpanded] = useState<DataTableExpandedRows | DataTableValueArray | undefined>(focus ? { [focus]: true } : undefined);
  const [explain, setExplain] = useState<JobExecution | null>(null);

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
          <DataTable value={rows} dataKey="id" size="small" expandedRows={expanded} onRowToggle={(e) => setExpanded(e.data)} rowExpansionTemplate={(x: JobExecution) => <Steps steps={x.steps} />}
            paginator rows={15} scrollable paginatorTemplate="CurrentPageReport PrevPageLink NextPageLink" currentPageReportTemplate="{first}–{last} / {totalRecords}" tableStyle={{ minWidth: '72rem' }}
            rowClassName={(x: JobExecution) => (x.status === 'FAILED' && !x.restartedAs ? 'row-overdue' : '')}>
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
              </div>
            )} />
            <Column style={{ minWidth: 240 }} body={(x: JobExecution) => x.status !== 'FAILED' ? null : (
              <div className="row nowrap-btns" style={{ flexWrap: 'nowrap', justifyContent: 'flex-end' }}>
                <Button label="AI giải thích" icon="pi pi-sparkles" size="small" text onClick={() => setExplain(x)} />
                {!x.restartedAs && <Button label="Chạy lại" icon="pi pi-replay" size="small" outlined onClick={() => restart(x)} />}
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
    </>
  );
}
