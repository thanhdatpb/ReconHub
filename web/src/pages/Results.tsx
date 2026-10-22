import { useMemo, useState } from 'react';
import { Chart } from 'primereact/chart';
import { Calendar } from 'primereact/calendar';
import { MultiSelect } from 'primereact/multiselect';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { Dialog } from 'primereact/dialog';
import { listMatchGroups, listReconDays } from '../api';
import { useLoad } from '../state/session';
import { Card, ErrorNote, Meter, Money, PageHeader, Pill, SourceBadge } from '../components/ui';
import { baseOptions, barStyle, useChartTheme } from '../components/charts';
import { ACTIVE_SOURCES, DEMO_NOW, daysAgo } from '../data/seed';
import type { MatchGroup, ReconDay, SourceCode } from '../data/types';
import { METHOD_META, SOURCE_LABEL, fmtDate, fmtDayMonth, fmtNumber, fmtPct } from '../lib/format';

function Groups({ day }: { day: ReconDay }) {
  const res = useLoad(() => listMatchGroups(day), [day]);
  return (
    <div className="stack" style={{ gap: 10 }}>
      <div className="row small ink-2">
        <span>{fmtNumber(day.totalLines)} dòng</span>·<span>{fmtNumber(day.autoMatched)} khớp tự động</span>·<span>{fmtNumber(day.manualMatched)} khớp sau xử lý</span>·<span>{fmtNumber(day.exceptions)} còn mở</span>
      </div>
      <DataTable value={res.data ?? []} loading={res.loading} size="small" scrollable scrollHeight="380px" dataKey="id" tableStyle={{ minWidth: '44rem' }}>
        <Column header="Phương thức" body={(g: MatchGroup) => <Pill tone={METHOD_META[g.method].tone}>{METHOD_META[g.method].label}</Pill>} />
        <Column header="Đơn hàng" body={(g: MatchGroup) => <span className="mono">{g.orderCodes.join(' + ')}</span>} />
        <Column header="Giao dịch" body={(g: MatchGroup) => <span className="mono small">{g.paymentRef}</span>} />
        <Column header="Tiền nhận" align="right" body={(g: MatchGroup) => <Money value={g.amount} />} />
        <Column header="Phí" align="right" body={(g: MatchGroup) => (g.fee ? <Money value={g.fee} /> : <span className="muted">—</span>)} />
      </DataTable>
      <span className="small muted">Mẫu 14 nhóm khớp trong ngày. Bản thật phân trang toàn bộ nhóm khớp từ bảng match_groups.</span>
    </div>
  );
}

export default function Results() {
  const t = useChartTheme();
  const [range, setRange] = useState<(Date | null)[]>([daysAgo(13), daysAgo(0)]);
  const [sources, setSources] = useState<SourceCode[]>([]);
  const [open, setOpen] = useState<ReconDay | null>(null);
  const from = range[0] ?? daysAgo(13);
  const to = range[1] ?? range[0] ?? DEMO_NOW;
  const res = useLoad(() => listReconDays(from, to, sources), [from.getTime(), to.getTime(), sources.join()]);
  const rows = useMemo(() => [...(res.data ?? [])].sort((a, b) => b.date.getTime() - a.date.getTime() || a.source.localeCompare(b.source)), [res.data]);

  const byDay = useMemo(() => {
    const m = new Map<number, { auto: number; manual: number; open: number; total: number }>();
    for (const d of res.data ?? []) {
      const k = d.date.getTime();
      const x = m.get(k) ?? { auto: 0, manual: 0, open: 0, total: 0 };
      x.auto += d.autoMatched;
      x.manual += d.manualMatched;
      x.open += d.exceptions;
      x.total += d.totalLines;
      m.set(k, x);
    }
    return [...m.entries()].sort((a, b) => a[0] - b[0]);
  }, [res.data]);

  const totals = byDay.reduce((s, [, x]) => ({ auto: s.auto + x.auto, manual: s.manual + x.manual, open: s.open + x.open, total: s.total + x.total }), { auto: 0, manual: 0, open: 0, total: 0 });

  // Only lines that needed a person: resolved (slot 1, blue) and still open (slot 2, orange).
  const chartData = {
    labels: byDay.map(([k]) => fmtDayMonth(new Date(k))),
    datasets: [
      { label: 'Đã xử lý', data: byDay.map(([, x]) => x.manual), ...barStyle(t, t.series[0], { end: false }) },
      { label: 'Còn mở', data: byDay.map(([, x]) => x.open), ...barStyle(t, t.series[1]) },
    ],
  };
  const options = baseOptions(t, { stacked: true, legend: true, tooltipValue: (n) => `${fmtNumber(n)} dòng` });

  return (
    <>
      <PageHeader title="Kết quả đối soát" sub="Mỗi ngày, mỗi nguồn: bao nhiêu dòng khớp tự động, bao nhiêu phải xử lý tay, bao nhiêu còn mở." />
      <div className="filters">
        <Calendar inputId="range" value={range} onChange={(e) => setRange((e.value as (Date | null)[]) ?? [daysAgo(13), daysAgo(0)])} selectionMode="range" readOnlyInput dateFormat="dd/mm/yy" maxDate={DEMO_NOW} showIcon inputStyle={{ width: '15.5rem' }} aria-label="Khoảng ngày" />
        <MultiSelect inputId="res-source" value={sources} onChange={(e) => setSources(e.value)} options={ACTIVE_SOURCES.map((s) => ({ value: s, label: SOURCE_LABEL[s] }))} optionLabel="label" optionValue="value" placeholder="Mọi nguồn" maxSelectedLabels={2} selectedItemsLabel="{0} nguồn" showClear />
        <span className="spacer" />
        <span className="small ink-2">
          {fmtNumber(totals.total)} dòng · tự động <strong>{totals.total ? fmtPct(totals.auto / totals.total) : '—'}</strong>
        </span>
      </div>
      {res.error && <ErrorNote message={res.error} />}
      <div className="stack">
        <Card title="Giao dịch không khớp tự động" sub="Số dòng phải chuyển sang hàng chờ ngoại lệ mỗi ngày, chia theo đã xử lý hay còn mở">
          <div className="chart-box tall">
            <Chart type="bar" data={chartData} options={options} style={{ height: '100%' }} />
          </div>
        </Card>
        <Card title="Chi tiết theo ngày và nguồn" sub="Bấm một dòng để xem các nhóm khớp" flush>
          <DataTable value={rows} loading={res.loading && !res.data} size="small" paginator rows={14} scrollable className="clickable" onRowClick={(e) => setOpen(e.data as ReconDay)}
            paginatorTemplate="CurrentPageReport PrevPageLink NextPageLink" currentPageReportTemplate="{first}–{last} / {totalRecords}" tableStyle={{ minWidth: '60rem' }}>
            <Column header="Ngày" body={(d: ReconDay) => <span className="num">{fmtDate(d.date)}</span>} />
            <Column header="Nguồn" body={(d: ReconDay) => <SourceBadge code={d.source} />} />
            <Column header="Tổng dòng" align="right" body={(d: ReconDay) => <span className="num">{fmtNumber(d.totalLines)}</span>} />
            <Column header="Tự động" align="right" body={(d: ReconDay) => <span className="num">{fmtNumber(d.autoMatched)}</span>} />
            <Column header="Sau xử lý" align="right" body={(d: ReconDay) => <span className="num">{fmtNumber(d.manualMatched)}</span>} />
            <Column header="Còn mở" align="right" body={(d: ReconDay) => <span className={`num ${d.exceptions ? 'neg' : 'muted'}`}>{fmtNumber(d.exceptions)}</span>} />
            <Column header="Khớp tự động" style={{ width: 190 }} body={(d: ReconDay) => <Meter value={d.autoMatched / d.totalLines} label={fmtPct(d.autoMatched / d.totalLines)} />} />
            <Column header="Tiền về" align="right" body={(d: ReconDay) => <Money value={d.amountIn} />} />
            <Column header="Phí" align="right" body={(d: ReconDay) => (d.feeTotal ? <Money value={d.feeTotal} /> : <span className="muted">—</span>)} />
          </DataTable>
        </Card>
      </div>
      <Dialog header={open ? `${SOURCE_LABEL[open.source]} · ${fmtDate(open.date)}` : ''} visible={!!open} onHide={() => setOpen(null)} style={{ width: 'min(860px, 96vw)' }}>
        {open && <Groups day={open} />}
      </Dialog>
    </>
  );
}
