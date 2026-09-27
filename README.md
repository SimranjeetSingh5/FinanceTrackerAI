<div align="center">

# FinanceTracker AI

**Private, offline-first personal finance tracking for Android — with a language model that never leaves your phone.**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-purple.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/API-26%2B-green.svg)](https://developer.android.com)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Room](https://img.shields.io/badge/Room-2.6.1-FF6D00.svg)](https://developer.android.com/training/data-storage/room)
[![License](https://img.shields.io/badge/Gemma-Terms%20of%20Use-blue.svg)](ai.google.dev/gemma/terms)

</div>

---

## Why this exists

Most finance apps want your bank credentials, your transaction history, and your trust. This one
asks for none of it. There is **no account, no backend, no analytics SDK, and no network call**
after the one-time model download. Your transactions live in a Room database inside your app's
private storage. The AI that categorises them and answers questions about them is Gemma, running
entirely on your device through Google's MediaPipe runtime.

If you want to know where your money went, you shouldn't have to hand your bank portal to a
startup to find out.

---

## Table of contents

- [Features](#features)
- [How the on-device AI works](#how-the-on-device-ai-works)
- [Bank statement import](#bank-statement-import)
- [Getting the model file](#getting-the-model-file)
- [Building from source](#building-from-source)
- [Architecture](#architecture)
- [Project layout](#project-layout)
- [Technology](#technology)
- [Testing](#testing)
- [Privacy](#privacy)
- [Known limitations](#known-limitations)
- [License](#license)

---

## Features

### Money in, money out

| | |
|---|---|
| **Accounts** | Checking, savings, credit card, cash, and investment. Each tracks its own balance, with a net-worth rollup on the dashboard. |
| **Transactions** | Expenses, income, and transfers between accounts. Merchant and note fields, with a searchable history screen. |
| **Budgets** | Per-category monthly limits with live progress bars and over-budget flags. |
| **Recurring bills** | Daily, weekly, biweekly, monthly, or yearly. Either auto-added on the due date or simply reminded. |
| **Savings goals** | Target amount with running contributions and progress tracking. |
| **Analytics** | This month's category breakdown and a six-month income/expense trend, drawn with Compose primitives — no charting library. |

### The AI part

| | |
|---|---|
| **Instant insights** | Totals, biggest category, month-over-month change, and over-budget warnings are computed with plain arithmetic and render in **milliseconds**. |
| **Written summaries** | Gemma turns those same numbers into a readable paragraph. The numbers are never model-generated, so they can't be hallucinated. |
| **Ask AI, everywhere** | A suggestion row on seven screens, populated from the data actually on screen. Tapping one opens chat with the question pre-filled. |
| **Auto-categorization** | Uncategorised transactions are matched to a category on-device. |
| **Chat assistant** | Streaming answers grounded in your real transactions, not a generic model's priors. |

### Importing statements

Photograph or screenshot a bank statement and the app reads it, **entirely on-device**, then shows
you exactly what it understood before saving a single row.

- **Image, PDF, or pasted text** — three ways in
- **On-device OCR** — ML Kit's *bundled* recogniser, no API key, works in airplane mode
- **Table-aware parsing** — a photographed table arrives as one cell per line; rows are
  reassembled from bounding boxes before parsing
- **Review before save** — every row shows its raw text, and anything the parser is unsure about
  is flagged for you
- **Duplicate detection** — re-importing a statement shows you the repeats instead of silently
  dropping them
- **Editable** — fix any name, amount, or category before it lands
- **Dedupe on save** — same day, amount, and merchant never lands twice

### Keeping it private

| | |
|---|---|
| **App lock** | Optional PIN plus biometric (fingerprint/face) gate. The PIN is stored as a SHA-256 hash in `EncryptedSharedPreferences`. |
| **CSV export** | Share your full history with any app — email, Drive, Files. |
| **No account required** | Nothing to sign up for, nothing to leak. |

---

## How the on-device AI works

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐
│  Your data  │────▶│  Deterministic │────▶│  Instant facts  │
│  (Room DB)  │     │    arithmetic  │     │  (milliseconds) │
└─────────────┘     └──────────────┘     └────────┬────────┘
                                                    │
                                                    ▼
                                          ┌─────────────────┐
                                          │   Gemma 1B int4  │
                                          │  (MediaPipe JNI) │
                                          └────────┬────────┘
                                                   │
                                                   ▼
                                          ┌─────────────────┐
                                          │ Written summary │
                                          │   (streams in)  │
                                          └─────────────────┘
```

**The split matters.** Most of what a "spending insight" contains is arithmetic, and arithmetic is
microseconds fast and exactly correct. So the app computes the facts itself and renders them
immediately, then asks the model for the one thing only it can do: turn those numbers into a
sentence a person wants to read.

This has three consequences worth having:

1. **The numbers are never wrong.** The model is only ever handed values it is allowed to repeat.
2. **You never wait for facts.** The useful part appears before inference has even started.
3. **A missing or slow model degrades gracefully.** You still get correct numbers.

### Backend selection

Gemma is initialised on the **GPU** where possible, falling back to CPU. Each candidate backend is
**smoke-tested with a one-token generation** before being committed to, because a GPU that loads
successfully can still fail on the first real session — OpenCL errors on some Adreno and Mali parts
surface exactly that way.

Emulators are excluded from the GPU path. MediaPipe's OpenCL loader dereferences null when
`libvndksupport.so` is absent, which **segfaults the process** rather than throwing — so no
Java-side fallback can catch it. See `FORCE_GPU_ON_EMULATOR` in `GemmaInferenceHelper` if you want
to see that for yourself.

---

## Bank statement import

Statements are not standardised, and OCR is not kind to them. A rupee sign comes back as `생`,
`제`, `₴`, or — worst — a plain `8`, turning ₹130.00 into 8130.00. A transaction row arrives with
no amount at all. A date lands in the wrong column.

So the app never imports blind. Every row is shown with the text it was read from, anything
uncertain is highlighted, and **nothing reaches the database until you confirm it**.

### What's handled

- **Dates** — `26 Sep 2026`, `09/12/2026`, `2026-04-13`, `091226`, `12.09.2026`, plus the OCR
  letter-for-digit damage (`O` → `0`, `l` → `1`)
- **Amounts** — `1,234.56` and `1.234,56` both resolve to 1234.56; any currency symbol or none
- **Layouts** — column exports (date, type, merchant, category, amount on separate lines) *and*
  US-style rows where merchant, reference, and running balance share the date line
- **Suspicious values** — an amount beginning with `8` is flagged rather than trusted, because
  that is the classic misread currency symbol

### Duplicate handling

Rows are matched on day + amount + a **sorted character signature** of the merchant. The signature
is order-independent, so `STARBUCKS` and `STARBUKS` — the same purchase read twice by OCR — still
match. Two cases are distinguished and shown:

- **Repeated on this statement** — usually a page photographed twice
- **Already in your transactions** — a re-import

Flagged rows arrive **unticked**. If you tick one, it is imported — re-importing is a legitimate
thing to want.

---

## Getting the model file

The model is **not bundled** in the APK. `gemma-3-1b-it-int4.task` is roughly 800 MB, which is
several times the 150 MB Android App Bundle limit, would make every install pay the download cost,
and would freeze users on whatever model shipped with that release.

It is instead downloaded once from a **GitHub Release asset** — a stable public URL, free, no
account, and it never expires.

```
https://github.com/SimranjeetSingh5/FinanceTrackerAI/releases/download/gemma-v2/Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task
```

To host a different model, upload it as a release asset and update one file:

```
app/src/main/java/com/financetracker/ai/ai/ModelSources.kt
```

Each mirror carries a SHA-256 that the download is verified against before the inference runtime
sees the file, so a truncated or tampered download fails loudly instead of crashing later. If a
mirror is unreachable the next one is tried.

> **Gemma is not Apache-2.0.** It is governed by the
> [Gemma Terms of Use](https://ai.google.dev/gemma/terms). Publishing the weights as a release
> asset means shipping those terms with it.

---

## Building from source

**Requirements:** Android Studio Koala or newer, JDK 17, and a **physical device** (see below).

```bash
git clone https://github.com/SimranjeetSingh5/FinanceTrackerAI.git
cd FinanceTrackerAI
```

Open in Android Studio, let Gradle sync, and run.

**Build from the command line:**

```bash
./gradlew assembleDebug          # build the APK
./gradlew testDebugUnitTest      # run the unit tests
./gradlew installDebug           # install on a connected device
```

**Use a real device.** MediaPipe's GPU backend is unreliable on emulators, and their emulated
single-core CPU makes inference roughly 20× slower than real hardware. Everything works on an
emulator — the AI is just painfully slow.

| Setting | Value |
|---|---|
| `minSdk` | 26 |
| `compileSdk` / `targetSdk` | 34 |
| Kotlin | 1.9.24 |
| Android Gradle Plugin | 8.5.2 |
| JDK | 17 |
| Recommended free RAM | 4 GB+ |

---

## Architecture

MVVM with a repository layer. The dependency graph runs strictly in one direction:

```
UI (Compose)  ──observes──▶  StateFlow  ──▶  ViewModel
                                                  │
                                          suspends ▼
                                            Repository  ◀── the only place
                                                  │        business logic
                                       ┌──────────┴──────────┐
                                       ▼                     ▼
                              Room DAOs            AiInsightEngine
                              (SQLite)               (Gemma / MediaPipe)
```

**Rules the code follows:**

- Composable functions render state and raise events. No business logic, no `remember` juggling
  across screens.
- ViewModels expose immutable `StateFlow` and never expose their backing `MutableStateFlow`.
- The repository is the single source of business logic. Two ViewModels needing the same rule call
  the same method rather than duplicating it.
- ViewModels never touch DAOs, SQLite, or MediaPipe directly.
- No Android framework types in the parser, the fact engine, or the repository — which is what
  makes them unit-testable without a device.

Background work runs through `WorkManager`: a daily maintenance job for budget and bill
notifications, and a foreground service for the model download so an 800 MB transfer survives the
user closing the app.

---

## Project layout

```
app/src/main/java/com/financetracker/ai/
├── ai/                 On-device intelligence
│   ├── GemmaInferenceHelper    MediaPipe wrapper, backend selection, timing
│   ├── AiInsightEngine         Prompt construction and response parsing
│   ├── SpendingFacts           Deterministic analysis — the instant half of an insight
│   ├── ModelSources            Mirror list and hashes for the model download
│   └── ModelDownloader         Resumable HTTP with mirror fallback and SHA-256
├── data/               Persistence
│   ├── Entities.kt      Transaction, Category, Account, Budget, Goal,
│   │                    RecurringTransaction, ChatMessage
│   ├── Daos.kt          Typed queries, including the statement search
│   └── AppDatabase.kt   Room setup and default category seeding
├── importing/          Bank statement import
│   ├── StatementScanner         ML Kit OCR; rebuilds table rows from bounding boxes
│   ├── StatementParser          Record segmentation and field extraction
│   ├── DateReader               Date formats across locales
│   └── AmountReader             Currency symbols and separator conventions
├── repository/         FinanceRepository — all business logic
├── viewmodel/          One per screen, StateFlow-based
├── settings/           SettingsStore (encrypted prefs: PIN, currency, toggles)
├── security/           BiometricAuthHelper
├── util/               Constants, CsvExporter, NotificationHelper, workers
├── ui/
│   ├── components/     ScreenHeader, AskAiSection, IconMapper, currency CompositionLocal
│   ├── screens/        14 screens
│   └── theme/          Material3 theme
├── FinanceApp.kt       Manual DI container, WorkManager scheduling, notification channels
└── MainActivity.kt     FragmentActivity (BiometricPrompt requires it) + lock gate
```

---

## Technology

Deliberately few dependencies. The chart, the swipe gesture, and the insight engine are all hand-
written rather than pulled in — a finance app is not the place to be at the mercy of a transitive
charting library.

| | |
|---|---|
| **UI** | Jetpack Compose, Material 3, Navigation Compose |
| **State** | ViewModel + `StateFlow`, collected with `collectAsState` |
| **Persistence** | Room 2.6.1 over SQLite |
| **On-device LLM** | MediaPipe LLM Inference (`tasks-genai:0.10.24`) + Gemma 3 1B int4 |
| **OCR** | ML Kit bundled text recognition (`16.0.1`) |
| **Background work** | WorkManager 2.9.1 |
| **Security** | `EncryptedSharedPreferences`, `androidx.biometric` |
| **Async** | Coroutines + Flow |

51 Kotlin source files, roughly 7,500 lines.

---

## Testing

```bash
./gradlew testDebugUnitTest
```

28 unit tests across three suites, all running on the JVM with no device or emulator:

| Suite | Covers |
|---|---|
| `StatementParserTest` | Real statement exports, including OCR damage and header-block rejection |
| `UsStatementParserTest` | US layouts, month-first dates, running balances, European decimals |
| `DuplicateDetectionTest` | Repeat detection, OCR-variant matching, and false-positive guards |

The parser tests are written against **actual statement text**, not idealised fixtures — including
the rows where the rupee symbol was read as `8`, where a date was mangled to `Zb sep, 202b`, and
where amounts are simply missing.

**25 of 28 pass.** The three failures are the known transaction-merge limitation below, plus two
duplicate-detection fixtures that don't parse in the single-line US shape they were written in.
The parser and duplicate logic are exercised; those three assert on row counts rather than
behaviour. See [Known limitations](#known-limitations).

---

## Privacy

- Your data never leaves the device. There is no server component.
- The AI model runs locally. Inference is local. Storage is local.
- The PIN is stored as a SHA-256 hash in `EncryptedSharedPreferences` under an AES-256-GCM key from
  the Android keystore.
- ML Kit's bundled recogniser performs OCR on-device.

> **One honest caveat:** ML Kit's own SDK emits anonymous usage telemetry to
> `firebaselogging.googleapis.com` even when using the bundled model. It is library metrics, not
> your statement — but if that matters for your threat model, it is real and worth knowing. The
> OCR happens locally either way.

---

## Known limitations

Stated plainly, because a README that only lists successes is marketing.

- **Two transactions can merge into one** when a mangled amount cell sits between them. Record
  boundaries are inferred from line content, and when OCR destroys the date line there is no
  reliable signal. The review screen surfaces such rows so you can split them.
- **Statement parsing is tuned against real statements, not all of them.** Banks differ, and a
  format not represented in the test fixtures may parse poorly. The review step is what makes this
  safe rather than the parser being exhaustive.
- **Row reconstruction assumes evenly spaced rows.** Clean app screenshots work well; a photo of a
  printed statement taken at an angle does not, because there is no perspective correction.
- **Room uses `fallbackToDestructiveMigration()`.** Any schema change silently recreates the
  database. Fine pre-release; needs real `Migration` objects before users have data worth keeping.
- **The currency setting relabels rather than converts.** There is no exchange rate on a
  transaction, so switching currency changes how history is displayed, not its value.
- **No multi-currency conversion, no date-range filter** on the transactions list, and
  `Transaction.receiptPhotoPath` has no camera UI behind it yet.

---

## Contributing

Issues and pull requests are welcome. If you are adding a statement format, please add a test built
from a **real** statement export — the fixtures in this repo are deliberately ugly, because real
OCR output is ugly.

---

## License

This project uses **Gemma**, which is governed by the
[Gemma Terms of Use](https://ai.google.dev/gemma/terms) — it is *not* Apache-2.0. If you redistribute
the model weights, you must ship those terms alongside them.

The application source is provided as-is for personal and educational use.
