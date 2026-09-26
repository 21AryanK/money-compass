import { FinancialSnapshot } from './api.types';
import { formatAmount, formatINR, roundTo } from './format';

export interface Tile { l: string; n: string; hint?: string; }

/** Tiles for "Your money snapshot" — shared with the PDF report. */
export function snapshotTiles(s: FinancialSnapshot): Tile[] {
  const incomeKnown = s.income !== null && s.income > 0;
  const tiles: Tile[] = [
    { l: 'Monthly income', n: incomeKnown ? formatINR(s.income) : s.income === 0 ? 'None regular' : 'Not shared' },
    { l: 'Saving each month', n: incomeKnown ? formatINR(roundTo(s.saving ?? 0, 100)) : `${Math.round(s.savingsRate)}%`,
      hint: incomeKnown ? `${Math.round(s.savingsRate)}% of income${s.rateKnown ? '' : ', assumed'}` : (s.rateKnown ? 'of income' : 'assumed') },
    { l: 'Est. monthly spending', n: incomeKnown ? formatINR(roundTo(s.spend ?? 0, 100)) : '—' },
    { l: 'Emergency fund', n: s.efMonths !== null ? `${Math.round(s.efMonths * 10) / 10} ${s.efMonths === 1 ? 'month' : 'months'}` : 'Not known',
      hint: `Target: ${s.efTargetMonths} months${incomeKnown ? ` (${formatAmount(s.efTarget)}); you have ${formatAmount(s.efHave)}` : ''}` },
  ];
  const emi: Record<string, string> = { A: 'None', B: 'Under 20%', C: '20–40%', D: 'Over 40%' };
  if (s.emiBand && s.emiBand !== '?') tiles.push({ l: 'Income going to EMIs', n: emi[s.emiBand] });
  if (s.yearsToRetire !== null) tiles.push({ l: 'Years to retirement', n: s.yearsToRetire ? `~${s.yearsToRetire} years` : 'Now', hint: 'Planning to retire at 60' });
  if (s.runwayYears !== null) tiles.push({ l: 'Corpus runway', n: `~${Math.round(s.runwayYears)} years`, hint: '25+ years is comfortable' });
  return tiles;
}

export const SNAPSHOT_NO_INCOME_NOTE =
  "You didn't share your income, so these can't be shown in rupees. Retake the assessment with a rough figure (a range is fine) to see them.";
