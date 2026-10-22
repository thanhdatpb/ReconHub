import { useState } from 'react';
import { Link } from 'react-router-dom';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Button } from 'primereact/button';
import { Dialog } from 'primereact/dialog';
import { Dropdown } from 'primereact/dropdown';
import { MultiSelect } from 'primereact/multiselect';
import { InputText } from 'primereact/inputtext';
import { IconField } from 'primereact/iconfield';
import { InputIcon } from 'primereact/inputicon';
import { getFileErrors, listFiles, uploadFile } from '../api';
import { useLoad, useSession } from '../state/session';
import { Card, ErrorNote, Money, PageHeader, Pill, SourceBadge } from '../components/ui';
import { ACTIVE_SOURCES, SOURCES } from '../data/seed';
import type { FileStatus, ImportFile, SourceCode } from '../data/types';
import { FILE_STATUS_META, SOURCE_LABEL, fmtDateTime, fmtNumber } from '../lib/format';

async function sha256(file: File) {
  try {
    const buf = await file.arrayBuffer();
    const hash = await crypto.subtle.digest('SHA-256', buf);
    return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, '0')).join('');
  } catch {
    return `${file.name}-${file.size}-${file.lastModified}`;
  }
}

function ErrorRows({ file }: { file: ImportFile }) {
  const res = useLoad(() => getFileErrors(file.id), [file.id]);
  return (
    <div className="stack" style={{ gap: 10 }}>
      <p style={{ margin: 0 }} className="ink-2">
        {fmtNumber(file.errorRows)} dòng bị bỏ qua khi nạp <span className="mono">{file.fileName}</span>. Các dòng này không vào đối soát cho tới khi đối tác gửi lại hoặc bạn sửa file và nạp lại.
      </p>
      {file.errorRows > 40 && <span className="small muted">Hiển thị 40 dòng đầu.</span>}
      <DataTable value={res.data ?? []} loading={res.loading} size="small" scrollable scrollHeight="360px" dataKey="row">
        <Column header="Dòng" body={(r) => <span className="num">{r.row}</span>} />
        <Column header="Cột" field="column" />
        <Column header="Giá trị" body={(r) => <span className="mono">{r.value || '(trống)'}</span>} />
        <Column header="Lý do" field="reason" />
      </DataTable>
    </div>
  );
}

export default function Files() {
  const { user, notify } = useSession();
  const files = useLoad(listFiles, []);
  const [sources, setSources] = useState<SourceCode[]>([]);
  const [status, setStatus] = useState<FileStatus | null>(null);
  const [q, setQ] = useState('');
  const [errorsOf, setErrorsOf] = useState<ImportFile | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [upSource, setUpSource] = useState<SourceCode>('SHOPEE');
  const [upFile, setUpFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);

  const rows = (files.data ?? []).filter(
    (f) => (!sources.length || sources.includes(f.source)) && (!status || f.status === status) && (!q || f.fileName.toLowerCase().includes(q.toLowerCase())),
  );

  const submit = async () => {
    if (!upFile) return;
    setBusy(true);
    const checksum = await sha256(upFile);
    const f = await uploadFile(upSource, upFile.name, checksum, user);
    setBusy(false);
    setUploadOpen(false);
    setUpFile(null);
    if (f.status === 'DUPLICATE') notify({ severity: 'warn', summary: 'File trùng, không nạp lại', detail: f.message, life: 6000 });
    else notify({ severity: 'info', summary: 'Đã nhận file', detail: `Job nạp file #${f.jobExecutionId} đang chạy` });
  };

  const sourceObj = SOURCES.find((s) => s.code === upSource)!;

  return (
    <>
      <PageHeader
        title="Nhận file"
        sub="File đối soát từ đối tác, nhận qua SFTP hoặc tải lên thủ công. Mỗi file được kiểm tra checksum để không bao giờ nạp trùng."
        actions={<Button label="Tải file lên" icon="pi pi-upload" onClick={() => setUploadOpen(true)} />}
      />
      <div className="filters">
        <IconField iconPosition="left" className="grow">
          <InputIcon className="pi pi-search" />
          <InputText id="file-search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Tên file…" />
        </IconField>
        <MultiSelect inputId="file-source" value={sources} onChange={(e) => setSources(e.value)} options={ACTIVE_SOURCES.map((s) => ({ value: s, label: SOURCE_LABEL[s] }))} optionLabel="label" optionValue="value" placeholder="Mọi nguồn" maxSelectedLabels={2} selectedItemsLabel="{0} nguồn" showClear />
        <Dropdown inputId="file-status" value={status} onChange={(e) => setStatus(e.value)} options={(Object.keys(FILE_STATUS_META) as FileStatus[]).map((s) => ({ value: s, label: FILE_STATUS_META[s].label }))} optionLabel="label" optionValue="value" placeholder="Mọi trạng thái" showClear />
      </div>
      {files.error && <ErrorNote message={files.error} />}
      <Card flush>
        <DataTable value={rows} loading={files.loading && !files.data} dataKey="id" paginator rows={15} size="small" scrollable tableStyle={{ minWidth: '70rem' }}
          paginatorTemplate="CurrentPageReport PrevPageLink NextPageLink" currentPageReportTemplate="{first}–{last} / {totalRecords}" emptyMessage="Không có file nào khớp bộ lọc.">
          <Column header="Tên file" body={(f: ImportFile) => (
            <div style={{ minWidth: 0 }}>
              <div className="mono cell-main">{f.fileName}</div>
              {f.message && <div className="cell-sub" title={f.message}>{f.message}</div>}
            </div>
          )} />
          <Column header="Nguồn" body={(f: ImportFile) => <SourceBadge code={f.source} />} />
          <Column header="Nhận lúc" body={(f: ImportFile) => <span className="small">{fmtDateTime(f.receivedAt)}</span>} />
          <Column header="Số dòng" align="right" body={(f: ImportFile) => <span className="num">{f.totalRows ? fmtNumber(f.totalRows) : '—'}</span>} />
          <Column header="Dòng lỗi" align="right" body={(f: ImportFile) => f.errorRows ? <Button link className="p-0" label={fmtNumber(f.errorRows)} onClick={() => setErrorsOf(f)} aria-label={`Xem ${f.errorRows} dòng lỗi`} /> : <span className="muted num">0</span>} />
          <Column header="Tổng tiền" align="right" body={(f: ImportFile) => f.amountTotal ? <Money value={f.amountTotal} /> : <span className="muted">—</span>} />
          <Column header="Checksum" body={(f: ImportFile) => <span className="mono small muted" title={f.checksum}>{f.checksum.slice(0, 10)}</span>} />
          <Column header="Trạng thái" body={(f: ImportFile) => <Pill tone={FILE_STATUS_META[f.status].tone} icon={FILE_STATUS_META[f.status].icon}>{FILE_STATUS_META[f.status].label}</Pill>} />
          <Column header="Job" body={(f: ImportFile) => f.jobExecutionId ? <Link className="mono small" to={`/batch?focus=${f.jobExecutionId}`}>#{f.jobExecutionId}</Link> : <span className="muted">—</span>} />
        </DataTable>
      </Card>

      <Dialog header={errorsOf ? `Dòng lỗi · ${errorsOf.fileName}` : ''} visible={!!errorsOf} onHide={() => setErrorsOf(null)} style={{ width: 'min(820px, 96vw)' }}>
        {errorsOf && <ErrorRows file={errorsOf} />}
      </Dialog>

      <Dialog header="Tải file đối soát lên" visible={uploadOpen} onHide={() => setUploadOpen(false)} style={{ width: 'min(520px, 94vw)' }}
        footer={
          <>
            <Button label="Huỷ" text onClick={() => setUploadOpen(false)} />
            <Button label="Nạp file" icon="pi pi-upload" loading={busy} disabled={!upFile} onClick={submit} />
          </>
        }
      >
        <div className="stack" style={{ gap: 14 }}>
          <div>
            <label className="label" htmlFor="up-source" style={{ display: 'block', marginBottom: 6 }}>Nguồn</label>
            <Dropdown inputId="up-source" value={upSource} onChange={(e) => setUpSource(e.value)} options={ACTIVE_SOURCES.map((s) => ({ value: s, label: SOURCE_LABEL[s] }))} optionLabel="label" optionValue="value" style={{ width: '100%' }} />
            <div className="small muted" style={{ marginTop: 6 }}>
              Mẫu tên file: <span className="mono">{sourceObj.filePattern}</span> · bảng mã {sourceObj.encoding}
            </div>
          </div>
          <div>
            <label className="label" htmlFor="up-file" style={{ display: 'block', marginBottom: 6 }}>File (.csv, .xlsx)</label>
            <input id="up-file" type="file" accept=".csv,.xlsx,.xls" onChange={(e) => setUpFile(e.target.files?.[0] ?? null)} />
          </div>
          <div className="notice tone-info">
            <i className="pi pi-info-circle" aria-hidden />
            <span>Hệ thống tính SHA-256 của file. Nếu trùng với file đã nạp, file bị từ chối. Thử tải cùng một file hai lần để xem.</span>
          </div>
        </div>
      </Dialog>
    </>
  );
}
