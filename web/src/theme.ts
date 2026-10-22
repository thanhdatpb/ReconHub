// Light/dark handling. The page follows `data-theme` on <html> when it is set,
// otherwise the OS preference, and swaps the matching PrimeReact theme stylesheet.
import { useSyncExternalStore } from 'react';
import lightHref from 'primereact/resources/themes/lara-light-teal/theme.css?url';
import darkHref from 'primereact/resources/themes/lara-dark-teal/theme.css?url';

export type Mode = 'light' | 'dark';
export type Pref = 'system' | 'light' | 'dark';

const media = window.matchMedia('(prefers-color-scheme: dark)');
const root = document.documentElement;
const listeners = new Set<() => void>();
let mode: Mode = resolve();
let pref: Pref = readPref();

function readPref(): Pref {
  const a = root.getAttribute('data-theme');
  return a === 'dark' || a === 'light' ? a : 'system';
}

function resolve(): Mode {
  const a = root.getAttribute('data-theme');
  if (a === 'dark' || a === 'light') return a;
  return media.matches ? 'dark' : 'light';
}

function apply() {
  const next = resolve();
  let link = document.getElementById('prime-theme') as HTMLLinkElement | null;
  if (!link) {
    link = document.createElement('link');
    link.rel = 'stylesheet';
    link.id = 'prime-theme';
    // First in <head> so the app's own stylesheet can override theme variables.
    document.head.prepend(link);
  }
  const href = next === 'dark' ? darkHref : lightHref;
  if (link.getAttribute('href') !== href) link.setAttribute('href', href);
  const nextPref = readPref();
  if (next !== mode || nextPref !== pref) {
    mode = next;
    pref = nextPref;
    listeners.forEach((l) => l());
  }
}

export function initTheme() {
  apply();
  media.addEventListener('change', apply);
  new MutationObserver(apply).observe(root, { attributes: true, attributeFilter: ['data-theme'] });
}

export function setPref(p: Pref) {
  if (p === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', p);
}

function subscribe(l: () => void) {
  listeners.add(l);
  return () => {
    listeners.delete(l);
  };
}

export const useThemeMode = () => useSyncExternalStore(subscribe, () => mode);
export const useThemePref = () => useSyncExternalStore(subscribe, () => pref);
