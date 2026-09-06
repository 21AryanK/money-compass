/**
 * Wire types, one to one with the API contract in section 5 of the build
 * specification. Written against the contract rather than against a running
 * server, so the questionnaire, score and risk services compile today and start
 * returning data once Phases 2, 4 and 6 land.
 */

export type ProfileType = 'STUDENT' | 'PROFESSIONAL' | 'RETIREE';
export type QuestionType = 'SINGLE' | 'MULTI' | 'NUMBER' | 'SCALE';
export type RiskBand = 'CONSERVATIVE' | 'MODERATE' | 'BALANCED' | 'GROWTH' | 'AGGRESSIVE';

export interface TokenResponse {
  token: string;
  expiresAt: string;
}

export interface MeResponse {
  id: string;
  email: string;
  profileType: ProfileType;
}

export interface Question {
  code: string;
  text: string;
  type: QuestionType;
  /** Present for SINGLE and MULTI. Key is the value stored, value is the label. */
  options?: Record<string, string>;
  category: string;
}

export interface Progress {
  answered: number;
  estimatedTotal: number;
}

export interface StartSessionResponse {
  sessionId: string;
  firstQuestion: Question;
}

export interface NextQuestionResponse {
  /** null once the questionnaire is complete. */
  question: Question | null;
  progress: Progress;
}

export interface AnswerResponse {
  nextQuestion: Question | null;
}

/** Shape depends on question type: one of these keys is present. */
export interface AnswerValue {
  selected?: string | string[];
  number?: number;
  scale?: number;
}

export interface Narrative {
  summary: string;
  strengths: string[];
  gaps: string[];
  nextSteps: string[];
}

export interface LiteracyScoreResponse {
  sessionId: string;
  total: number;
  categoryBreakdown: Record<string, number>;
  narrative: Narrative;
  providerUsed: string;
  modelUsed: string;
  disclaimer: string;
}

export interface RiskNarrative {
  rationale: string;
  considerations: string[];
  avoid: string[];
}

export interface RiskProfileResponse {
  sessionId: string;
  toleranceScore: number;
  capacityScore: number;
  riskBand: RiskBand;
  allocationPercent: Record<string, number>;
  narrative: RiskNarrative;
  providerUsed: string;
  disclaimer: string;
}

export interface ProviderHealth {
  provider: string;
  model: string;
  up: boolean;
}

export interface AiHealthResponse {
  primary: ProviderHealth;
  fallback: ProviderHealth;
  circuitState: string;
}

/** RFC 7807 problem detail, which is what every error from this API is. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  errors?: Record<string, string>;
}
