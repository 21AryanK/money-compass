# Money Compass — interactive prototype

A single self-contained `index.html` (no build step, no dependencies beyond
two Google Fonts) that walks through the full product experience: sign up,
pick who you are, answer the adaptive questionnaire, get a literacy score
with an AI-written explanation, a risk profile, and an investment plan that
bifurcates your money across FDs, mutual funds, direct equity and gold with
a projected growth chart.

It exists to show what the product feels like end to end before — or in
parallel with — the real Angular frontend in `../frontend` and Spring Boot
backend in `../backend` are wired together.

## Deploying it on GitHub Pages

One-time setup, then every push deploys automatically:

1. Push this repository to GitHub.
2. **Settings → Pages → Build and deployment → Source: GitHub Actions.**
3. That's it. `.github/workflows/deploy-prototype.yml` (at the repo root)
   publishes whatever is in this folder to Pages on every push to `main`
   that touches `prototype/**`, and can also be run manually from the
   **Actions** tab (`workflow_dispatch`).

The published URL appears under the `github-pages` environment on the
workflow run, and under **Settings → Pages** once it has deployed at least
once: `https://<your-username>.github.io/<repo-name>/`.

Prefer not to use Actions? You can instead just point Pages at this folder
directly with **Settings → Pages → Deploy from a branch → `main` / `/prototype`**
(GitHub Pages only accepts `/` or `/docs` for that option on most plans —
if `/prototype` isn't offered, copy `index.html` into a `/docs` folder at
the repo root instead, or keep the Actions workflow above).

## How the questionnaire adapts

Every question has an **"I don't know about this"** option, and every
question except monthly income has a **"Learn about … on Wikipedia"** link.
The link opens in a new tab and doesn't affect the answer. The article for
each question is set in `WIKI_ARTICLES` in `index.html`.

The number of questions grows or shrinks with the answers:

- **Simpler re-ask.** Saying "I don't know" to income, savings rate,
  emergency-fund months, high-interest debt or the market-drop question brings
  up a plainer version (a range, or a concrete ₹ scenario). Its answer is
  translated back into an answer for the original question.
- **Follow-ups.** Some answers add a question: high-interest debt → payoff
  plan; under 3 months of emergency fund → where to keep one; a cautious
  reaction to a market fall → equity experience; a wrong or unknown knowledge
  answer in Compounding or Investing → a basics probe.
- **Skips.** Advanced questions (ELSS lock-in, 80C, NPS, subsidised loans…)
  are dropped when the user said "I don't know" in that category and hasn't
  got any of its knowledge questions right.

What an unresolved "I don't know" means for the results: a knowledge
question scores zero (a topic to learn); a question about the user's own
situation is read cautiously — no emergency fund, high-interest debt assumed
present, lowest risk tolerance. The score screen says how many were handled
each way.

## How results are personalised

Everything below comes from `financialSnapshot()` in `index.html`: the
user's own answers turned into rupee figures. Anything the user didn't know
is flagged, not guessed.

- **Money snapshot** (score screen): income, monthly saving, estimated
  spending (income minus saving), and emergency fund against a
  profile-specific target (3 / 6 / 12 months for student / professional /
  retiree).
- **Risk capacity** (risk screen) is a sum of listed factors, shown line by
  line. It starts from the backend formula (profile base + emergency fund −
  high-interest debt) and **extends it** with: an active payoff plan,
  investment horizon, savings rate, EMI share of income, and, for retirees,
  corpus runway, EMIs still running and a separate medical fund. This part
  is prototype-only and is **not** in `../backend` yet.
- **Monthly priorities** (investment screen): the month's saving goes to
  high-interest debt first, then the emergency-fund gap, then investing.
  The suggested SIP is what's left after those. The planner also defaults to
  the user's stated horizon and shows the projection in today's money (6%
  inflation).
- **Narratives** name the exact knowledge topics missed and quote the
  user's own ₹ figures. Categories with no questions for a profile show as
  "n/a" and are left out of best/worst comparisons, instead of counting as 0%.

## Projections: range, step-up, fees and tax

Projections are simulated month by month (`simulateSip`). With no step-up
this gives exactly the standard SIP formula. The planner and the PDF show:

- **A likely range:** each instrument is also projected with lower and
  higher returns (equity and gold ±3–4% a year, debt ±1%, cash ±0.5%),
  drawn as a shaded band on the chart. It shows plausible outcomes, not a
  statistical guarantee.
- **Step-up SIP:** "Raise my monthly investment every year by 0 / 5 / 10%".
- **After fees and tax:** typical Direct-plan fees are subtracted
  (`INSTRUMENT_COSTS`), and tax is estimated under FY 2025-26 rules:
  - equity: 12.5% on long-term gains above ₹1.25 lakh (20% within a year)
  - gold ETFs: 12.5%
  - debt and liquid funds: the user's slab rate when sold
  - deposit and savings interest: the slab rate every year
  - SGBs held to maturity: tax-free
  - plus 4% cess

  The slab comes from the user's income under the new regime
  (`marginalSlab`, 20% if income wasn't shared). It ignores the ₹12 lakh
  rebate, so it may overstate tax.

The questionnaire now starts with **age**. Age feeds the risk capacity
(under 30 +5, 45–54 −5, 55+ −10; retirees 75+ −5) and years to retirement
(`RETIREMENT_AGE` = 60). For professionals it also gives the monthly SIP
needed, in today's money, to reach 25× spending by 60, and a comparison
with the "100 minus age" rule.

## Student wording

The same question can read differently per profile through
`variants: { STUDENT: { text, hint, unit, options } }`. The option codes and
scoring never change; only the wording does. Students see:

- income as pocket money, allowance, stipend or part-time pay (0 is
  allowed), with student-sized ranges if they don't know (up to ₹15,000+)
- emergency fund, savings rate and automatic saving without salary, EPF or
  rent-and-bills assumptions
- high-interest debt that includes Buy Now Pay Later and instant-loan apps
- a ₹10,000 market-drop scenario instead of "your portfolio"
- student examples for fixed expenses and good vs. bad debt
- two student-only questions: UPI "sent by mistake" requests, and
  "guaranteed return" Telegram / crypto schemes. These replace a
  self-rated confidence question that tested nothing.

A student with no regular income is told there's nothing to split each
month, and the suggested SIP starts at ₹500 (₹1,000 if they didn't share
their income).

Retirees see:

- income as pension, annuity, interest, rent and withdrawals (no salary or
  EPF)
- retiree examples for needs vs. wants, and "if your pension was held up"
  for the emergency-fund re-ask
- savings confidence as "will last through your retirement", not "on track
  for retirement"
- four retiree-only questions:
  - "digital arrest" / fake-police video-call scams (report on 1930 or
    cybercrime.gov.in)
  - a safe yearly withdrawal rate (3–4%)
  - nominees on accounts and a will (feeds a next step when missing)
  - Form 15H to stop TDS on FD interest (advanced)

Working professionals see:

- income as in-hand salary plus side or freelance income, and "if you lost
  your job today" for the emergency-fund re-ask
- health insurance that says employer group cover ends when you leave
- NPS 80CCD(1B) marked as old-regime only
- new questions:
  - the new tax regime's zero-tax limit (₹12.75 lakh for salaried income
    since FY 2025-26, from the ₹12 lakh 87A rebate plus the ₹75,000
    standard deduction), with regime advice in the results when missed
  - what to do with a pay rise (replaces a self-rated step-up SIP
    question)
  - EPF on a job change (transfer, don't withdraw)
- "100 minus your age" is no longer a question. The results compare it
  with the user's real age instead.

Known gap: the emergency-fund question gives full marks at 6 months, while
the plan's target for retirees is 12 months. Scoring doesn't vary by
profile yet.

## Saved progress, Back, tabs and accessibility

- Progress is saved in the browser's `localStorage` after every step. After
  signing in as the same profile, the dashboard offers **Resume** (mid-way)
  or **View my results** (finished), plus **Start over**. Everything is
  guarded, so the app works the same where storage is blocked.
- Each completed assessment is also added to a history in `localStorage`
  (`money-compass-history-v1`: date, profile, score, risk band). The
  dashboard's "Your last session" tiles are built from it: latest score
  with the change since the previous attempt, risk band with its split,
  and the number completed. With no history they're replaced by a
  "You haven't taken the assessment yet" card with a start button.
- The dashboard shows which profile the questions are set up for, with a
  **Change profile** button. It reopens the profile picker with the
  current profile selected, and "Keep my current profile" backs out
  without changing anything. Choosing a different profile starts fresh:
  the questions, scoring and plan all differ by profile, so the previous
  results screens are cleared. Past results stay in the dashboard history,
  labelled with their profile.
- **Light and dark themes:** a sun/moon button in the top bar (and at the
  top-right of the sign-in screen) switches themes. Dark is the default,
  and the choice is remembered in `localStorage` (`money-compass-theme`).
  A one-line script in `<head>` applies it before the page paints, so
  there's no flash. The light theme redefines the same color tokens on
  `:root[data-theme="light"]`, and every text color meets 4.5:1 contrast.
  The growth chart reads its colors from those tokens and redraws when the
  theme changes. The PDF report is always light.
- **Back** brings the previous question back with its answer filled in.
- The five breakdowns (mutual funds, direct shares, debt, gold, cash) are
  tabs in one card. A breakdown with nothing in it has no tab, and the arrow
  keys move between tabs.
- Toggle buttons carry `aria-pressed`, answer options form a labelled
  group, the progress bar is a real `progressbar`, follow-up notices are
  announced, and focus moves to each new question. The muted text color
  changed from `#837c6c` (3.7:1 on the glass cards) to `#9a9282` (4.9:1),
  which meets WCAG AA contrast.

## Mutual fund mix

When mutual funds are picked for the equity portion, that money is split
into SEBI's fund categories: large cap / Nifty 50 index (the 100 largest
companies), flexi cap, mid cap (101st–250th) and small cap (251st onward).
The split is set by risk band in `MF_SPLIT_BY_BAND`, from 80/20/0/0 for
Conservative to 30/25/25/20 for Aggressive. It is then adjusted for the
user:

- If the money is needed within 3 years, or the user is retired, there are
  no small caps and mid caps are capped at 10%.
- Any category under ₹500 a month (`MIN_SIP`) moves into the next larger
  one. If even the large-cap core would be under ₹500, everything goes into
  one index fund.

Each category has its own illustrative return (11 / 11.5 / 12.5 / 13.5%).
The plan's projection, instrument table, "Your mutual fund mix" card and
PDF report all use the same split. Gold and cash are labelled as the fund
types they map to (gold ETF / fund, liquid fund).

## Direct shares by sector

When direct shares are picked, their monthly amount is spread across six
sector groups. The split starts from the Nifty 50's own sector mix (2026
NSE factsheet: financial services ~36%, oil & gas ~9.5%, IT ~8.5%, autos
~7%, FMCG ~5.4%, telecom ~5%). Financials are **capped at 25%**, and the
difference goes to the smaller sectors:

| Sector | Default | Defensive (Conservative / Moderate / retirees) |
|---|---|---|
| Banks & financial services | 25% | 25% |
| IT & telecom | 15% | 13% |
| Consumer (FMCG, autos, durables) | 20% | 25% |
| Energy (oil & gas, power) | 13% | 12% |
| Industrials & materials | 17% | 12% |
| Healthcare & pharma | 10% | 13% |

Every sector uses the same 13% direct-equity return, so the sectors appear
only in the "by sector" card and the PDF, not as separate lines in the
instrument table, and the projection is unchanged. Below ₹6,000 a month
(`DIRECT_ROTATE_BELOW`), the card suggests putting each month into one or
two sectors in turn. It also suggests about 10–20 companies, none above 10%,
and warns when the money is needed within 3 years. These rules are in
`sectorSplit()` in `index.html`.

## Debt mix

The debt slice is split between **FD / RD** (invested monthly, a deposit is
a recurring deposit), **short duration debt funds** and **corporate bond /
Banking & PSU debt funds**. The split follows the planner's time horizon:

| Horizon | FD / RD | Short duration | Corporate bond |
|---|---|---|---|
| 1 year | 100% | – | – |
| 2–3 years | 50% | 50% | – |
| 5+ years | 30% | 30% | 40% |

Retirees move up to 20 points from corporate bonds into deposits, and get
the senior-citizen deposit rate (+0.5%). A debt-fund share under ₹500 a
month stays in the RD. Returns: FD / RD 6.5% (7% for retirees), short
duration 6.8%, corporate bond 7%. The "Your debt mix" card also covers debt-fund
taxation since April 2023, DICGC insurance and, for retirees, SCSS.
These rules are in `debtSplit()` in `index.html`.

## Gold mix

The gold slice goes to a **gold ETF / gold fund** (a monthly SIP works) and,
where it makes sense, **Sovereign Gold Bonds**. No new SGBs have been issued
since February 2024 (the scheme was discontinued for new subscriptions in
Budget 2025). Existing bonds trade on NSE/BSE at 1 gram per unit and all
mature by 2032. So the SGB share (50%) only applies when:

- the horizon is 5+ years (SGBs are best held to maturity for the tax-free
  gain, and they can be hard to sell quickly on the exchange), and
- that share is at least ₹1,000 a month (`SGB_MIN_MONTHLY`), enough to buy a
  unit about once a year.

Otherwise it's all gold ETF. Returns: gold ETF 8%, SGB 8.75% (gold price plus
interest of 2.5% on an issue price well below today's price). These rules
are in `goldSplit()` in `index.html`.

## Cash mix

The cash slice is split between a **savings account** (instant, 3%) and a
**liquid fund** (6%). Liquid funds allow up to ₹50,000, or 90% of the
balance if lower, to be withdrawn instantly per day per fund, with the rest
arriving the next working day. The default is 25% savings / 75% liquid;
retirees get 40 / 60. If the liquid share would be under ₹500 a month, it
all stays in savings. The card also tells the user to keep one month of
their own spending (in ₹) in savings and the rest of the emergency fund in
the liquid fund. These rules are in `cashSplit()` in `index.html`.

## Downloading the report as a PDF

The investment plan screen has a **Download report (PDF)** button. It saves
`money-compass-report-YYYY-MM-DD.pdf`, an A4 report with the score and its
breakdown, the written explanation, the money snapshot, the risk profile
(including the capacity factors), the monthly priorities, the investment plan
with its growth chart and instrument table, and the assumptions. It uses
whichever risk band, amount, horizon and instruments are selected at the
time.

The PDF is built in the browser with [jsPDF](https://github.com/parallax/jsPDF)
4.2.1. It is loaded from cdnjs (with an integrity hash) **only when the button
is first clicked**, so the page still has no dependencies until then;
offline, the button shows an error instead. The PDF's built-in fonts have no
₹ glyph, so amounts appear as "Rs".

## What's real math and what's simulated

This is a frontend-only artifact — there is no server behind it. Two very
different things are happening under the hood, and it matters which is which:

| | How it's computed |
|---|---|
| Literacy score, category breakdown | **Real deterministic logic**, ported line-for-line from the actual `LiteracyScoringService`/`RiskAssessmentService`/`QuestionnaireService` in `../backend`. Same rubric shapes, same weighting, same `band = min(tolerance, capacity)` rule. |
| Investment plan (bifurcation, SIP growth projection) | **Real compound-interest math** (a standard monthly-SIP future-value formula) against illustrative long-term return assumptions. No AI involved, by design — same "deterministic core" principle as the score. |
| The narrative text (score explanation, risk rationale) | **Simulated.** `generateNarrative`/`generateRiskNarrative` in `index.html` build plausible prose from the real numbers above. Each builds a pool of candidate points (all computed from the user's own answers and rupee figures) plus several framings for the summary; a per-narrative memory means each **Regenerate** favours points, angles and phrasings not yet shown — so a regenerate brings in new, relevant content rather than rewording, while critical points like high-interest debt always come back. Standing in for an actual call to `ResilientChatClient` → Ollama. |
| The **Ask Compass** assistant, the analysis-screen log, draft history (‹ 2 / 3 ›) and 👍/👎 feedback | **Simulated.** `answerQuestion` in `index.html` matches each question to a topic (score, first steps, emergency fund, debt, risk band, market crash, tax, retirement, insurance, investing, a glossary of terms, and what-ifs like “what if I invest ₹5,000 a month for 15 years?”) and answers from the user's real snapshot, score, band and plan using the same maths as the rest of the app. It declines stock/crypto picks. The analysis log narrates the real computed numbers. Feedback isn't sent anywhere. |
| "Ollama connected", response times, token counts, the provider-health popover | **Simulated**, for the same reason. Wiring this screen to the real `/api/health/ai` and `/api/score/{id}` endpoints once the backend is deployed is a fetch call, not a redesign — the response shapes already match (see `../backend/.../score/dto` and `../backend/.../health`). |

None of this is hidden from anyone reading the source — the functions above
are commented as simulations. What changed in this pass is only the parts a
user actually *sees*: the model "thinks" before answering, text streams in
rather than appearing all at once, latency and token counts vary
realistically per response, and a regenerate button produces a genuinely
new take — different points and framing — rather than the same fixed string.
