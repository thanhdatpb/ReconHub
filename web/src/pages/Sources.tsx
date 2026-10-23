import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button } from 'primereact/button';
import { Dropdown } from 'primereact/dropdown';
import { TabView, TabPanel } from 'primereact/tabview';
import { listSources, saveMapping, suggestMapping } from '../api';
import { useLoad, useSession } from '../state/session';
import { Card, ErrorNote, Loading, PageHeader, Pill } from '../components/ui';
import type { MappingRow, Source, SourceCode } from '../data/types';
import { FIELD_META, KIND_LABEL, fmtPct } from '../lib/format';
import SourceFees from './SourceFees';

const TAB_INDEX: Record<string, number> = { mapping: 0, fees: 1, schedule: 2 };

function MappingEditor({ source, onSaved }: { source: Source; onSaved: () => void }) {
  const { user, notify } = useSession();
  const [rows, setRows] = useState<MappingRow[]>(source.mapping);
  const [thinking, setThinking] = useState(false);
  const [saving, setSaving] = useState(false);
  useEffect(() => setRows(source.mapping.map((m) => ({ ...m }))), [source]);

  const options = [{ label: '(không có)', value: null }, ...source.headers.map((h) => ({ label: h, value: h }))];
  const missing = rows.filter((r) => FIELD_META[r.field].required && !r.column);
  const preview = source.sampleRows.map((row) => rows.map((m) => (m.column ? row[source.headers.indexOf(m.column)] ?? '' : '')));

  const suggest = async () => {
    setThinking(true);
    const s = await suggestMapping(source.code);
    setRows(s);
    setThinking(false);
    notify({ severity: 'info', summary: 'AI đã đề xuất ánh xạ', detail: 'Kiểm tra các dòng độ tin cậy thấp trước khi lưu.' });
  };

  const save = async () => {
    setSaving(true);
    await saveMapping(source.code, rows);
    setSaving(false);
    notify({
      severity: 'success',
      summary: 'Đã lưu mẫu ánh xạ',
      detail: source.status === 'DRAFT' ? `Nguồn sẽ được kích hoạt sau khi Kế toán trưởng xác nhận (người lưu: ${user.name}).` : 'Áp dụng từ file nhận tiếp theo.',
    });
    onSaved();
  };

  return (
    <div className="stack" style={{ gap: 14 }}>
      <div className="row">
        <span className="ink-2 small" style={{ flex: '1 1 260px' }}>
          Chọn cột trong file của đối tác ứng với từng trường chuẩn. Khi gặp định dạng mới, AI đọc tiêu đề và 3 dòng mẫu để đề xuất. Sau khi lưu, file được đọc bằng mẫu cố định, không gọi AI cho từng dòng.
        </span>
        <Button label="AI gợi ý ánh xạ" icon="pi pi-sparkles" outlined loading={thinking} onClick={suggest} />
      </div>
      <div className="table-wrap">
        <table className="mini-table map-table" style={{ minWidth: 640 }}>
          <thead>
            <tr>
              <th>Trường chuẩn</th>
              <th>Cột trong file</th>
              <th>Giá trị mẫu</th>
              <th>Độ tin cậy</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.field}>
                <td>
                  <div className="cell-main">{FIELD_META[r.field].label}</div>
                  <div className="small muted mono">{r.field}{FIELD_META[r.field].required ? ' · bắt buộc' : ''}</div>
                </td>
                <td>
                  <Dropdown
                    inputId={`map-${r.field}`}
                    value={r.column}
                    options={options}
                    optionLabel="label"
                    optionValue="value"
                    onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, column: e.value, confidence: undefined, aiNote: undefined } : x)))}
                    aria-label={`Cột cho ${FIELD_META[r.field].label}`}
                  />
                </td>
                <td className="mono small">{r.column ? source.sampleRows[0][source.headers.indexOf(r.column)] || '(trống)' : '—'}</td>
                <td style={{ maxWidth: 280 }}>
                  {r.confidence != null ? (
                    <div>
                      <span className={`ai-chip ${r.confidence < 0.8 ? 'conf-low' : ''}`}>
                        <i className={r.confidence < 0.8 ? 'pi pi-exclamation-triangle' : 'pi pi-sparkles'} aria-hidden /> {fmtPct(r.confidence, 0)}
                      </span>
                      {r.aiNote && <div className="small ink-2">{r.aiNote}</div>}
                    </div>
                  ) : (
                    <span className="muted small">{r.column ? 'Chọn tay' : '—'}</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div>
        <div className="label" style={{ marginBottom: 6 }}>Xem trước sau khi ánh xạ</div>
        <div className="table-wrap">
          <table className="mini-table" style={{ minWidth: 640 }}>
            <thead>
              <tr>{rows.map((r) => <th key={r.field}>{FIELD_META[r.field].label}</th>)}</tr>
            </thead>
            <tbody>
              {preview.map((row, i) => (
                <tr key={i}>{row.map((c, j) => <td key={j} className="mono small">{c || <span className="muted">—</span>}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="row">
        {missing.length > 0 ? (
          <span className="small sla-over">Còn thiếu trường bắt buộc: {missing.map((m) => FIELD_META[m.field].label).join(', ')}</span>
        ) : (
          <span className="small pos">Đủ các trường bắt buộc</span>
        )}
        <span className="spacer" />
        <Button label="Lưu mẫu ánh xạ" icon="pi pi-check" disabled={missing.length > 0} loading={saving} onClick={save} />
      </div>
    </div>
  );
}

export default function Sources() {
  const [params, setParams] = useSearchParams();
  const res = useLoad(listSources, []);
  const code = (params.get('code') as SourceCode) || 'VTP';
  const tab = TAB_INDEX[params.get('tab') ?? 'mapping'] ?? 0;
  const [, bump] = useState(0);

  if (res.error) return <ErrorNote message={res.error} />;
  if (!res.data) return <Loading rows={6} />;
  const sources = res.data;
  const s = sources.find((x) => x.code === code) ?? sources[0];

  const go = (c: SourceCode, t?: string) => {
    const next = new URLSearchParams(params);
    next.set('code', c);
    if (t) next.set('tab', t);
    next.delete('proposeFrom');
    setParams(next, { replace: true });
  };

  /** "Đề xuất cập nhật biểu phí" from an exception opens its draft once; a reload must not open it again. */
  const dropProposeFrom = () => setParams((p) => {
    const next = new URLSearchParams(p);
    next.delete('proposeFrom');
    return next;
  }, { replace: true });

  return (
    <>
      <PageHeader title="Cấu hình nguồn" sub="Mỗi đối tác gửi file theo định dạng riêng. Cấu hình ánh xạ cột, biểu phí và lịch nhận file cho từng nguồn." />
      <div className="grid cols-sources">
        <div className="source-list" role="list">
          {sources.map((x) => {
            const mapped = x.mapping.filter((m) => m.column).length;
            return (
              <button key={x.code} role="listitem" className={`source-item ${x.code === s.code ? 'active' : ''}`} onClick={() => go(x.code)}>
                <span className="row" style={{ gap: 8, flexWrap: 'nowrap', alignItems: 'baseline' }}>
                  <i className={KIND_LABEL[x.kind].icon} style={{ color: 'var(--muted)' }} aria-hidden />
                  <strong style={{ minWidth: 0 }}>{x.name}</strong>
                </span>
                <span className="row small muted" style={{ gap: 8 }}>
                  {x.status === 'DRAFT' ? <Pill tone="warn" icon="pi pi-pencil">Nháp</Pill> : <Pill tone="good" icon="pi pi-check">Đang dùng</Pill>}
                  {mapped}/{x.mapping.length} trường đã ánh xạ
                </span>
              </button>
            );
          })}
        </div>

        <Card
          title={s.name}
          sub={`${KIND_LABEL[s.kind].label} · ${s.channel}`}
          actions={s.status === 'DRAFT' ? <Pill tone="warn" icon="pi pi-pencil">Nguồn mới, chưa kích hoạt</Pill> : <Pill tone="good" icon="pi pi-check">Đang dùng</Pill>}
        >
          <TabView activeIndex={tab} onTabChange={(e) => go(s.code, Object.keys(TAB_INDEX)[e.index])}>
            <TabPanel header="Ánh xạ cột">
              <MappingEditor source={s} onSaved={() => bump((n) => n + 1)} />
            </TabPanel>
            <TabPanel header="Biểu phí">
              <SourceFees source={s.code} proposeFrom={params.get('proposeFrom')} onDraftOpened={dropProposeFrom} />
            </TabPanel>
            <TabPanel header="Lịch nhận file">
              <dl className="props">
                <dt>Kênh nhận</dt>
                <dd className="mono">{s.channel}</dd>
                <dt>Mẫu tên file</dt>
                <dd className="mono">{s.filePattern}</dd>
                <dt>Lịch dự kiến</dt>
                <dd>{s.schedule}</dd>
                <dt>Bảng mã ký tự</dt>
                <dd>
                  {s.encoding}
                  {s.encoding !== 'UTF-8' && <span className="small muted"> · chuyển sang UTF-8 khi đọc để không lỗi dấu tiếng Việt</span>}
                </dd>
                <dt>Định dạng ngày</dt>
                <dd className="mono">{s.dateFormat}</dd>
                <dt>Cột trong file</dt>
                <dd>{s.headers.join(' · ')}</dd>
              </dl>
            </TabPanel>
          </TabView>
        </Card>
      </div>
    </>
  );
}
