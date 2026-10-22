import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { SOURCES, USERS } from '../data/seed';
import type { SourceCode } from '../data/types';
import { KIND_LABEL, SOURCE_LABEL, fmtSignedVnd, fmtVnd, initials, type Tone } from '../lib/format';

export function Pill({ tone, icon, children }: { tone: Tone; icon?: string; children: ReactNode }) {
  return (
    <span className={`pill tone-${tone}`}>
      {icon && <i className={icon} aria-hidden />}
      {children}
    </span>
  );
}

export function SourceBadge({ code }: { code: SourceCode }) {
  const s = SOURCES.find((x) => x.code === code);
  return (
    <span className="src">
      {s && <i className={KIND_LABEL[s.kind].icon} aria-hidden />}
      {SOURCE_LABEL[code]}
    </span>
  );
}

export function Money({ value, signed = false, tone = false }: { value: number; signed?: boolean; tone?: boolean }) {
  const cls = tone ? (value < 0 ? 'neg' : value > 0 ? 'pos' : '') : '';
  return <span className={`num ${cls}`}>{signed ? fmtSignedVnd(value) : fmtVnd(value)}</span>;
}

export function PageHeader({ title, sub, actions, back }: { title: ReactNode; sub?: ReactNode; actions?: ReactNode; back?: { to: string; label: string } }) {
  return (
    <div>
      {back && (
        <Link className="back-link" to={back.to}>
          <i className="pi pi-arrow-left" aria-hidden /> {back.label}
        </Link>
      )}
      <div className="page-head">
        <div style={{ minWidth: 0 }}>
          <h1>{title}</h1>
          {sub && <div className="sub">{sub}</div>}
        </div>
        {actions && <div className="actions">{actions}</div>}
      </div>
    </div>
  );
}

export function Card({ title, sub, actions, children, flush, className = '' }: { title?: ReactNode; sub?: ReactNode; actions?: ReactNode; children: ReactNode; flush?: boolean; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <div className="card-head">
          <div style={{ minWidth: 0 }}>
            {title && <h2 className="card-title">{title}</h2>}
            {sub && <div className="card-sub">{sub}</div>}
          </div>
          {actions && <div className="row" style={{ marginLeft: 'auto' }}>{actions}</div>}
        </div>
      )}
      <div className={flush ? 'card-flush' : 'card-body'}>{children}</div>
    </section>
  );
}

export function Kpi({ label, value, foot, to }: { label: string; value: ReactNode; foot?: ReactNode; to?: string }) {
  const body = (
    <>
      <span className="label">{label}</span>
      <span className="kpi-value">{value}</span>
      {foot && <span className="kpi-foot">{foot}</span>}
    </>
  );
  return to ? (
    <Link to={to} className="card kpi">
      {body}
    </Link>
  ) : (
    <div className="card kpi">{body}</div>
  );
}

/** `bad`/`warn` are the thresholds below which the fill turns red/amber (defaults suit match rates). */
export function Meter({ value, label, bad = 0.9, warn = 0.95 }: { value: number; label?: string; bad?: number; warn?: number }) {
  const tone = value < bad ? 'bad' : value < warn ? 'warn' : '';
  return (
    <div className="meter">
      <div className="meter-track" role="presentation">
        <div className={`meter-fill ${tone}`} style={{ width: `${Math.max(0, Math.min(1, value)) * 100}%` }} />
      </div>
      {label && <span className="num small">{label}</span>}
    </div>
  );
}

export function Avatar({ userId, showName = false }: { userId: string | null; showName?: boolean }) {
  const u = USERS.find((x) => x.id === userId);
  if (!u)
    return (
      <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
        <span className="avatar empty" aria-hidden>
          ?
        </span>
        {showName && <span className="muted small">Chưa giao</span>}
      </span>
    );
  return (
    <span className="row" style={{ gap: 6, flexWrap: 'nowrap' }} title={u.name}>
      <span className="avatar" aria-hidden>
        {initials(u.name)}
      </span>
      {showName && <span className="small">{u.name}</span>}
    </span>
  );
}

export function Loading({ rows = 4 }: { rows?: number }) {
  return (
    <div className="stack" style={{ gap: 10 }} aria-busy>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} style={{ height: 14, borderRadius: 6, background: 'var(--neutral-bg)', width: `${90 - i * 12}%` }} />
      ))}
    </div>
  );
}

export function ErrorNote({ message }: { message: string }) {
  return (
    <div className="notice tone-bad">
      <i className="pi pi-exclamation-circle" aria-hidden />
      <span>{message}</span>
    </div>
  );
}
