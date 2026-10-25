# AI Personal Finance Assistant (EECS 3311)

A desktop finance assistant that imports bank/credit-card statements, categorizes spending
(your own rules first, then Claude for the rest), tracks monthly budgets with alerts, and
explains your finances in plain language. Deterministic code does every calculation; the LLM
decides what to look up and how to explain it.

The Stage 1 design report (PDFs, UML `.uxf` files and diagram PNGs) is in the repository root.

## Status

| Feature | Status |
|---|---|
| F01 Import CSV statements (TD, CIBC) | Implemented (CLI) |
| F02 Automatic categorization (rules + Claude) | Implemented (CLI) |
| F03 Manual category correction + rules | Implemented (CLI) |
| F04 Monthly budgets | Implemented (CLI) |
| F05 Budget alerts (80% / exceeded) | Implemented (CLI) |
| F06 Subscriptions, F07 Chat agent, F08 Savings goals | In progress |
| F09 Monthly report, F10 Report export, GUI | Planned |

## Prerequisites

- Java 21 (JDK) - check with `java -version`
- Maven 3.9+ - check with `mvn -v`
- An Anthropic API key for the AI features (<https://console.anthropic.com>). Without a key the
  app still runs; AI steps report "AI unavailable" and transactions stay Uncategorized.

## Setup

```bash
git clone https://github.com/dhruv4980/eecs3311-finance-assistant.git
cd eecs3311-finance-assistant

# 1. Local configuration - .env is git-ignored, never commit it
cp .env.example .env          # Windows: copy .env.example .env
# 2. Open .env and replace YOUR_LOCAL_API_KEY_HERE with your key

# 3. Build and run the tests
mvn package
```

`mvn package` builds `target/finance-assistant.jar` (dependencies included) and runs the unit tests.

### Configuration (`.env`)

| Setting | Default | Meaning |
|---|---|---|
| `LLM_API_KEY` | - | Your Anthropic API key (required for AI features) |
| `LLM_MODEL` | `claude-haiku-5-5` | Claude model used |
| `LLM_TIMEOUT_SECONDS` | `30` | How long to wait for Claude before treating it as unavailable |
| `DB_PATH` | `data/finance-db.json` | Local database file |

Environment variables with the same names override `.env`.

## Running the CLI

```bash
java -jar target/finance-assistant.jar help         # one command
java -jar target/finance-assistant.jar              # interactive prompt (type 'exit' to quit)
```

### Example session (uses the sample statements in `sample-data/`)

```bash
# F01 + F02: preview, confirm, then auto-categorize
java -jar target/finance-assistant.jar import --bank TD --account Chequing --file sample-data/td-chequing-jul-sep-2026.csv
java -jar target/finance-assistant.jar import --bank CIBC --account Visa --file sample-data/cibc-visa-jul-sep-2026.csv --yes

# See transactions (with ids) and spending by category
java -jar target/finance-assistant.jar transactions --month 2026-09
java -jar target/finance-assistant.jar categories --month 2026-09

# F03: fix a category and save a rule for similar transactions
java -jar target/finance-assistant.jar recategorize --id <ID from the list> --category Subscriptions --similar
java -jar target/finance-assistant.jar rules

# F04 + F05: budgets and alerts
java -jar target/finance-assistant.jar budget set --month 2026-09 --category Dining --amount 150
java -jar target/finance-assistant.jar budget status --month 2026-09

# If Claude was unavailable during an import, retry the AI step later
java -jar target/finance-assistant.jar categorize
```

Error cases to try: `sample-data/notes.txt` (not a CSV), `sample-data/empty.csv` (empty file),
`sample-data/td-with-errors.csv` (invalid rows and a duplicate), `--bank RBC` (unsupported bank),
a zero or negative budget amount.

Use `--db <file>` with any command to work on a separate database (handy for demos).

## Project structure

```
src/main/java/com/financeassistant/
  config/          Config - reads .env / environment settings
  model/           Transaction, Account, Category, CategoryRule, Budget, AlertRecord
  db/              DatabaseManager (Singleton) - JSON-file database
  importer/        ImportService, ParserFactory (Factory), StatementParser, CsvParser, TDParser, CIBCParser
  llm/             LLMClient (Target), ClaudeClient (Adapter), AnthropicApi (Adaptee)
  categorization/  CategorizationService - rules first, then Claude
  budget/          BudgetService (Observer subject), AlertObserver, CliAlertPrinter
  controller/      AgentController (Facade used by both CLI and GUI)
  cli/             CLI
src/test/java/     JUnit tests (no network; Claude is replaced by a fake)
sample-data/       Safe, made-up statements for demos and tests
```

## Design patterns implemented so far

| Pattern | Where | Problem solved |
|---|---|---|
| Singleton | `db/DatabaseManager` | One shared database for all services |
| Factory | `importer/ParserFactory` | Pick the right bank parser without `ImportService` knowing them |
| Adapter | `llm/ClaudeClient` | Hide Anthropic's API format behind `generate(prompt)` |
| Observer | `budget/BudgetService` + `AlertObserver` | Send alerts to GUI and console without coupling |
| Facade | `controller/AgentController` | One entry point shared by CLI and GUI |

## Security

Real API keys live only in `.env`, which `.gitignore` excludes. `.env.example` contains a
placeholder only. If a key is ever pushed by mistake, revoke it in the Anthropic console immediately.
