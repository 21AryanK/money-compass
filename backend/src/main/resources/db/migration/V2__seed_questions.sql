-- Money Compass question bank. Phase 2.
--
-- Rubric shapes, by question type (see LiteracyScoringService for how each
-- is scored):
--   SINGLE, one correct answer .... {"correct": "<code>", "points": N}
--   SINGLE, ordinal/behavioural ... {"optionPoints": {"<code>": points, ...}}
--   MULTI .......................... {"correctSet": ["<code>", ...], "points": N}
--   NUMBER ......................... {"numberThresholds": [{"min": x, "points": N}, ...]}
--                                     (highest satisfied threshold wins; the answer
--                                     is always phrased so that a larger number is better)
--   SCALE (1-5 self-assessment) .... {"scale": {"1": 0, "2": 3, "3": 6, "4": 8, "5": 10}}
--
-- `code` is the stable key everything else (answers, rules, RiskAssessmentService)
-- references; do not rename an existing code without a follow-up migration.

-- ---------------------------------------------------------------------------
-- BUDGETING
-- ---------------------------------------------------------------------------
INSERT INTO questions (code, text, type, options, category, applicable_profiles, weight, rubric) VALUES
('budgeting_expense_tracking',
 'How do you currently track your monthly expenses?',
 'SINGLE',
 '{"A":"I don''t track them at all","B":"Rough mental estimate","C":"Spreadsheet or app, checked occasionally","D":"Detailed tracking, reviewed every month"}'::jsonb,
 'BUDGETING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"optionPoints":{"A":0,"B":3,"C":7,"D":10}}'::jsonb),

('budgeting_savings_rate',
 'Roughly what percentage of your monthly income do you save or invest, on average?',
 'NUMBER', NULL,
 'BUDGETING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"numberThresholds":[{"min":20,"points":10},{"min":10,"points":6},{"min":0,"points":2}]}'::jsonb),

('budgeting_needs_wants',
 'Which of these is typically a "want" rather than a "need"?',
 'SINGLE',
 '{"A":"Rent","B":"Groceries","C":"Streaming subscriptions","D":"Electricity bill"}'::jsonb,
 'BUDGETING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"correct":"C","points":10}'::jsonb);

-- ---------------------------------------------------------------------------
-- SAVING
-- ---------------------------------------------------------------------------
INSERT INTO questions (code, text, type, options, category, applicable_profiles, weight, rubric) VALUES
('saving_emergency_fund_months',
 'How many months of essential expenses do you have saved in an easily accessible account (like a savings account)?',
 'NUMBER', NULL,
 'SAVING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 2,
 '{"numberThresholds":[{"min":6,"points":10},{"min":3,"points":6},{"min":1,"points":3},{"min":0,"points":0}]}'::jsonb),

('saving_fd_vs_savings',
 'Which of these typically earns a higher rate of interest?',
 'SINGLE',
 '{"A":"A regular savings account","B":"A fixed deposit (FD)","C":"They always pay the same rate","D":"It depends on the bank''s mood that day"}'::jsonb,
 'SAVING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"correct":"B","points":10}'::jsonb),

('saving_automatic_transfer',
 'How do you usually save each month?',
 'SINGLE',
 '{"A":"I save whatever is left over, if anything","B":"I save occasionally, when I remember","C":"I have a recurring deposit or SIP set up","D":"Saving is automated the day I get paid"}'::jsonb,
 'SAVING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"optionPoints":{"A":0,"B":3,"C":7,"D":10}}'::jsonb);

-- ---------------------------------------------------------------------------
-- DEBT
--
-- debt_high_interest_status doubles as an input to RiskAssessmentService's
-- capacity score, and is the trigger question for the debt_payoff_plan
-- conditional question wired up in the question_rules insert below.
-- ---------------------------------------------------------------------------
INSERT INTO questions (code, text, type, options, category, applicable_profiles, weight, rubric) VALUES
('debt_high_interest_status',
 'Do you currently carry credit card or other high-interest (above roughly 12% a year) debt from month to month?',
 'SINGLE',
 '{"A":"Yes","B":"No"}'::jsonb,
 'DEBT', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 2,
 '{"optionPoints":{"A":0,"B":10}}'::jsonb),

('debt_free_income_pct',
 'Roughly what percentage of your monthly income is left after paying all EMIs and debt repayments?',
 'NUMBER', NULL,
 'DEBT', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"numberThresholds":[{"min":90,"points":10},{"min":75,"points":6},{"min":50,"points":3},{"min":0,"points":0}]}'::jsonb),

('debt_good_vs_bad',
 'Which of these are usually considered "good debt" - the kind that can build long-term value? Select all that apply.',
 'MULTI',
 '{"A":"Home loan","B":"Credit card revolving balance","C":"Education loan","D":"Payday loan"}'::jsonb,
 'DEBT', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"correctSet":["A","C"],"points":10}'::jsonb),

('debt_payoff_plan',
 'What''s your plan for paying off that high-interest debt?',
 'SINGLE',
 '{"A":"No plan yet","B":"Paying only the minimums","C":"I have a payoff plan and add extra when I can","D":"Actively following a payoff plan (avalanche or snowball)"}'::jsonb,
 'DEBT', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"optionPoints":{"A":0,"B":2,"C":7,"D":10}}'::jsonb);

-- ---------------------------------------------------------------------------
-- INVESTING
-- ---------------------------------------------------------------------------
INSERT INTO questions (code, text, type, options, category, applicable_profiles, weight, rubric) VALUES
('investing_index_fund_def',
 'What does an index fund do?',
 'SINGLE',
 '{"A":"Actively picks stocks to try to beat the market","B":"Tracks a market index, like the Nifty 50, passively","C":"Guarantees a fixed return","D":"Only invests in gold"}'::jsonb,
 'INVESTING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 2,
 '{"correct":"B","points":10}'::jsonb),

('investing_elss_lockin',
 'What is the minimum lock-in period for ELSS (tax-saving) mutual funds in India?',
 'SINGLE',
 '{"A":"No lock-in","B":"1 year","C":"3 years","D":"5 years"}'::jsonb,
 'INVESTING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"correct":"C","points":10}'::jsonb),

-- STUDENT-only: demonstrates applicable_profiles filtering rather than a
-- question_rules branch.
('investing_first_step',
 'As a student with limited money to invest, what''s a reasonable way to start?',
 'SINGLE',
 '{"A":"Wait until I have a large lump sum","B":"Try to time the market for the perfect entry point","C":"Start a small SIP in an index fund","D":"Put everything into one hot stock tip"}'::jsonb,
 'INVESTING', ARRAY['STUDENT'], 1,
 '{"optionPoints":{"A":2,"B":0,"C":10,"D":0}}'::jsonb);

-- ---------------------------------------------------------------------------
-- COMPOUNDING
-- ---------------------------------------------------------------------------
INSERT INTO questions (code, text, type, options, category, applicable_profiles, weight, rubric) VALUES
('compounding_rule_of_72',
 'Using the Rule of 72, roughly how many years does it take to double your money at 8% annual return?',
 'SINGLE',
 '{"A":"4 years","B":"9 years","C":"14 years","D":"20 years"}'::jsonb,
 'COMPOUNDING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 2,
 '{"correct":"B","points":10}'::jsonb),

('compounding_early_start',
 'Two people invest the same amount every month. Person A starts at age 25 and Person B starts at age 35; both stop contributing at 45 and let it grow untouched until 60, at the same rate of return. Who generally ends up with more?',
 'SINGLE',
 '{"A":"Person A","B":"Person B","C":"They always end up equal","D":"Impossible to know"}'::jsonb,
 'COMPOUNDING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 2,
 '{"correct":"A","points":10}'::jsonb),

('compounding_confidence',
 'Rate your confidence explaining how compound interest works to a friend, from 1 (not at all) to 5 (very confident).',
 'SCALE', NULL,
 'COMPOUNDING', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"scale":{"1":0,"2":3,"3":5,"4":8,"5":10}}'::jsonb);

-- ---------------------------------------------------------------------------
-- RISK
--
-- All three feed RiskAssessmentService's tolerance score directly (it is the
-- RISK category percentage from LiteracyScoringService).
-- ---------------------------------------------------------------------------
INSERT INTO questions (code, text, type, options, category, applicable_profiles, weight, rubric) VALUES
('risk_market_drop_reaction',
 'If your investment portfolio dropped 20% in a month, how would you react? 1 = panic and sell everything, 5 = stay invested, or even buy more.',
 'SCALE', NULL,
 'RISK', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 2,
 '{"scale":{"1":0,"2":3,"3":6,"4":8,"5":10}}'::jsonb),

('risk_horizon',
 'What''s your investment time horizon for most of your savings?',
 'SINGLE',
 '{"A":"Less than 1 year","B":"1 to 3 years","C":"3 to 7 years","D":"7 or more years"}'::jsonb,
 'RISK', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 2,
 '{"optionPoints":{"A":2,"B":4,"C":7,"D":10}}'::jsonb),

('risk_volatility_understanding',
 'Which of these typically has the highest short-term price volatility?',
 'SINGLE',
 '{"A":"Fixed deposit","B":"PPF (Public Provident Fund)","C":"Equity mutual fund","D":"Savings account"}'::jsonb,
 'RISK', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"correct":"C","points":10}'::jsonb);

-- ---------------------------------------------------------------------------
-- RETIREMENT
-- ---------------------------------------------------------------------------
INSERT INTO questions (code, text, type, options, category, applicable_profiles, weight, rubric) VALUES
('retirement_ppf_lockin',
 'What is the standard maturity period of a PPF (Public Provident Fund) account in India?',
 'SINGLE',
 '{"A":"5 years","B":"10 years","C":"15 years","D":"21 years"}'::jsonb,
 'RETIREMENT', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"correct":"C","points":10}'::jsonb),

-- PROFESSIONAL/RETIREE-only: a student rarely has 80C-relevant income yet.
('retirement_nps_tax',
 'Under which section can NPS (National Pension System) contributions get an additional tax benefit beyond the standard 80C limit?',
 'SINGLE',
 '{"A":"80D","B":"80CCD(1B)","C":"80G","D":"80TTA"}'::jsonb,
 'RETIREMENT', ARRAY['PROFESSIONAL','RETIREE'], 1,
 '{"correct":"B","points":10}'::jsonb),

('retirement_readiness',
 'How confident are you that your current savings and investments are on track for retirement?',
 'SCALE', NULL,
 'RETIREMENT', ARRAY['STUDENT','PROFESSIONAL','RETIREE'], 1,
 '{"scale":{"1":0,"2":3,"3":5,"4":8,"5":10}}'::jsonb);

-- ---------------------------------------------------------------------------
-- Branching: answering "Yes" to debt_high_interest_status unlocks
-- debt_payoff_plan. Without this row, debt_payoff_plan would never be
-- eligible, because it is a rule target (see the QuestionnaireService
-- "conditional question" logic).
-- ---------------------------------------------------------------------------
INSERT INTO question_rules (question_code, condition, next_question_code, priority) VALUES
('debt_high_interest_status', '{"question":"debt_high_interest_status","equals":"A"}'::jsonb, 'debt_payoff_plan', 100);
