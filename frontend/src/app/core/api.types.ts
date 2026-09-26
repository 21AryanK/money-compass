/**
 * Wire types, one to one with the backend's DTOs. Where a field is nullable on
 * the server (an unknown answer, a category not assessed for this profile) it
 * is nullable here too, and the screens say so rather than showing a zero.
 */

export type ProfileType = 'STUDENT' | 'PROFESSIONAL' | 'RETIREE';
export type QuestionType = 'SINGLE' | 'MULTI' | 'NUMBER' | 'SCALE';
export type RiskBand = 'CONSERVATIVE' | 'MODERATE' | 'BALANCED' | 'GROWTH' | 'AGGRESSIVE';
export type Category = 'BUDGETING' | 'SAVING' | 'DEBT' | 'INVESTING' | 'COMPOUNDING' | 'RISK' | 'RETIREMENT';

export const BANDS: RiskBand[] = ['CONSERVATIVE', 'MODERATE', 'BALANCED', 'GROWTH', 'AGGRESSIVE'];

export interface TokenResponse {
  token: string;
  expiresAt: string;
}

export interface MeResponse {
  id: string;
  email: string;
  profileType: ProfileType;
}

// ------------------------------------------------------------ questionnaire

export interface Question {
  code: string;
  text: string;
  type: QuestionType;
  /** SINGLE and MULTI only. Key is the value stored, value is the label. */
  options: Record<string, string> | null;
  category: Category;
  hint: string | null;
  unit: string | null;
  min: number | null;
  max: number | null;
  scaleLabels: string[] | null;
  /** Why the flow just grew: a simpler re-ask, or a follow-up's reason. */
  followUpNote: string | null;
  /** What answering "I don't know" will do on this question. */
  unknownConsequence: string;
  wikiTitle: string | null;
  wikiUrl: string | null;
  /** Answering (other than "I don't know") would end the questionnaire. */
  last: boolean;
}

export interface Progress {
  answered: number;
  estimatedTotal: number;
  skippedAdvanced: number;
}

export interface StartSessionResponse {
  sessionId: string;
  firstQuestion: Question | null;
  progress: Progress;
}

export interface NextQuestionResponse {
  question: Question | null;
  progress: Progress;
}

export interface AnswerResponse {
  nextQuestion: Question | null;
  progress: Progress;
}

export interface BackResponse {
  question: Question;
  previousValue: AnswerValue;
  progress: Progress;
}

/** One of these keys is present, depending on question type — or `unknown` for "I don't know". */
export interface AnswerValue {
  selected?: string | string[];
  number?: number;
  scale?: number;
  unknown?: boolean;
}

// ------------------------------------------------------------ results

export interface MissedTopic {
  code: string;
  topic: string;
  unknown: boolean;
}

/** The user's own numbers. Option-code fields hold the code, "?" for "I don't know", or null if not asked. */
export interface FinancialSnapshot {
  profile: ProfileType;
  age: number | null;
  yearsToRetire: number | null;
  income: number | null;
  savingsRate: number;
  rateKnown: boolean;
  spend: number | null;
  saving: number | null;
  efMonths: number | null;
  efTargetMonths: number;
  efTarget: number | null;
  efHave: number | null;
  efGap: number | null;
  highInterestDebt: boolean | null;
  payoffPlan: string | null;
  horizon: string | null;
  horizonYears: number | null;
  dropScale: number | null;
  tracking: string | null;
  autoSave: string | null;
  emiBand: string | null;
  dependents: string | null;
  termCover: string | null;
  healthCover: string | null;
  corpusMultiple: number | null;
  runwayYears: number | null;
  runwayAsked: boolean;
  retireeDebt: string | null;
  healthFund: string | null;
  nomination: string | null;
  missedTopics: MissedTopic[];
}

export interface Waterfall {
  budget: number;
  toDebt: number;
  toEf: number;
  toInvest: number;
  efMonthsToFill: number;
}

export interface Narrative {
  summary: string;
  strengths: string[];
  gaps: string[];
  nextSteps: string[];
}

export interface RiskNarrative {
  rationale: string;
  considerations: string[];
  avoid: string[];
}

/** One draft of an AI-written card. provider "template" means no model answered. */
export interface Draft<T> {
  content: T;
  provider: string;
  model: string;
  latencyMs: number;
  tokens: number | null;
  createdAt: string;
}

export interface LiteracyScoreResponse {
  sessionId: string;
  total: number;
  /** null = not assessed for this profile. */
  categoryBreakdown: Record<Category, number | null>;
  bandTitle: string;
  bandSub: string;
  unknownNote: string;
  snapshot: FinancialSnapshot;
  waterfall: Waterfall | null;
  narrative: Narrative;
  drafts: Draft<Narrative>[];
  providerUsed: string;
  modelUsed: string;
  disclaimer: string;
}

export interface CapacityFactor {
  label: string;
  delta: number;
}

export interface Allocation {
  equity: number;
  debt: number;
  gold: number;
  cash: number;
}

export interface RiskProfileResponse {
  sessionId: string;
  toleranceScore: number;
  capacityScore: number;
  /** What the answers calculated — never changed by the user's choice. */
  riskBand: RiskBand;
  /** What's being planned around: the calculated band, or the user's pick. */
  activeBand: RiskBand;
  allocationPercent: Allocation;
  capacityFactors: CapacityFactor[];
  snapshot: FinancialSnapshot;
  narrative: RiskNarrative;
  drafts: Draft<RiskNarrative>[];
  providerUsed: string;
  modelUsed: string;
  disclaimer: string;
}

// ------------------------------------------------------------ plan

export type Vehicle = 'mf' | 'direct';

export interface PlanRequest {
  monthly?: number;
  years?: number;
  vehicles?: Vehicle[];
  stepUp?: number;
}

export interface MixPart {
  key: string;
  label: string;
  what: string;
  rate: number;
  color: string;
  pct: number;
  monthly: number;
}

export interface MixView {
  intro: string;
  parts: MixPart[];
  notes: string[];
  total: number;
}

export interface PlanItem {
  key: string;
  label: string;
  color: string;
  monthly: number;
  rate: number;
  fee: number;
  tax: string;
  spread: number;
  projected: number;
  invested: number;
  gain: number;
  netValue: number;
}

export interface YearPoint {
  year: number;
  invested: number;
  value: number;
  low: number;
  high: number;
}

export interface Projection {
  items: PlanItem[];
  totalInvested: number;
  totalProjected: number;
  totalGain: number;
  blendedRate: number;
  series: YearPoint[];
  totalLow: number;
  totalHigh: number;
  totalAfterFeesAndTax: number;
  taxEstimate: number;
  slab: number;
  slabAssumed: boolean;
  stepUp: number;
  realValue: number;
}

export interface Priority {
  title: string;
  detail: string;
  amount: string;
}

export interface PlanResponse {
  monthly: number;
  years: number;
  vehicles: Vehicle[];
  stepUp: number;
  band: RiskBand;
  allocation: Allocation;
  suggestedMonthly: number;
  income: number | null;
  savingsRate: number;
  rateKnown: boolean;
  priorities: { intro: string; items: Priority[] };
  projection: Projection;
  mixes: Partial<Record<'fund' | 'direct' | 'debt' | 'gold' | 'cash', MixView>>;
  inflationPct: number;
}

// ------------------------------------------------------------ assistant

export interface ChatTurn {
  role: 'user' | 'assistant';
  content: string;
}

export interface ChatRequest {
  message: string;
  history: ChatTurn[];
  screen: string;
  planMonthly?: number;
  planYears?: number;
}

export interface ChatResponse {
  reply: string;
  followUps: string[];
  /** "rules" when no model answered and the grounded answer was returned as-is. */
  provider: string;
  model: string;
  latencyMs: number;
  tokens: number | null;
}

export interface ChatIntro {
  greeting: string;
  suggestions: string[];
  provider: string;
  model: string;
}

// ------------------------------------------------------------ history, health, errors

export type SessionStatus = 'IN_PROGRESS' | 'COMPLETED';

export interface SessionSummary {
  sessionId: string;
  status: SessionStatus;
  profileType: ProfileType;
  startedAt: string;
  completedAt: string | null;
  answered: number;
  total: number | null;
  band: RiskBand | null;
}

export interface ProviderHealth {
  provider: string;
  model: string;
  up: boolean;
  probeMs: number | null;
}

export interface AiHealthResponse {
  primary: ProviderHealth;
  fallback: ProviderHealth;
  circuitState: string;
  checkedAt: string;
}

/** RFC 7807 problem detail, which is what every error from this API is. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  errors?: Record<string, string>;
}
