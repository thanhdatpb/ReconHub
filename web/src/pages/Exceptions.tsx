import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { DataTable, type DataTableRowClickEvent } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { SelectButton } from 'primereact/selectbutton';
import { MultiSelect } from 'primereact/multiselect';
import { Dropdown } from 'primereact/dropdown';
import { InputText } from 'primereact/inputtext';
import { IconField } from 'primereact/iconfield';
import { InputIcon } from 'primereact/inputicon';
import { InputSwitch } from 'primereact/inputswitch';
import { Button } from 'primereact/button';
import { assignExceptions, exceptionCounts, listExceptions, type ExceptionTab } from '../api';
import { useLoad, useSession } from '../state/session';
import { Avatar, Card, ErrorNote, Money, PageHeader, Pill, SourceBadge } from '../components/ui';
import { ACTIVE_SOURCES, DEMO_NOW, USERS } from '../data/seed';
import type { ExceptionType, ReconException, SourceCode } from '../data/types';
import { SOURCE_LABEL, STATUS_META, TYPE_META, fmtDateTime, fmtSla } from '../lib/format';

const TYPES = Object.keys(TYPE_META) as ExceptionType[];

function summaryOf(e: ReconException) {
  if (e.orders.length) {
    const o = e.orders[0];
    return { main: o.code, sub: `${o.customer}${o.trackingCode ? ` · ${o.trackingCode}` : ''}` };
  }
  const p = e.payments[0];
  return { main: p?.externalRef ?? '', sub: p ? `"${p.description}"` : '' };
}

export default function Exceptions() {
  const [params, setParams] = useSearchParams();
  const nav = useNavigate();
  const { user, notify } = useSession();
  const tab = (params.get('tab') as ExceptionTab) || 'todo';
  const [types, setTypes] = useState<ExceptionType[]>(params.get('type') ? [params.get('type') as ExceptionType] : []);
  const [sources, setSources] = useState<SourceCode[]>([]);
  const [assignee, setAssignee] = useState('all');
  const [overdueOnly, setOverdueOnly] = useState(params.get('overdue') === '1');
  const [q, setQ] = useState(params.get('q') ?? '');
  const [selected, setSelected] = useState<ReconException[]>([]);
  const [bulkTo, setBulkTo] = useState<string | undefined>(undefined);
  const [first, setFirst] = useState(0);

  const urlQ = params.get('q');
  useEffect(() => {
    if (urlQ !== null) setQ(urlQ);
  }, [urlQ]);

  const counts = useLoad(exceptionCounts, []);
  const list = useLoad(() => listExceptions({ tab, types, sources, assignee, overdueOnly, q }), [tab, types.join(), sources.join(), assignee, overdueOnly, q]);

  const tabOptions = useMemo(() => {
    const c = counts.data;
    return [
      { value: 'todo', label: `Cần xử lý${c ? ` · ${c.todo}` : ''}` },
      { value: 'pending', label: `Chờ duyệt${c ? ` · ${c.pending}` : ''}` },
      { value: 'done', label: 'Đã xử lý' },
      { value: 'all', label: 'Tất cả' },
    ];
  }, [counts.data]);

  const setTab = (v: ExceptionTab) => {
    const next = new URLSearchParams(params);
    next.set('tab', v);
    setParams(next, { replace: true });
    setSelected([]);
    setFirst(0);
  };

  const onRowClick = (e: DataTableRowClickEvent) => {
    const target = e.originalEvent.target as HTMLElement;
    if (target.closest('.p-selection-column, .p-checkbox')) return;
    nav(`/exceptions/${(e.data as ReconException).id}`);
  };

  const applyBulk = async () => {
    const ids = selected.map((s) => s.id);
    const to = bulkTo === 'none' ? null : bulkTo ?? null;
    await assignExceptions(ids, to, user);
    notify({ severity: 'success', summary: 'Đã giao việc', detail: `${ids.length} ngoại lệ → ${USERS.find((u) => u.id === to)?.name ?? 'bỏ giao việc'}` });
    setSelected([]);
    setBulkTo(undefined);
  };

  const assigneeOptions = [
    { value: 'all', label: 'Mọi người xử lý' },
    { value: 'none', label: 'Chưa giao' },
    ...USERS.filter((u) => u.role !== 'ADMIN').map((u) => ({ value: u.id, label: u.name })),
  ];

  return (
    <>
      <PageHeader title="Hàng chờ ngoại lệ" sub="Những giao dịch quy tắc tự động chưa khớp được. Bấm vào một dòng để xem hai phía và gợi ý của AI." />

      <div className="filters">
        <SelectButton value={tab} onChange={(e) => e.value && setTab(e.value)} options={tabOptions} optionLabel="label" optionValue="value" allowEmpty={false} />
      </div>
      <div className="filters">
        <IconField iconPosition="left" className="grow">
          <InputIcon className="pi pi-search" />
          <InputText id="exc-search" value={q} onChange={(e) => { setQ(e.target.value); setFirst(0); }} placeholder="Mã ngoại lệ, mã đơn, tên khách, nội dung chuyển khoản…" />
        </IconField>
        <MultiSelect inputId="exc-type" value={types} onChange={(e) => { setTypes(e.value); setFirst(0); }} options={TYPES.map((t) => ({ value: t, label: TYPE_META[t].label }))} optionLabel="label" optionValue="value" placeholder="Mọi loại" maxSelectedLabels={1} selectedItemsLabel="{0} loại" showClear />
        <MultiSelect inputId="exc-source" value={sources} onChange={(e) => { setSources(e.value); setFirst(0); }} options={ACTIVE_SOURCES.map((s) => ({ value: s, label: SOURCE_LABEL[s] }))} optionLabel="label" optionValue="value" placeholder="Mọi nguồn" maxSelectedLabels={2} selectedItemsLabel="{0} nguồn" showClear />
        <Dropdown inputId="exc-assignee" value={assignee} onChange={(e) => { setAssignee(e.value); setFirst(0); }} options={assigneeOptions} optionLabel="label" optionValue="value" />
        <label className="row small ink-2" htmlFor="exc-overdue" style={{ gap: 8, marginLeft: 4 }}>
          <InputSwitch inputId="exc-overdue" checked={overdueOnly} onChange={(e) => { setOverdueOnly(!!e.value); setFirst(0); }} />
          Chỉ quá hạn
        </label>
      </div>

      {selected.length > 0 && (
        <div className="bulkbar" role="region" aria-label="Thao tác hàng loạt">
          <strong>Đã chọn {selected.length}</strong>
          <Dropdown inputId="bulk-assign" value={bulkTo} onChange={(e) => setBulkTo(e.value)} options={[{ value: 'none', label: 'Bỏ giao việc' }, ...USERS.filter((u) => u.role === 'ACCOUNTANT').map((u) => ({ value: u.id, label: u.name }))]} optionLabel="label" optionValue="value" placeholder="Giao cho…" />
          <Button label="Áp dụng" size="small" onClick={applyBulk} disabled={bulkTo === undefined} />
          <Button label="Bỏ chọn" size="small" text onClick={() => setSelected([])} />
        </div>
      )}

      {list.error && <ErrorNote message={list.error} />}
      <Card flush>
        <DataTable
          value={list.data ?? []}
          loading={list.loading && !list.data}
          dataKey="id"
          selectionMode="checkbox"
          selection={selected}
          onSelectionChange={(e) => setSelected(e.value as ReconException[])}
          onRowClick={onRowClick}
          className="clickable"
          rowClassName={(e: ReconException) => ((e.status === 'OPEN' || e.status === 'IN_REVIEW') && e.slaDueAt < DEMO_NOW ? 'row-overdue' : '')}
          paginator
          rows={15}
          first={first}
          onPage={(e) => setFirst(e.first)}
          rowsPerPageOptions={[15, 30, 60]}
          paginatorTemplate="RowsPerPageDropdown CurrentPageReport PrevPageLink NextPageLink"
          currentPageReportTemplate="{first}–{last} / {totalRecords}"
          size="small"
          scrollable
          tableStyle={{ minWidth: '74rem' }}
          emptyMessage="Không có ngoại lệ nào khớp bộ lọc."
        >
          <Column selectionMode="multiple" headerStyle={{ width: '3rem' }} />
          <Column header="Mã" body={(e: ReconException) => (<div><div className="mono cell-main">{e.id}</div><div className="cell-sub">{fmtDateTime(e.createdAt)}</div></div>)} />
          <Column header="Loại" body={(e: ReconException) => <Pill tone={TYPE_META[e.type].tone} icon={TYPE_META[e.type].icon}>{TYPE_META[e.type].short}</Pill>} />
          <Column header="Nguồn" body={(e: ReconException) => <SourceBadge code={e.source} />} />
          <Column header="Đơn / giao dịch" body={(e: ReconException) => { const s = summaryOf(e); return (<div style={{ minWidth: 0 }}><div className="mono cell-main">{s.main}</div><div className="cell-sub">{s.sub}</div></div>); }} />
          <Column header="Chênh lệch" align="right" sortable sortField="diffAmount" body={(e: ReconException) => <Money value={e.diffAmount} signed tone />} />
          <Column header="Gợi ý AI" body={(e: ReconException) => e.ai ? <span className={`ai-chip ${e.ai.confidence < 0.7 ? 'low' : ''}`}><i className="pi pi-sparkles" aria-hidden /> {Math.round(e.ai.confidence * 100)}%</span> : <span className="muted">—</span>} />
          <Column header="Hạn xử lý" sortable sortField="slaDueAt" body={(e: ReconException) => {
            if (e.status !== 'OPEN' && e.status !== 'IN_REVIEW') return <span className="muted small">—</span>;
            const s = fmtSla(e.slaDueAt);
            return <span className={`small nowrap ${s.overdue ? 'sla-over' : s.soon ? 'sla-soon' : ''}`}>{s.text}</span>;
          }} />
          <Column header="Người xử lý" body={(e: ReconException) => <Avatar userId={e.assigneeId} />} />
          <Column header="Trạng thái" body={(e: ReconException) => <Pill tone={STATUS_META[e.status].tone} icon={STATUS_META[e.status].icon}>{STATUS_META[e.status].label}</Pill>} />
        </DataTable>
      </Card>
    </>
  );
}
