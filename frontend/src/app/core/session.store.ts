import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import {
  LiteracyScoreResponse, PlanRequest, RiskProfileResponse, SessionSummary, Vehicle,
} from './api.types';
import { AssessmentService } from './assessment.service';

/** What the plan screen had set, kept per session so coming back restores it. */
export interface PlanInputs {
  monthly: number | null;
  years: number | null;
  vehicles: Vehicle[];
  stepUp: number;
}

const PLAN_KEY = 'money-compass-plan-';
const DEFAULT_PLAN: PlanInputs = { monthly: null, years: null, vehicles: ['mf', 'direct'], stepUp: 0 };

/**
 * App-wide state for the assessment whose results are on screen: its id, the
 * score and risk responses (fetched once, then updated by regenerate and band
 * changes), the plan inputs, and the list of the user's sessions for the
 * dashboard and the rail's enabled links.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {

  private readonly api = inject(AssessmentService);

  readonly sessions = signal<SessionSummary[] | null>(null);
  readonly resultsSessionId = signal<string | null>(null);
  readonly score = signal<LiteracyScoreResponse | null>(null);
  readonly risk = signal<RiskProfileResponse | null>(null);
  readonly planInputs = signal<PlanInputs>({ ...DEFAULT_PLAN });

  /** The most recent finished assessment, which the rail's result links open. */
  readonly latestCompleted = computed(() => this.sessions()?.find((s) => s.status === 'COMPLETED') ?? null);
  /** An assessment started but not finished, offered for resuming. */
  readonly inProgress = computed(() => {
    const list = this.sessions();
    if (!list?.length) return null;
    const newest = list[0];
    return newest.status === 'IN_PROGRESS' && newest.answered > 0 ? newest : null;
  });

  refreshSessions(): Observable<SessionSummary[]> {
    return this.api.sessions().pipe(tap((list) => {
      this.sessions.set(list);
      if (!this.resultsSessionId()) {
        const latest = list.find((s) => s.status === 'COMPLETED');
        if (latest) this.useSession(latest.sessionId);
      }
    }));
  }

  /** Points the results screens at a session, dropping anything cached for another one. */
  useSession(sessionId: string): void {
    if (this.resultsSessionId() === sessionId) return;
    this.resultsSessionId.set(sessionId);
    this.score.set(null);
    this.risk.set(null);
    this.planInputs.set(this.readPlan(sessionId));
  }

  loadScore(sessionId: string): Observable<LiteracyScoreResponse> {
    this.useSession(sessionId);
    const cached = this.score();
    if (cached && cached.sessionId === sessionId) return of(cached);
    return this.api.computeScore(sessionId).pipe(tap((s) => this.score.set(s)));
  }

  loadRisk(sessionId: string): Observable<RiskProfileResponse> {
    this.useSession(sessionId);
    const cached = this.risk();
    if (cached && cached.sessionId === sessionId) return of(cached);
    return this.api.computeRisk(sessionId).pipe(tap((r) => this.risk.set(r)));
  }

  setPlanInputs(inputs: Partial<PlanInputs>): void {
    const next = { ...this.planInputs(), ...inputs };
    this.planInputs.set(next);
    const id = this.resultsSessionId();
    if (!id) return;
    try {
      localStorage.setItem(PLAN_KEY + id, JSON.stringify(next));
    } catch {
      // Storage unavailable: the plan still works, it just isn't remembered.
    }
  }

  planRequest(): PlanRequest {
    const p = this.planInputs();
    return {
      monthly: p.monthly ?? undefined,
      years: p.years ?? undefined,
      vehicles: p.vehicles,
      stepUp: p.stepUp,
    };
  }

  /** Called on sign-out, so the next user starts clean. */
  clear(): void {
    this.sessions.set(null);
    this.resultsSessionId.set(null);
    this.score.set(null);
    this.risk.set(null);
    this.planInputs.set({ ...DEFAULT_PLAN });
  }

  private readPlan(sessionId: string): PlanInputs {
    try {
      const saved = JSON.parse(localStorage.getItem(PLAN_KEY + sessionId) ?? 'null') as Partial<PlanInputs> | null;
      return saved ? { ...DEFAULT_PLAN, ...saved } : { ...DEFAULT_PLAN };
    } catch {
      return { ...DEFAULT_PLAN };
    }
  }
}
