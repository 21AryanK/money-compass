import { Component, OnDestroy, OnInit, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Subject, Subscription, debounceTime, forkJoin, switchMap } from 'rxjs';
import { MixView, PlanResponse, Vehicle } from '../core/api.types';
import { AssessmentService } from '../core/assessment.service';
import { SessionStore } from '../core/session.store';
import { bandLabel, formatINR } from '../core/format';
import { describeProblem } from '../core/problem';
import { GrowthChartComponent } from '../shared/growth-chart.component';
import { downloadReport } from '../report/pdf-report';

type MixKey = 'fund' | 'direct' | 'debt' | 'gold' | 'cash';

const MIX_TABS: { key: MixKey; tab: string; title: string; link: string; linkText: string }[] = [
  { key: 'fund', tab: 'Mutual funds', title: 'Your mutual fund mix', link: 'https://en.wikipedia.org/wiki/Market_capitalization', linkText: 'What do large, mid and small cap mean? ↗' },
  { key: 'direct', tab: 'Direct shares', title: 'Your direct shares, by sector', link: 'https://en.wikipedia.org/wiki/NIFTY_50', linkText: "What's in the Nifty 50? ↗" },
  { key: 'debt', tab: 'Debt', title: 'Your debt mix: deposits and debt funds', link: 'https://en.wikipedia.org/wiki/Bond_fund', linkText: 'What is a debt fund? ↗' },
  { key: 'gold', tab: 'Gold', title: 'Your gold mix: ETF and Sovereign Gold Bonds', link: 'https://en.wikipedia.org/wiki/Sovereign_Gold_Bond', linkText: 'What is a Sovereign Gold Bond? ↗' },
  { key: 'cash', tab: 'Cash', title: 'Your cash mix: savings account and liquid fund', link: 'https://en.wikipedia.org/wiki/Money_market_fund', linkText: 'What is a liquid (money market) fund? ↗' },
];

/**
 * The investment planner. Every input change asks the server for the plan —
 * the maths is the backend's deterministic planner, not the model — and the
 * inputs are remembered per assessment.
 */
@Component({
  selector: 'mc-plan',
  imports: [GrowthChartComponent],
  template: `
    <section class="stage">
      @if (error()) {
        <p class="app-error" role="alert" style="margin-bottom:16px;">{{ error() }} <button type="button" class="btn btn-ghost btn-sm" (click)="refresh()">Try again</button></p>
      }
      @if (plan(); as p) {
        <div class="glass card" style="margin-bottom:16px;">
          <h3 style="margin-bottom:4px;">Your monthly money, in order</h3>
          <p style="font-size:13px; color:var(--ink-3); margin-bottom:6px;">{{ p.priorities.intro }}</p>
          <ol class="priority-list">
            @for (it of p.priorities.items; track it.title) {
              <li><span><span class="t">{{ it.title }}</span>{{ it.detail }}</span><span class="amt">{{ it.amount }}</span></li>
            }
          </ol>
        </div>

        <div class="glass card">
          <div class="income-recap">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M12 2v20M17 6H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6"/></svg>
            <span>Based on your assessment, your monthly income is <b>{{ p.income !== null ? inr(p.income) + '/mo' : 'not shared' }}</b> — you said you save about <b>{{ round(p.savingsRate) }}%{{ p.rateKnown ? '' : ' (assumed)' }}</b> of it.</span>
          </div>

          <div class="plan-grid">
            <div class="field">
              <label for="plan-amount">Monthly investment amount</label>
              <div class="amount-field">
                <span class="prefix">₹</span>
                <input type="number" id="plan-amount" min="0" step="500" inputmode="numeric" [value]="amountText()" (input)="setAmount($any($event.target).value)" />
                <span class="unit">per month</span>
              </div>
              <p style="font-size:11.5px; color:var(--ink-3); margin-top:2px;">
                Suggested from your income: <b style="color:var(--ink-2); font-weight:600;">{{ inr(p.suggestedMonthly) }}/mo</b> —
                <button type="button" class="text-link" (click)="useSuggested(p.suggestedMonthly)">use it</button>
              </p>
            </div>
            <div class="field">
              <label id="plan-horizon-label">Time horizon</label>
              <div class="horizon-row" role="group" aria-labelledby="plan-horizon-label">
                @for (y of horizons; track y) {
                  <button type="button" class="horizon-btn" [class.selected]="p.years === y" [attr.aria-pressed]="p.years === y" (click)="set({ years: y })">{{ y }} {{ y === 1 ? 'yr' : 'yrs' }}</button>
                }
              </div>
            </div>
          </div>

          <div class="field" style="margin-top:16px;">
            <label id="plan-stepup-label">Raise my monthly investment every year by</label>
            <div class="horizon-row" role="group" aria-labelledby="plan-stepup-label">
              @for (s of stepUps; track s) {
                <button type="button" class="horizon-btn" [class.selected]="p.stepUp === s" [attr.aria-pressed]="p.stepUp === s" (click)="set({ stepUp: s })">{{ s === 0 ? 'Keep it flat' : s + '% a year' }}</button>
              }
            </div>
            <p style="font-size:11.5px; color:var(--ink-3); margin-top:2px;">A "step-up SIP": most incomes rise every year, and raising your SIP with them makes a big difference over time.</p>
          </div>

          <div class="field" style="margin-top:16px;">
            <label id="plan-vehicle-label">For the equity portion of your money, which would you like to use?</label>
            <div class="vehicle-row" role="group" aria-labelledby="plan-vehicle-label">
              <button type="button" class="vehicle-card" [class.selected]="has('mf')" [attr.aria-pressed]="has('mf')" (click)="toggleVehicle('mf')">
                <span class="box"></span>
                <span><span class="t">Mutual funds</span><br /><span class="d">Index / equity funds — diversified, lower effort</span></span>
              </button>
              <button type="button" class="vehicle-card" [class.selected]="has('direct')" [attr.aria-pressed]="has('direct')" (click)="toggleVehicle('direct')">
                <span class="box"></span>
                <span><span class="t">Direct equity shares</span><br /><span class="d">Individual stocks — more upside, more risk</span></span>
              </button>
            </div>
          </div>
          <p class="disclaimer" style="margin-top:14px;">This calculator uses illustrative long-term average returns, not AI, not a live model — it's the same "deterministic core" the rest of Money Compass uses. Nothing here is a guarantee: markets can and do go down.</p>
        </div>

        <div class="glass card plan-hero" [style.opacity]="updating() ? 0.6 : 1">
          <span class="eyebrow-label">Projected value in {{ p.years }} years</span>
          <div class="headline">{{ inr(p.projection.totalProjected) }}</div>
          <p class="sub">You'll have put in <b>{{ inr(p.projection.totalInvested) }}</b> yourself — the rest, <b>{{ inr(p.projection.totalGain) }}</b>, is projected growth.</p>
          <p class="sub" style="margin-top:4px;">That's about <b>{{ inr(p.projection.realValue) }}</b> in today's money, after {{ p.inflationPct }}% a year inflation.</p>
          <p class="sub" style="margin-top:4px;">If returns run lower or higher than assumed, it could land anywhere from <b>{{ inr(p.projection.totalLow) }}</b> to <b>{{ inr(p.projection.totalHigh) }}</b>.</p>
          <p class="sub" style="margin-top:4px;">After fund fees and the tax due when you withdraw: about <b>{{ inr(p.projection.totalAfterFeesAndTax) }}</b> (an estimate, at {{ p.projection.slabAssumed ? 'an assumed ' : 'your ' }}{{ p.projection.slab }}% income-tax slab).</p>
          <div class="plan-stats">
            <div class="plan-stat"><div class="l">Monthly amount</div><div class="n">{{ inr(p.monthly) }}</div></div>
            <div class="plan-stat"><div class="l">Blended return (assumed)</div><div class="n">{{ p.projection.blendedRate.toFixed(1) }}%/yr</div></div>
            <div class="plan-stat"><div class="l">Risk band driving the split</div><div class="n">{{ band(p.band) }}</div></div>
            <div class="plan-stat"><div class="l">Yearly step-up</div><div class="n">{{ p.stepUp ? p.stepUp + '% a year' : 'None' }}</div></div>
          </div>
        </div>

        <div class="glass card" style="margin-top:16px;">
          <h3 style="margin-bottom:14px;">How it grows</h3>
          <div class="growth-chart-wrap">
            <div class="growth-chart-legend">
              <span class="lg"><span class="ln" style="border-color:var(--gold-bright);"></span>Projected value</span>
              <span class="lg"><span class="ln" style="border-color:var(--ink-3); border-top-style:dashed;"></span>Amount invested</span>
              <span class="lg"><span class="band-swatch"></span>Range if returns run lower or higher</span>
            </div>
            <mc-growth-chart [series]="p.projection.series" />
          </div>
        </div>

        <div class="glass card" style="margin-top:16px;">
          <h3 style="margin-bottom:6px;">Suggested bifurcation</h3>
          <p style="font-size:13px; color:var(--ink-3); margin-bottom:16px;">Split by your {{ band(p.band).toLowerCase() }} risk band, then routed through the instruments you picked above.</p>
          <div class="alloc-bar">
            @for (it of p.projection.items; track it.key) {
              <div class="alloc-seg" [style.background]="it.color" [style.width.%]="share(p, it.monthly)" [title]="it.label + ' — ' + inr(it.monthly) + '/mo'">{{ share(p, it.monthly) >= 12 ? round(share(p, it.monthly)) + '%' : '' }}</div>
            }
          </div>
          <hr class="hairline" style="margin:20px 0 4px;" />
          <div class="instrument-row instrument-head"><span>Instrument</span><span class="num">Monthly</span><span class="num rate-col">Rate</span><span class="num">In {{ p.years }}y</span></div>
          @for (it of p.projection.items; track it.key) {
            <div class="instrument-row">
              <span class="name"><span class="sw" [style.background]="it.color"></span>{{ it.label }}</span>
              <span class="num">{{ inr(it.monthly) }}</span>
              <span class="num rate-col">{{ it.rate.toFixed(1) }}%</span>
              <span class="num value">{{ inr(it.projected) }}</span>
            </div>
          }
        </div>

        @if (tabs(p).length) {
          <div class="glass card" style="margin-top:16px;">
            <h3 style="margin-bottom:4px;">Fund-by-fund breakdown</h3>
            <p style="font-size:13px; color:var(--ink-3); margin-bottom:12px;">Where each part of the plan above actually goes.</p>
            <div class="mix-tabs horizon-row" role="tablist" aria-label="Breakdown by part of the plan">
              @for (t of tabs(p); track t.key) {
                <button type="button" class="horizon-btn" role="tab" [class.selected]="activeTab(p) === t.key" [attr.aria-selected]="activeTab(p) === t.key"
                        [tabIndex]="activeTab(p) === t.key ? 0 : -1" (click)="mixTab.set(t.key)" (keydown)="tabKey($event, p)">{{ t.tab }}</button>
              }
            </div>
            @for (t of tabs(p); track t.key) {
              @if (activeTab(p) === t.key) {
                <div class="mix-panel" role="tabpanel">
                  <div style="display:flex; align-items:baseline; justify-content:space-between; flex-wrap:wrap; gap:8px;">
                    <h3 style="margin-bottom:4px;">{{ t.title }}</h3>
                    <a class="text-link" [href]="t.link" target="_blank" rel="noopener noreferrer">{{ t.linkText }}</a>
                  </div>
                  <p style="font-size:13px; color:var(--ink-3); margin-bottom:12px;">{{ mix(p, t.key).intro }}</p>
                  @for (part of mix(p, t.key).parts; track part.key) {
                    <div class="fund-row">
                      <span class="name"><span class="sw" [style.background]="part.color"></span>{{ part.label }}</span>
                      <span class="num">{{ round(part.pct) }}%</span>
                      <span class="num value">{{ inr(part.monthly) }}/mo</span>
                      <span class="what">{{ part.what }}</span>
                    </div>
                  }
                  <ul class="fund-mix-notes">@for (n of mix(p, t.key).notes; track $index) { <li>{{ n }}</li> }</ul>
                </div>
              }
            }
          </div>
        }

        <div class="risk-cta-row">
          <button class="btn btn-primary" type="button" (click)="download()" [disabled]="downloading()">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12M7 10l5 5 5-5M5 21h14"/></svg>
            <span>{{ downloading() ? 'Preparing your report…' : 'Download report (PDF)' }}</span>
          </button>
          <button class="btn btn-ghost" (click)="go('/dashboard')">Back to dashboard</button>
          <button class="btn btn-ghost" (click)="go('/results/' + sessionId() + '/risk')">← Risk profile</button>
        </div>
        <p class="download-status" role="status" aria-live="polite">{{ downloadStatus() }}</p>
      } @else if (!error()) {
        <div class="glass card loading-card"><div class="orb"></div><div>Building your plan…</div></div>
      }
    </section>
  `,
})
export class PlanPage implements OnInit, OnDestroy {

  readonly sessionId = input.required<string>();

  private readonly api = inject(AssessmentService);
  private readonly store = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly horizons = [1, 3, 5, 10, 15, 20];
  protected readonly stepUps = [0, 5, 10];
  protected readonly plan = signal<PlanResponse | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly updating = signal(false);
  protected readonly downloading = signal(false);
  protected readonly downloadStatus = signal('');
  protected readonly mixTab = signal<MixKey>('fund');
  protected readonly amountText = signal('');
  private readonly changes = new Subject<void>();
  private sub?: Subscription;

  protected readonly inr = formatINR;
  protected readonly round = Math.round;

  ngOnInit(): void {
    this.store.useSession(this.sessionId());
    // Typing in the amount shouldn't send a request per keystroke; a new
    // request also cancels one still in flight.
    this.sub = this.changes.pipe(
      debounceTime(250),
      switchMap(() => { this.updating.set(true); return this.api.plan(this.sessionId(), this.store.planRequest()); }),
    ).subscribe({
      next: (p) => this.apply(p),
      error: (err) => { this.updating.set(false); this.error.set(describeProblem(err)); },
    });
    this.refresh();
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  protected refresh(): void {
    this.error.set(null);
    this.api.plan(this.sessionId(), this.store.planRequest()).subscribe({
      next: (p) => {
        this.apply(p);
        this.amountText.set(String(Math.round(p.monthly)));
        // Remember where the plan started from, so coming back shows the same one.
        if (this.store.planInputs().years === null) this.store.setPlanInputs({ years: p.years });
      },
      error: (err) => this.error.set(describeProblem(err)),
    });
  }

  private apply(p: PlanResponse): void {
    this.updating.set(false);
    this.plan.set(p);
  }

  protected set(change: { years?: number; stepUp?: number }): void {
    this.store.setPlanInputs(change);
    this.plan.update((p) => p ? { ...p, ...change } : p);
    this.changes.next();
  }

  protected setAmount(raw: string): void {
    this.amountText.set(raw);
    const v = parseFloat(raw);
    this.store.setPlanInputs({ monthly: Number.isNaN(v) || v < 0 ? 0 : v });
    this.changes.next();
  }

  protected useSuggested(amount: number): void {
    this.amountText.set(String(Math.round(amount)));
    this.store.setPlanInputs({ monthly: amount });
    this.changes.next();
  }

  protected has(v: Vehicle): boolean {
    return this.store.planInputs().vehicles.includes(v);
  }

  /** At least one vehicle stays selected. */
  protected toggleVehicle(v: Vehicle): void {
    const current = this.store.planInputs().vehicles;
    if (current.includes(v) && current.length === 1) return;
    const next = current.includes(v) ? current.filter((x) => x !== v) : [...current, v];
    this.store.setPlanInputs({ vehicles: next });
    this.changes.next();
  }

  protected band(b: PlanResponse['band']): string {
    return bandLabel(b);
  }

  protected share(p: PlanResponse, monthly: number): number {
    return p.monthly > 0 ? (monthly / p.monthly) * 100 : 0;
  }

  protected tabs(p: PlanResponse) {
    return MIX_TABS.filter((t) => !!p.mixes[t.key]);
  }

  protected activeTab(p: PlanResponse): MixKey {
    const available = this.tabs(p);
    return available.some((t) => t.key === this.mixTab()) ? this.mixTab() : (available[0]?.key ?? 'fund');
  }

  protected mix(p: PlanResponse, key: MixKey): MixView {
    return p.mixes[key]!;
  }

  /** Arrow keys move between tabs, as screen-reader users expect. */
  protected tabKey(e: KeyboardEvent, p: PlanResponse): void {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    const available = this.tabs(p);
    const i = available.findIndex((t) => t.key === this.activeTab(p));
    const next = available[(i + (e.key === 'ArrowRight' ? 1 : available.length - 1)) % available.length];
    this.mixTab.set(next.key);
    e.preventDefault();
  }

  protected go(url: string): void {
    void this.router.navigateByUrl(url);
  }

  protected download(): void {
    const p = this.plan();
    if (!p) return;
    this.downloading.set(true);
    this.downloadStatus.set('');
    forkJoin({ score: this.store.loadScore(this.sessionId()), risk: this.store.loadRisk(this.sessionId()) }).subscribe({
      next: async ({ score, risk }) => {
        try {
          const file = await downloadReport({ score, risk, plan: p });
          this.downloadStatus.set(`Saved ${file}.`);
        } catch {
          this.downloadStatus.set("Couldn't build the PDF. Please try again.");
        } finally {
          this.downloading.set(false);
        }
      },
      error: (err) => { this.downloading.set(false); this.downloadStatus.set(describeProblem(err)); },
    });
  }
}
