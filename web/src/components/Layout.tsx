import { useState, type FormEvent } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Button } from 'primereact/button';
import { Dropdown } from 'primereact/dropdown';
import { InputText } from 'primereact/inputtext';
import { IconField } from 'primereact/iconfield';
import { InputIcon } from 'primereact/inputicon';
import { Sidebar } from 'primereact/sidebar';
import { USERS } from '../data/seed';
import { exceptionCounts } from '../api';
import { useLoad, useSession } from '../state/session';
import { setPref, useThemePref, type Pref } from '../theme';
import { initials } from '../lib/format';

interface NavItem {
  to: string;
  label: string;
  icon: string;
  end?: boolean;
  count?: boolean;
}

const NAV: { group: string; items: NavItem[] }[] = [
  {
    group: 'Theo dõi',
    items: [
      { to: '/', label: 'Tổng quan', icon: 'pi pi-th-large', end: true },
      { to: '/results', label: 'Kết quả đối soát', icon: 'pi pi-chart-bar' },
    ],
  },
  {
    group: 'Xử lý',
    items: [
      { to: '/exceptions', label: 'Hàng chờ ngoại lệ', icon: 'pi pi-inbox', count: true },
      { to: '/aging', label: 'Tuổi nợ & bút toán', icon: 'pi pi-clock' },
    ],
  },
  {
    group: 'Dữ liệu',
    items: [
      { to: '/files', label: 'Nhận file', icon: 'pi pi-upload' },
      { to: '/sources', label: 'Cấu hình nguồn', icon: 'pi pi-sliders-h' },
    ],
  },
  {
    group: 'Vận hành',
    items: [
      { to: '/batch', label: 'Theo dõi batch', icon: 'pi pi-server' },
      { to: '/assistant', label: 'Trợ lý số liệu', icon: 'pi pi-sparkles' },
    ],
  },
];

function Logo() {
  return (
    <svg width="30" height="30" viewBox="0 0 30 30" aria-hidden>
      <rect x="1" y="5" width="18" height="22" rx="3" fill="none" stroke="#5eead4" strokeWidth="2" />
      <rect x="11" y="2" width="18" height="22" rx="3" fill="#0f2a26" stroke="#d7e8e3" strokeWidth="2" />
      <path d="M15.5 13.5l3 3 6-6.5" fill="none" stroke="#5eead4" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function RailContent({ onNavigate }: { onNavigate?: () => void }) {
  const counts = useLoad(exceptionCounts, []);
  return (
    <>
      <NavLink to="/" className="brand" onClick={onNavigate}>
        <Logo />
        <span>
          <span className="brand-name">ReconHub</span>
          <span className="brand-sub" style={{ display: 'block' }}>
            Đối soát thanh toán đa kênh
          </span>
        </span>
      </NavLink>
      <nav aria-label="Điều hướng chính">
        {NAV.map((g) => (
          <div className="nav-group" key={g.group}>
            <div className="nav-group-label">{g.group}</div>
            {g.items.map((it) => (
              <NavLink key={it.to} to={it.to} end={it.end} className="nav-link" onClick={onNavigate}>
                <i className={it.icon} aria-hidden />
                {it.label}
                {it.count && counts.data && (
                  <span className={`nav-count ${counts.data.overdue ? 'hot' : ''}`} title={`${counts.data.todo} cần xử lý, ${counts.data.overdue} quá hạn`}>
                    {counts.data.todo}
                  </span>
                )}
              </NavLink>
            ))}
          </div>
        ))}
      </nav>
      <div className="rail-foot">Cửa hàng mẫu: Thời trang An Nhiên</div>
    </>
  );
}

const TITLES: [RegExp, string][] = [
  [/^\/exceptions\/.+/, 'Chi tiết ngoại lệ'],
  [/^\/exceptions/, 'Hàng chờ ngoại lệ'],
  [/^\/results/, 'Kết quả đối soát'],
  [/^\/aging/, 'Tuổi nợ & bút toán'],
  [/^\/files/, 'Nhận file'],
  [/^\/sources/, 'Cấu hình nguồn'],
  [/^\/batch/, 'Theo dõi batch'],
  [/^\/assistant/, 'Trợ lý số liệu'],
];

const THEME_NEXT: Record<Pref, { next: Pref; icon: string; label: string }> = {
  system: { next: 'light', icon: 'pi pi-desktop', label: 'Giao diện: theo hệ thống' },
  light: { next: 'dark', icon: 'pi pi-sun', label: 'Giao diện: sáng' },
  dark: { next: 'system', icon: 'pi pi-moon', label: 'Giao diện: tối' },
};

export default function Layout() {
  const [drawer, setDrawer] = useState(false);
  const [q, setQ] = useState('');
  const { user, setUserId } = useSession();
  const pref = useThemePref();
  const loc = useLocation();
  const nav = useNavigate();
  const title = TITLES.find(([re]) => re.test(loc.pathname))?.[1] ?? 'Tổng quan';

  const onSearch = (e: FormEvent) => {
    e.preventDefault();
    if (q.trim()) nav(`/exceptions?q=${encodeURIComponent(q.trim())}&tab=all`);
  };

  const userTemplate = (u: (typeof USERS)[number] | null) =>
    u && (
      <span className="user-opt">
        <span className="avatar">{initials(u.name)}</span>
        <span className="who-text">
          {u.name}
          <small>{u.title}</small>
        </span>
      </span>
    );

  return (
    <div className="app">
      <aside className="rail">
        <RailContent />
      </aside>
      <Sidebar visible={drawer} onHide={() => setDrawer(false)} className="rail-drawer" style={{ width: 'min(280px, 86vw)' }}>
        <RailContent onNavigate={() => setDrawer(false)} />
      </Sidebar>
      <div style={{ minWidth: 0 }}>
        <header className="topbar">
          <Button className="menu-btn" icon="pi pi-bars" text rounded aria-label="Mở menu" onClick={() => setDrawer(true)} />
          <span className="sr-only" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>
            {title}
          </span>
          <form className="topbar-search" onSubmit={onSearch} role="search">
            <IconField iconPosition="left">
              <InputIcon className="pi pi-search" />
              <InputText id="global-search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Tìm mã đơn, mã vận đơn, mã giao dịch…" aria-label="Tìm kiếm" />
            </IconField>
          </form>
          <span className="spacer" />
          <span className="demo-chip" title="Toàn bộ số liệu là dữ liệu mẫu do bộ sinh dữ liệu tạo ra">
            Dữ liệu mẫu
          </span>
          <Button
            icon={THEME_NEXT[pref].icon}
            text
            rounded
            aria-label={THEME_NEXT[pref].label}
            tooltip={THEME_NEXT[pref].label}
            tooltipOptions={{ position: 'bottom' }}
            onClick={() => setPref(THEME_NEXT[pref].next)}
          />
          <div className="user-pick">
            <Dropdown
              inputId="current-user"
              value={user.id}
              options={USERS}
              optionValue="id"
              optionLabel="name"
              onChange={(e) => setUserId(e.value)}
              valueTemplate={() => userTemplate(user)}
              itemTemplate={(u) => userTemplate(u)}
              aria-label="Đổi người dùng (để thử luồng duyệt)"
            />
          </div>
        </header>
        <main className="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
