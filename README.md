# FinanceTracker AI

A Kotlin/Jetpack Compose Android app for tracking finances, with **Gemma running fully
on-device** (via Google's MediaPipe LLM Inference API) for categorization, insights, and a
chat assistant. Feature set is scoped to match mainstream finance-tracker apps (Mint/YNAB/
Copilot-style): multiple accounts, budgets, recurring bills, savings goals, analytics, search,
CSV export, and an app lock — all local, no backend, no account required.

## Feature checklist

- **Accounts** — checking/savings/credit card/cash/investment, each with its own balance;
  a net-worth rollup on the dashboard and Accounts screen.
- **Transactions** — expense/income/transfer between accounts, optional AI auto-categorization,
  merchant + note fields, plus a searchable/filterable history screen with inline edit and
  swipe-to-delete (More → Transactions).
- **Budgets** — per-category monthly limits with live progress bars and over-budget flags.
- **Recurring transactions / bills** — daily/weekly/biweekly/monthly/yearly schedules, either
  auto-added on the due date or just reminded via notification.
- **Savings goals** — target amount + running contributions with progress bars.
- **Analytics** — this month's category breakdown and a 6-month income/expense trend chart
  (hand-drawn with Compose primitives, no external chart dependency).
- **AI assistant** — streaming chat grounded in your actual transactions, monthly natural-
  language spending insight, automatic categorization — all offline via Gemma/MediaPipe.
- **Notifications** — a daily WorkManager job checks budget thresholds and due recurring bills.
- **App lock** — optional PIN + biometric (fingerprint/face) gate on launch, PIN hash stored in
  EncryptedSharedPreferences.
- **CSV export** — share your full transaction history via any app (email, Drive, Files).
- **Settings** — currency selector, notification toggles, security, data export.

There is no network dependency for any of the above except the one-time Gemma model download —
inference, storage, and notifications are all local.

## Project structure

```
app/src/main/java/com/financetracker/ai/
├── data/            Room entities, DAOs, database (Transaction, Category, Account, Budget,
│                     Goal, RecurringTransaction, ChatMessage)
├── ai/               GemmaInferenceHelper (MediaPipe wrapper) + AiInsightEngine (prompts)
├── repository/       FinanceRepository — glues Room + AI together, one place for all business logic
├── viewmodel/        FinanceViewModel, ChatViewModel, BudgetsViewModel, GoalsViewModel,
│                     RecurringViewModel, AnalyticsViewModel, SettingsViewModel,
│                     TransactionsViewModel (search + filters)
├── settings/         SettingsStore (encrypted prefs: PIN, currency, notification toggles)
├── security/         BiometricAuthHelper (androidx.biometric wrapper)
├── util/             CsvExporter, NotificationHelper, DailyMaintenanceWorker (WorkManager)
├── ui/components/    IconMapper, ScreenHeader (back nav), currency CompositionLocal
├── ui/screens/        Dashboard, AddTransaction, Accounts, Budgets, Goals, Recurring,
│                     Analytics, Chat, Settings, ModelSetup, LockScreen, More (hub),
│                     Transactions (searchable history)
├── ui/theme/          Material3 theme
├── FinanceApp.kt      Manual DI container + WorkManager scheduling + notification channels
└── MainActivity.kt    FragmentActivity (required by BiometricPrompt) + lock-screen gate
```

Navigation: a 5-item bottom bar (Home, Budgets, Add, Analytics, More) — the More tab hubs out
to Transactions, Accounts, Recurring & Bills, Goals, the AI chat assistant, AI model setup, and
Settings, so the bottom bar stays uncluttered while every feature is one tap away. Every
More-hub screen has a visible back arrow in its header.

## Getting a Gemma model file (required, one-time, per device)

The model file itself (hundreds of MB to a few GB) is deliberately **not bundled** — you
download it once and the app loads it from local storage. Bundling it would exceed the 150 MB
AAB limit, make every install pay the download cost, and freeze users on one model version.

The app downloads it from a **GitHub Release asset** — a stable public URL that never expires,
free, and no credit card. Mirrors and their SHA-256 hashes live in one place:

```
app/src/main/java/com/financetracker/ai/ai/ModelSources.kt
```

To publish a model, upload it as a release asset and paste the resulting URL + hash into
`ModelSources.mirrors`. If a mirror fails, the next one is tried automatically.

Gemma is **not** Apache-2.0 — publishing the weights means shipping Gemma's Terms of Use with
the release.

The file is saved to `context.filesDir/gemma_local_model.task` and verified against the
configured hash before being handed to the inference runtime.

## Building

Open in Android Studio (Koala+), let Gradle sync, run on a physical device (MediaPipe's GPU
backend can be flaky on emulators — set `preferGpu = false` in `GemmaInferenceHelper.initialize()`
to fall back to CPU if needed). Minimum SDK 26; 4GB+ free RAM recommended for 1B/2B Gemma
variants.

## Known simplifications / next steps

- Room migrations use `fallbackToDestructiveMigration()` — fine pre-release, replace with real
  `Migration` objects before shipping a schema change to real users.
- The `Budget` entity exists for historical per-month snapshots, but the current Budgets screen
  edits `Category.monthlyBudget` directly for simplicity (one active limit per category, not
  a limit-per-month history). Wire up `FinanceRepository.upsertBudget`/`budgetsForMonth` if you
  want month-by-month budget history instead.
- Receipt photo capture: `Transaction.receiptPhotoPath` field exists but no camera/gallery
  picker UI yet.
- No multi-currency conversion — the currency setting only changes the display symbol/format.
  It is applied app-wide via `LocalCurrencyCode`; a transaction has no exchange rate attached
  to it, so switching currency re-labels history rather than converting it.
- The Transactions list has no date-range filter yet (the DAO accepts a `start`/`end` window,
  currently left unbounded).
