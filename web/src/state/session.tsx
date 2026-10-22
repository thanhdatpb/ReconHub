import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { Toast, type ToastMessage } from 'primereact/toast';
import { USERS } from '../data/seed';
import type { User } from '../data/types';
import { getVersion, subscribe } from '../api';

interface Session {
  user: User;
  setUserId: (id: string) => void;
  notify: (m: ToastMessage) => void;
}

const Ctx = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState('u1');
  const toast = useRef<Toast>(null);
  const notify = useCallback((m: ToastMessage) => toast.current?.show({ life: 4000, ...m }), []);
  const value = useMemo(() => ({ user: USERS.find((u) => u.id === userId)!, setUserId, notify }), [userId, notify]);
  return (
    <Ctx.Provider value={value}>
      <Toast ref={toast} position="bottom-right" />
      {children}
    </Ctx.Provider>
  );
}

export function useSession() {
  const s = useContext(Ctx);
  if (!s) throw new Error('SessionProvider missing');
  return s;
}

/** Runs an API call and re-runs it when its deps change or the mock data changes. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[]) {
  const version = useSyncExternalStore(subscribe, getVersion);
  const [state, setState] = useState<{ data: T | null; loading: boolean; error: string | null }>({ data: null, loading: true, error: null });
  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true }));
    fn()
      .then((data) => alive && setState({ data, loading: false, error: null }))
      .catch((e: Error) => alive && setState({ data: null, loading: false, error: e.message }));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version]);
  return state;
}
