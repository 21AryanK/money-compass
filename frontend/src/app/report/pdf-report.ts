import type { jsPDF as JsPdfDoc } from 'jspdf';
import {
  Category, FinancialSnapshot, LiteracyScoreResponse, MixView, PlanResponse, RiskProfileResponse,
} from '../core/api.types';
import { CATEGORY_META, PROFILE_LABELS, bandLabel, compactINR, formatAmount, formatINR } from '../core/format';
import { SNAPSHOT_NO_INCOME_NOTE, snapshotTiles } from '../core/snapshot-tiles';

/**
 * The downloadable A4 report: score and breakdown, the explanation, the money
 * snapshot, the risk profile with its capacity factors, the monthly
 * priorities, and the investment plan with its growth chart, instrument table
 * and breakdowns. Everything comes from the same responses the screens show,
 * so the report can't disagree with them. jsPDF is imported on first use so
 * it isn't in the initial bundle.
 */

type Rgb = [number, number, number];

// jsPDF's built-in fonts only cover WinAnsi (Latin-1 plus a few typographic
// marks). The rupee sign and some maths symbols aren't in it, so they're
// spelled out rather than printed as garbage.
const WIN_ANSI_EXTRA = '€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ';
function pdfText(str: unknown): string {
  return String(str ?? '')
    .replace(/₹\s?/g, 'Rs ')
    .replace(/−/g, '-').replace(/≈/g, '~').replace(/→/g, '->').replace(/≥/g, '>=').replace(/≤/g, '<=')
    .replace(/[^\x00-\xFF]/g, (ch) => (WIN_ANSI_EXTRA.includes(ch) ? ch : ''));
}

const C: Record<string, Rgb> = {
  ink: [34, 31, 26], muted: [112, 105, 92], rule: [224, 218, 206], track: [238, 234, 225],
  accent: [176, 126, 40], good: [39, 140, 78], warn: [196, 124, 18],
  equity: [52, 160, 124], debt: [88, 128, 196], gold: [217, 165, 74], cash: [160, 152, 138],
};

const VEHICLE_LABELS: Record<string, string> = { mf: 'mutual funds (index / equity)', direct: 'direct equity shares' };

export interface ReportInput {
  score: LiteracyScoreResponse;
  risk: RiskProfileResponse;
  plan: PlanResponse;
}

/** Builds the report and saves it; resolves to the file name. */
export async function downloadReport(input: ReportInput): Promise<string> {
  const { jsPDF } = await import('jspdf');
  const doc = buildReportPdf(new jsPDF({ unit: 'mm', format: 'a4' }), input);
  const name = reportFileName();
  doc.save(name);
  return name;
}

function reportFileName(): string {
  const now = new Date();
  const pad = (n: number) => (n < 10 ? '0' : '') + n;
  return `money-compass-report-${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}.pdf`;
}

export function buildReportPdf(doc: JsPdfDoc, { score, risk, plan }: ReportInput): JsPdfDoc {
  const W = doc.internal.pageSize.getWidth(), H = doc.internal.pageSize.getHeight();
  const M = 16, CW = W - 2 * M, BOTTOM = H - 18;
  const PT = 0.3528; // mm per point
  let y = M;

  const textColor = (c: Rgb = C['ink']) => doc.setTextColor(c[0], c[1], c[2]);
  const fill = (c: Rgb) => doc.setFillColor(c[0], c[1], c[2]);
  const stroke = (c: Rgb) => doc.setDrawColor(c[0], c[1], c[2]);
  const font = (style: string, size: number, c?: Rgb) => { doc.setFont('helvetica', style); doc.setFontSize(size); textColor(c); };
  const lineH = (size: number) => size * PT * 1.4;
  const ensure = (h: number) => { if (y + h > BOTTOM) { doc.addPage(); y = M; } };

  function para(text: string, o: { size?: number; indent?: number; style?: string; color?: Rgb; width?: number; after?: number } = {}) {
    const size = o.size ?? 10, indent = o.indent ?? 0, lh = lineH(size);
    font(o.style ?? 'normal', size, o.color);
    (doc.splitTextToSize(pdfText(text), o.width ?? CW - indent) as string[]).forEach((ln) => {
      ensure(lh);
      doc.text(ln, M + indent, y + lh * 0.72);
      y += lh;
    });
    y += o.after ?? 2;
  }

  function section(title: string) {
    ensure(22);
    y += 5;
    font('bold', 13, C['accent']);
    doc.text(pdfText(title), M, y + 4.5);
    y += 7.5;
    stroke(C['rule']); doc.setLineWidth(0.3); doc.line(M, y, M + CW, y);
    y += 4;
  }

  const tableHeight = (rows: number) => (rows + 1) * 8.5 + 12;

  function subhead(title: string, c?: Rgb) {
    ensure(20);
    y += 1;
    para(title, { style: 'bold', size: 10.5, color: c, after: 1 });
  }

  function bullets(items: string[], dot: Rgb) {
    (items ?? []).forEach((t) => {
      const size = 9.5, lh = lineH(size);
      font('normal', size);
      const lines = doc.splitTextToSize(pdfText(t), CW - 6) as string[];
      ensure(lh * Math.min(lines.length, 2));
      fill(dot); doc.circle(M + 1.6, y + lh * 0.5, 0.8, 'F');
      lines.forEach((ln) => { ensure(lh); doc.text(ln, M + 5, y + lh * 0.72); y += lh; });
      y += 1.2;
    });
    y += 1;
  }

  function statGrid(tiles: { l: string; n: string; hint?: string }[], cols: number) {
    const cw = CW / cols;
    for (let i = 0; i < tiles.length; i += cols) {
      const row = tiles.slice(i, i + cols);
      font('normal', 8);
      const hintLines = row.map((t) => (t.hint ? (doc.splitTextToSize(pdfText(t.hint), cw - 4) as string[]).slice(0, 2) : []));
      const rowH = 13 + Math.max(...hintLines.map((h) => h.length)) * 3.6;
      ensure(rowH);
      row.forEach((t, j) => {
        const x = M + j * cw;
        font('bold', 7.5, C['muted']);
        doc.text(pdfText(t.l).toUpperCase(), x, y + 3);
        font('bold', 12);
        doc.text(pdfText(t.n), x, y + 9);
        if (hintLines[j].length) { font('normal', 8, C['muted']); doc.text(hintLines[j], x, y + 13.5, { lineHeightFactor: 1.3 }); }
      });
      y += rowH;
    }
    y += 1;
  }

  function table(cols: { h: string; w: number; align?: 'right' }[], rows: string[][], o: { totalLast?: boolean } = {}) {
    const widths = cols.map((c) => c.w * CW);
    const cellX = (j: number) => M + widths.slice(0, j).reduce((a, b) => a + b, 0);
    const estimate = (rows.length + 1) * 8.5;
    if (estimate < BOTTOM - M) ensure(estimate);
    const drawRow = (cells: string[], style: string, size: number, c?: Rgb) => {
      font(style, size, c);
      const first = doc.splitTextToSize(pdfText(cells[0]), widths[0] - 3) as string[];
      const lh = lineH(size), h = Math.max(1, first.length) * lh + 3;
      ensure(h);
      first.forEach((ln, li) => doc.text(ln, M, y + 1.5 + lh * (li + 0.72)));
      for (let j = 1; j < cells.length; j++) {
        const right = cols[j].align === 'right';
        doc.text(pdfText(cells[j]), right ? cellX(j) + widths[j] : cellX(j), y + 1.5 + lh * 0.72, right ? { align: 'right' } : undefined);
      }
      y += h;
      stroke(C['rule']); doc.setLineWidth(0.2); doc.line(M, y, M + CW, y);
    };
    drawRow(cols.map((c) => c.h), 'bold', 8, C['muted']);
    rows.forEach((r, i) => {
      const isTotal = o.totalLast && i === rows.length - 1;
      drawRow(r, isTotal ? 'bold' : 'normal', 9.5, isTotal ? C['ink'] : undefined);
    });
    y += 3;
  }

  function barRows(rows: { label: string; value: number | null }[]) {
    const labelW = 34, pctW = 14, trackW = CW - labelW - pctW - 4;
    rows.forEach((r) => {
      ensure(7);
      font('normal', 9.5);
      doc.text(pdfText(r.label), M, y + 4);
      fill(C['track']); doc.roundedRect(M + labelW, y + 1.4, trackW, 3.2, 1.6, 1.6, 'F');
      if (r.value) { fill(C['accent']); doc.roundedRect(M + labelW, y + 1.4, Math.max(3.2, (trackW * r.value) / 100), 3.2, 1.6, 1.6, 'F'); }
      font('bold', 9.5, r.value === null ? C['muted'] : undefined);
      doc.text(r.value === null ? 'n/a' : `${r.value}%`, M + CW, y + 4, { align: 'right' });
      y += 7;
    });
    y += 2;
  }

  function stackedBar(parts: { label: string; value: number; color: Rgb }[]) {
    ensure(18);
    let x = M;
    parts.forEach((p) => {
      const w = (CW * p.value) / 100;
      if (w <= 0) return;
      fill(p.color); doc.rect(x, y, w, 7, 'F');
      if (p.value >= 10) { font('bold', 8, [255, 255, 255]); doc.text(`${Math.round(p.value)}%`, x + w / 2, y + 4.8, { align: 'center' }); }
      x += w;
    });
    y += 10;
    let lx = M;
    font('normal', 8.5);
    parts.forEach((p) => {
      const label = pdfText(`${p.label} ${Math.round(p.value)}%`);
      const w = doc.getTextWidth(label) + 9;
      if (lx + w > M + CW) { lx = M; y += 5; ensure(5); }
      fill(p.color); doc.rect(lx, y + 0.6, 3, 3, 'F');
      textColor(); doc.text(label, lx + 4.5, y + 3.2);
      lx += w;
    });
    y += 8;
  }

  function growthChart(series: PlanResponse['projection']['series']) {
    const h = 58, padL = 22, padB = 7, top = 4;
    ensure(h + 8);
    const x0 = M + padL, x1 = M + CW, yBase = y + h - padB, yTop = y + top;
    const maxV = Math.max(...series.map((p) => p.high || p.value), 1);
    const px = (i: number) => x0 + (i / Math.max(1, series.length - 1)) * (x1 - x0);
    const py = (v: number) => yBase - (v / maxV) * (yBase - yTop);

    stroke(C['rule']); doc.setLineWidth(0.2);
    font('normal', 7.5, C['muted']);
    [0, 0.5, 1].forEach((f) => {
      const gy = py(maxV * f);
      doc.line(x0, gy, x1, gy);
      doc.text(pdfText(formatAmount(maxV * f)), x0 - 2, gy + 1, { align: 'right' });
    });
    const step = Math.max(1, Math.round((series.length - 1) / 5));
    series.forEach((p, i) => {
      if (i % step === 0 || i === series.length - 1) doc.text(`Yr ${p.year}`, px(i), yBase + 4.5, { align: 'center' });
    });
    const polyline = (key: 'value' | 'invested', c: Rgb, width: number, dash: boolean) => {
      stroke(c); doc.setLineWidth(width);
      if (dash) doc.setLineDashPattern([1.6, 1.4], 0);
      for (let i = 1; i < series.length; i++) doc.line(px(i - 1), py(series[i - 1][key]), px(i), py(series[i][key]));
      if (dash) doc.setLineDashPattern([], 0);
    };
    // low-to-high range band: two triangles per year, under the lines
    fill([246, 236, 212]);
    for (let b = 1; b < series.length; b++) {
      const xa = px(b - 1), xb = px(b);
      doc.triangle(xa, py(series[b - 1].low), xa, py(series[b - 1].high), xb, py(series[b].high), 'F');
      doc.triangle(xa, py(series[b - 1].low), xb, py(series[b].high), xb, py(series[b].low), 'F');
    }
    polyline('invested', C['muted'], 0.5, true);
    polyline('value', C['accent'], 0.9, false);

    const last = series[series.length - 1];
    fill(C['accent']); doc.circle(px(series.length - 1), py(last.value), 0.9, 'F');
    y += h;
    font('normal', 8, C['muted']);
    stroke(C['accent']); doc.setLineWidth(0.9); doc.line(x0, y + 1, x0 + 6, y + 1);
    doc.text('Projected value', x0 + 8, y + 2);
    stroke(C['muted']); doc.setLineWidth(0.5); doc.setLineDashPattern([1.6, 1.4], 0);
    doc.line(x0 + 40, y + 1, x0 + 46, y + 1); doc.setLineDashPattern([], 0);
    doc.text('Amount invested', x0 + 48, y + 2);
    fill([246, 236, 212]); doc.rect(x0 + 82, y - 0.5, 6, 3, 'F');
    doc.text('Range if returns run lower or higher', x0 + 90, y + 2);
    y += 7;
  }

  const snap: FinancialSnapshot = score.snapshot;
  const p = plan.projection;

  /* ---- Title ---- */
  font('bold', 21);
  doc.text('Your Money Compass report', M, y + 7);
  y += 11;
  const generated = new Date().toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' });
  para(`Prepared for a ${PROFILE_LABELS[snap.profile].toLowerCase()} on ${generated}.`, { size: 10, color: C['muted'], after: 3 });
  stroke(C['accent']); doc.setLineWidth(0.8); doc.line(M, y, M + 24, y);
  y += 5;

  /* ---- Score ---- */
  section('1. Financial literacy score');
  ensure(20);
  font('bold', 30, C['accent']);
  doc.text(String(score.total), M, y + 10);
  const numW = doc.getTextWidth(String(score.total));
  font('normal', 10, C['muted']);
  doc.text('/ 100', M + numW + 2, y + 10);
  font('bold', 13);
  doc.text(pdfText(score.bandTitle), M + numW + 16, y + 5);
  font('normal', 9.5, C['muted']);
  doc.text(doc.splitTextToSize(pdfText(score.bandSub), CW - numW - 16), M + numW + 16, y + 10);
  y += 17;
  if (score.unknownNote) para(score.unknownNote, { size: 9, color: C['muted'], after: 3 });
  subhead('Category breakdown');
  barRows((Object.keys(CATEGORY_META) as Category[]).map((c) => ({ label: CATEGORY_META[c].label, value: score.categoryBreakdown[c] ?? null })));

  subhead('What this means');
  para(score.narrative.summary, { after: 3 });
  subhead('Strengths', C['good']);
  bullets(score.narrative.strengths, C['good']);
  subhead('Gaps', C['warn']);
  bullets(score.narrative.gaps, C['warn']);
  subhead('Next steps', C['accent']);
  bullets(score.narrative.nextSteps, C['accent']);
  if (score.providerUsed === 'template') para('This explanation was written from your numbers by the app, because the model was unavailable.', { size: 8.5, color: C['muted'] });

  /* ---- Snapshot ---- */
  section('2. Your money snapshot');
  para('Worked out from your answers. Spending is estimated as income minus what you save.', { size: 9, color: C['muted'], after: 3 });
  statGrid(snapshotTiles(snap), 3);
  if (snap.income === null) para(SNAPSHOT_NO_INCOME_NOTE, { size: 9, color: C['muted'] });

  /* ---- Risk ---- */
  const active = bandLabel(risk.activeBand), computedBand = bandLabel(risk.riskBand);
  section('3. Risk profile');
  statGrid([
    { l: 'Risk tolerance', n: `${risk.toleranceScore} / 100`, hint: "How you say you'd react to volatility" },
    { l: 'Risk capacity', n: `${risk.capacityScore} / 100`, hint: 'Whether your finances can absorb a downturn' },
    { l: 'Risk band', n: active, hint: active === computedBand ? 'The lower of the two' : `Your choice (calculated: ${computedBand})` },
  ], 3);
  ensure(tableHeight(risk.capacityFactors.length + 1));
  subhead('How your capacity was worked out');
  table([{ h: 'Factor', w: 0.82 }, { h: 'Points', w: 0.18, align: 'right' }],
    risk.capacityFactors.map((f, i) => [f.label, i === 0 ? String(f.delta) : (f.delta > 0 ? '+' : '') + f.delta])
      .concat([['Risk capacity (kept within 0-100)', String(risk.capacityScore)]]), { totalLast: true });
  subhead(`Suggested allocation — ${active}`);
  const a = risk.allocationPercent;
  stackedBar([
    { label: 'Equity', value: a.equity, color: C['equity'] }, { label: 'Debt', value: a.debt, color: C['debt'] },
    { label: 'Gold', value: a.gold, color: C['gold'] }, { label: 'Cash', value: a.cash, color: C['cash'] },
  ]);
  subhead('Why this allocation');
  para(risk.narrative.rationale, { after: 3 });
  subhead('Worth considering', C['good']);
  bullets(risk.narrative.considerations, C['good']);
  subhead('Worth avoiding', C['warn']);
  bullets(risk.narrative.avoid, C['warn']);

  /* ---- Priorities ---- */
  section('4. Your monthly money, in order');
  para(plan.priorities.intro, { size: 9.5, color: C['muted'], after: 3 });
  plan.priorities.items.forEach((it, i) => {
    ensure(14);
    fill(C['track']); doc.roundedRect(M, y, 6, 6, 1.2, 1.2, 'F');
    font('bold', 9, C['muted']); doc.text(String(i + 1), M + 3, y + 4.2, { align: 'center' });
    font('bold', 10.5); doc.text(pdfText(it.title), M + 9, y + 4.2);
    if (it.amount) doc.text(pdfText(it.amount), M + CW, y + 4.2, { align: 'right' });
    y += 6.5;
    para(it.detail, { indent: 9, size: 9.5, color: C['muted'], width: CW - 9 - 30, after: 3 });
  });

  /* ---- Plan ---- */
  section('5. Investment plan');
  statGrid([
    { l: 'Monthly investment', n: formatINR(plan.monthly) },
    { l: 'Time horizon', n: `${plan.years}${plan.years === 1 ? ' year' : ' years'}` },
    { l: 'Blended return (assumed)', n: `${p.blendedRate.toFixed(1)}% a year` },
    { l: 'Projected value', n: formatINR(p.totalProjected), hint: `In today's money: ${formatINR(p.realValue)}` },
    { l: 'You put in', n: formatINR(p.totalInvested), hint: plan.stepUp ? `Rising ${plan.stepUp}% every year` : 'The same amount every month' },
    { l: 'Projected growth', n: formatINR(p.totalGain) },
    { l: 'Likely range', n: `${compactINR(p.totalLow)} – ${compactINR(p.totalHigh)}`, hint: 'If returns run lower or higher than assumed' },
    { l: 'After fees and tax', n: formatINR(p.totalAfterFeesAndTax), hint: `Estimate, at ${p.slabAssumed ? 'an assumed ' : 'your '}${p.slab}% slab` },
  ], 3);
  para(`Equity routed through: ${plan.vehicles.map((v) => VEHICLE_LABELS[v]).join(' and ')}.`, { size: 9, color: C['muted'], after: 3 });
  subhead('How it grows');
  growthChart(p.series);
  ensure(tableHeight(p.items.length + 1));
  subhead('Suggested bifurcation');
  table([{ h: 'Instrument', w: 0.46 }, { h: 'Monthly', w: 0.18, align: 'right' }, { h: 'Rate', w: 0.12, align: 'right' }, { h: `In ${plan.years}y`, w: 0.24, align: 'right' }],
    p.items.map((it) => [it.label, formatINR(it.monthly), `${it.rate.toFixed(1)}%`, formatINR(it.projected)])
      .concat([['Total', formatINR(plan.monthly), `${p.blendedRate.toFixed(1)}%`, formatINR(p.totalProjected)]]), { totalLast: true });

  const mixSection = (title: string, shareHeader: string, mix: MixView | undefined) => {
    if (!mix) return;
    ensure(tableHeight(mix.parts.length) + 14);
    subhead(title);
    para(mix.intro, { size: 9.5, color: C['muted'], after: 3 });
    table([{ h: 'Type', w: 0.6 }, { h: shareHeader, w: 0.2, align: 'right' }, { h: 'Monthly', w: 0.2, align: 'right' }],
      mix.parts.map((part) => [part.label, `${Math.round(part.pct)}%`, formatINR(part.monthly)]));
    mix.parts.forEach((part) => {
      para(part.label, { style: 'bold', size: 9.5, after: 0 });
      para(part.what, { size: 9, color: C['muted'], after: 2 });
    });
    bullets(mix.notes, C['accent']);
  };
  mixSection('Your mutual fund mix', 'Share of fund money', plan.mixes.fund);
  mixSection('Your direct shares, by sector', 'Share of direct shares', plan.mixes.direct);
  mixSection('Your debt mix: deposits and debt funds', 'Share of debt money', plan.mixes.debt);
  mixSection('Your gold mix: ETF and Sovereign Gold Bonds', 'Share of gold money', plan.mixes.gold);
  mixSection('Your cash mix: savings account and liquid fund', 'Share of cash', plan.mixes.cash);

  /* ---- Assumptions ---- */
  section('Assumptions and notes');
  bullets([
    'Illustrative long-term returns a year: large cap / index funds 11%, flexi cap 11.5%, mid cap 12.5%, small cap 13.5%, direct equity 13%, FD / RD 6.5% (7% for retirees), short duration debt funds 6.8%, corporate bond funds 7%, Sovereign Gold Bonds 8.75% (gold price plus the bonds\' interest, which is 2.5% of an issue price well below today\'s), gold ETFs 8%, liquid funds 6%, savings accounts 3%. Real returns vary, and can be negative in any year.',
    `"Today's money" discounts the projection by ${plan.inflationPct}% inflation a year.`,
    'The range projects equity and gold 3–4% a year lower and higher than assumed, debt 1% and cash 0.5% — a spread of plausible long-run outcomes, not a guarantee of either end.',
    '"After fees and tax" takes off typical Direct-plan fees (0.2–0.7% a year) and estimates tax under FY 2025-26 rules: equity gains 12.5% above Rs 1.25 lakh (20% within a year), gold ETFs 12.5%, debt and liquid funds and deposit interest at your slab, Sovereign Gold Bonds tax-free at maturity, plus 4% cess. The slab comes from your income under the new regime and ignores the Rs 12 lakh rebate, so it may overstate tax.',
    `Projections assume the monthly amount stays the same${plan.stepUp ? ` apart from the ${plan.stepUp}% yearly step-up` : ''} and nothing is withdrawn until the end. The headline projection is before fees and tax; the after-tax figure is shown separately.`,
    'The score, risk band and plan are computed from your answers by fixed rules; a language model only wrote the explanations. Anything you marked "I don\'t know" was handled as described in section 1.',
    'This report is educational and is not financial advice. Check tax rules, and any product, before acting on it.',
  ], C['muted']);

  /* ---- Footer on every page ---- */
  const pages = doc.getNumberOfPages();
  for (let pg = 1; pg <= pages; pg++) {
    doc.setPage(pg);
    stroke(C['rule']); doc.setLineWidth(0.2); doc.line(M, H - 12, M + CW, H - 12);
    font('normal', 7.5, C['muted']);
    doc.text('Money Compass · Educational content only, not financial advice', M, H - 8);
    doc.text(`Page ${pg} of ${pages}`, M + CW, H - 8, { align: 'right' });
  }
  return doc;
}
