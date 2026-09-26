import { Category, ProfileType, RiskBand } from './api.types';

/** Indian digit grouping: 1234567 → ₹12,34,567. Same rules as the backend's Money.inr. */
export function formatINR(amount: number | null | undefined, opts: { noSymbol?: boolean } = {}): string {
  let n = Math.round(amount || 0);
  const neg = n < 0;
  n = Math.abs(n);
  const s = String(n);
  const last3 = s.length > 3 ? s.slice(-3) : s;
  const rest = s.length > 3 ? s.slice(0, -3) : '';
  const grouped = rest.replace(/\B(?=(\d{2})+(?!\d))/g, ',');
  const digits = grouped ? `${grouped},${last3}` : last3;
  return (neg ? '-' : '') + (opts.noSymbol ? '' : '₹') + digits;
}

export function roundTo(n: number, step: number): number {
  return Math.round(n / step) * step;
}

/** Readable large amounts for prose: ₹4.2 lakh, ₹1.35 crore; rounded exact rupees below a lakh. */
export function formatAmount(amount: number | null | undefined): string {
  const n = Math.round(amount || 0);
  if (n >= 1e7) return `₹${Math.round(n / 1e5) / 100} crore`;
  if (n >= 1e5) return `₹${Math.round(n / 1e4) / 10} lakh`;
  return formatINR(roundTo(n, n >= 10000 ? 500 : 100));
}

/** Axis-label amounts: 0, 45k, 5.9L, 1.2Cr. */
export function compactINR(amount: number): string {
  const n = Math.round(amount || 0);
  if (n >= 1e7) return `${Math.round(n / 1e6) / 10}Cr`;
  if (n >= 1e5) return `${Math.round(n / 1e4) / 10}L`;
  if (n >= 1e3) return `${Math.round(n / 1e3)}k`;
  return String(n);
}

export function capitalize(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

export function bandLabel(band: RiskBand | null | undefined): string {
  return band ? capitalize(band.toLowerCase()) : '—';
}

export const CATEGORY_META: Record<Category, { label: string; color: string }> = {
  BUDGETING: { label: 'Budgeting', color: 'var(--c-budgeting)' },
  SAVING: { label: 'Saving', color: 'var(--c-saving)' },
  DEBT: { label: 'Debt', color: 'var(--c-debt)' },
  INVESTING: { label: 'Investing', color: 'var(--c-investing)' },
  COMPOUNDING: { label: 'Compounding', color: 'var(--c-compounding)' },
  RISK: { label: 'Risk', color: 'var(--c-risk)' },
  RETIREMENT: { label: 'Retirement', color: 'var(--c-retirement)' },
};

export const PROFILE_LABELS: Record<ProfileType, string> = {
  STUDENT: 'Student',
  PROFESSIONAL: 'Working professional',
  RETIREE: 'Retired',
};

export const PROFILE_NOUNS: Record<ProfileType, string> = {
  STUDENT: 'student',
  PROFESSIONAL: 'working professional',
  RETIREE: 'retiree',
};

/** "ollama · llama3.1:8b", or a plain label for the non-model fallbacks. */
export function providerLabel(provider: string | null | undefined, model: string | null | undefined): string {
  if (!provider) return '';
  if (provider === 'template') return 'written from your numbers · model unavailable';
  if (provider === 'rules') return 'answered from your numbers · model unavailable';
  return `${provider} · ${model ?? ''}`;
}

/** A readable date: 26 Sep 2026. */
export function shortDate(iso: string | null | undefined): string {
  if (!iso) return '';
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}
