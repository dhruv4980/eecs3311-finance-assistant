package com.financeassistant.cli;

import com.financeassistant.budget.BudgetExistsException;
import com.financeassistant.budget.BudgetService;
import com.financeassistant.budget.BudgetStatus;
import com.financeassistant.budget.CliAlertPrinter;
import com.financeassistant.categorization.CategorizationResult;
import com.financeassistant.categorization.CategorySummary;
import com.financeassistant.categorization.RecategorizeResult;
import com.financeassistant.categorization.RuleConflictException;
import com.financeassistant.config.Config;
import com.financeassistant.controller.AgentController;
import com.financeassistant.controller.ImportOutcome;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.importer.ImportException;
import com.financeassistant.importer.ImportResult;
import com.financeassistant.importer.RejectedRow;
import com.financeassistant.model.CategoryRule;
import com.financeassistant.model.Transaction;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Command-line interface. Run with a command for one action
 * ({@code import --bank TD --account Chequing --file statement.csv}) or with
 * no command for an interactive prompt. Every command goes through
 * AgentController, the same facade the GUI uses.
 */
public class CLI {
    private static final Set<String> FLAGS = Set.of("yes", "similar", "replace", "overwrite", "uncategorized", "all");

    private final AgentController controller;
    private final BufferedReader in;
    private final PrintStream out;

    public CLI(AgentController controller, BufferedReader in, PrintStream out) {
        this.controller = controller;
        this.in = in;
        this.out = out;
        controller.addAlertObserver(new CliAlertPrinter(out));
    }

    public static void main(String[] args) {
        List<String> rest = new ArrayList<>(Arrays.asList(args));
        int db = rest.indexOf("--db");
        if (db >= 0 && db + 1 < rest.size()) {
            DatabaseManager.open(Path.of(rest.get(db + 1)));
            rest.remove(db + 1);
            rest.remove(db);
        }
        AgentController controller;
        try {
            controller = AgentController.fromConfig(Config.load());
        } catch (IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
            return;
        }
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        CLI cli = new CLI(controller, stdin, System.out);
        System.exit(cli.run(rest.toArray(new String[0])));
    }

    /** Runs one command, or the interactive prompt when there is none. Returns the exit code. */
    public int run(String[] args) {
        if (args.length > 0) {
            return dispatch(args[0], List.of(args).subList(1, args.length)) ? 0 : 1;
        }
        out.println("AI Personal Finance Assistant - " + controller.describeAi());
        out.println("Type 'help' for commands, 'exit' to quit.");
        while (true) {
            out.print("> ");
            out.flush();
            String line = readLine();
            if (line == null) {
                return 0;
            }
            List<String> tokens = Args.tokenize(line);
            if (tokens.isEmpty()) {
                continue;
            }
            if (tokens.get(0).equals("exit") || tokens.get(0).equals("quit")) {
                return 0;
            }
            dispatch(tokens.get(0), tokens.subList(1, tokens.size()));
        }
    }

    /** Executes a command; prints errors instead of throwing. Returns true on success. */
    boolean dispatch(String command, List<String> tokens) {
        Args args = new Args(tokens, FLAGS);
        try {
            switch (command.toLowerCase(Locale.ROOT)) {
                case "help" -> printHelp();
                case "import" -> importStatement(args);
                case "transactions", "txns" -> listTransactions(args);
                case "categorize" -> printCategorization(controller.categorizeTransactions());
                case "categories" -> listCategories(args);
                case "recategorize" -> recategorize(args);
                case "rules" -> rules(args);
                case "budget" -> budget(args);
                case "months" -> controller.getMonths().forEach(out::println);
                default -> {
                    out.println("Unknown command '" + command + "'. Type 'help' for the list of commands.");
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException | ImportException e) {
            out.println("Error: " + e.getMessage());
            return false;
        } catch (IllegalStateException e) {
            out.println("Error: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------ commands

    private void importStatement(Args args) throws ImportException {
        String bank = args.require("bank", String.join("|", controller.getSupportedBanks()));
        String account = args.require("account", "Chequing");
        File file = new File(args.require("file", "statement.csv"));
        ImportResult preview = controller.previewImport(file, bank, account);

        out.println("Preview of " + file.getName() + " (" + preview.getBankName() + " " + preview.getAccountType() + "):");
        List<Transaction> rows = preview.getValidRows();
        rows.stream().limit(10).forEach(t -> out.printf(Locale.ROOT, "  %s  %10.2f  %s%n",
                t.getDate(), t.getAmount(), t.getDescription()));
        if (rows.size() > 10) {
            out.println("  ... and " + (rows.size() - 10) + " more");
        }
        for (RejectedRow r : preview.getRejectedRows()) {
            out.println("  rejected " + r);
        }
        out.println(preview.getSummary());
        if (rows.isEmpty()) {
            out.println("Nothing new to import.");
            return;
        }
        if (!args.has("yes") && !confirm("Import these transactions?")) {
            out.println("Import cancelled; nothing was saved.");
            return;
        }
        ImportOutcome outcome = controller.confirmImport(preview);
        out.println(outcome.importResult().getSummary());
        printCategorization(outcome.categorization());
    }

    private void printCategorization(CategorizationResult result) {
        out.println(result.summary());
        if (result.uncategorized() > 0) {
            out.println("Fix them with: recategorize --id <id> --category <name> [--similar]"
                    + (result.aiError() != null ? "  or run 'categorize' again once AI is available" : ""));
        }
    }

    private void listTransactions(Args args) {
        String month = args.has("all") ? null : month(args);
        String category = args.has("uncategorized") ? "Uncategorized" : args.get("category");
        List<Transaction> list = controller.getTransactions(month, category);
        if (list.isEmpty()) {
            out.println("No transactions" + (month == null ? "" : " in " + month) + ". Import a statement first.");
            return;
        }
        out.printf("%-6s %-10s %10s  %-34s %-14s %s%n", "ID", "DATE", "AMOUNT", "DESCRIPTION", "CATEGORY", "BY");
        for (Transaction t : list) {
            out.printf(Locale.ROOT, "%-6d %-10s %10.2f  %-34s %-14s %s%n", t.getId(), t.getDate(), t.getAmount(),
                    truncate(t.getDescription(), 34), t.getCategory(), t.getCategorySource().name().toLowerCase(Locale.ROOT));
        }
        out.println(list.size() + " transaction(s)");
    }

    private void listCategories(Args args) {
        String month = args.has("all") ? null : month(args);
        List<CategorySummary> summaries = controller.getCategorySummaries(month);
        if (summaries.isEmpty()) {
            out.println("No spending" + (month == null ? "" : " in " + month) + ".");
            out.println("Allowed categories: " + String.join(", ", controller.getAllowedCategories()));
            return;
        }
        out.println("Spending by category" + (month == null ? " (all time)" : " for " + month) + ":");
        double total = 0;
        for (CategorySummary s : summaries) {
            out.printf(Locale.ROOT, "  %-14s %10.2f  %5.1f%%  (%d)%n", s.category(), s.total(), s.percent(), s.count());
            total += s.total();
        }
        out.printf(Locale.ROOT, "  %-14s %10.2f%n", "Total", total);
    }

    private void recategorize(Args args) {
        int id = args.requireInt("id");
        String category = args.require("category", "Dining");
        boolean similar = args.has("similar");
        RecategorizeResult result;
        try {
            result = controller.recategorize(id, category, similar, args.has("replace"));
        } catch (RuleConflictException e) {
            if (confirm(e.getMessage())) {
                result = retryRecategorize(id, category, true, true);
            } else {
                out.println("Keeping the existing rule; only this transaction will change.");
                result = retryRecategorize(id, category, false, false);
            }
        }
        out.println("Updated: " + result.transaction());
        if (result.rule() != null) {
            out.println("Saved rule " + result.rule() + "; " + result.similarUpdated() + " similar transaction(s) updated.");
        }
    }

    private RecategorizeResult retryRecategorize(int id, String category, boolean similar, boolean replace) {
        try {
            return controller.recategorize(id, category, similar, replace);
        } catch (RuleConflictException impossible) {
            throw new IllegalStateException(impossible.getMessage(), impossible);
        }
    }

    private void rules(Args args) {
        if (!args.positional.isEmpty() && args.positional.get(0).equals("delete")) {
            int id = args.requireInt("id");
            out.println(controller.deleteRule(id) ? "Rule deleted." : "No rule with id " + id + ".");
            return;
        }
        List<CategoryRule> rules = controller.getRules();
        if (rules.isEmpty()) {
            out.println("No rules yet. Create one with: recategorize --id <id> --category <name> --similar");
        }
        rules.forEach(r -> out.println("  #" + r.getId() + "  " + r));
    }

    private void budget(Args args) {
        String action = args.positional.isEmpty() ? "status" : args.positional.get(0);
        switch (action) {
            case "set" -> {
                String month = BudgetService.validateMonth(args.require("month", "2026-09"));
                String category = args.require("category", "Dining");
                double amount = args.requireAmount("amount");
                try {
                    controller.setBudget(month, category, amount, args.has("overwrite"));
                } catch (BudgetExistsException e) {
                    if (!confirm(e.getMessage())) {
                        out.println("Kept the existing budget.");
                        return;
                    }
                    try {
                        controller.setBudget(month, category, amount, true);
                    } catch (BudgetExistsException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                }
                out.printf(Locale.ROOT, "Budget saved: %s %s $%.2f%n", month, category, amount);
            }
            case "status" -> {
                String month = month(args);
                List<BudgetStatus> status = controller.getBudgetStatus(month);
                if (status.isEmpty()) {
                    out.println("No budgets set for " + month
                            + ". Create one with: budget set --month " + month + " --category Dining --amount 200");
                    return;
                }
                out.printf("Budgets for %s:%n  %-14s %10s %10s %10s %7s%n", month, "CATEGORY", "BUDGET", "SPENT", "LEFT", "USED");
                for (BudgetStatus s : status) {
                    out.printf(Locale.ROOT, "  %-14s %10.2f %10.2f %10.2f %6.1f%% %s%n", s.category(), s.limit(),
                            s.spent(), s.remaining(), s.percentUsed(),
                            s.level() == BudgetStatus.Level.OK ? "" : s.level().name());
                }
            }
            case "delete" -> {
                String month = args.require("month", "2026-09");
                String category = args.require("category", "Dining");
                out.println(controller.deleteBudget(month, category) ? "Budget deleted." : "No such budget.");
            }
            default -> throw new IllegalArgumentException("Use: budget set | budget status | budget delete");
        }
    }

    private void printHelp() {
        out.println("""
                Commands (options in [brackets] are optional; month looks like 2026-09):
                  import --bank TD|CIBC --account <type> --file <statement.csv> [--yes]   F01 import + F02 categorize
                  transactions [--month M | --all] [--category C | --uncategorized]      list transactions with ids
                  categorize                                                              ask AI to categorize leftovers
                  categories [--month M | --all]                                         spending by category
                  recategorize --id N --category C [--similar] [--replace]                F03 fix a category (+ rule)
                  rules | rules delete --id N                                             saved category rules
                  budget set --month M --category C --amount A [--overwrite]              F04 set a budget
                  budget status [--month M]                                               F05 budget progress + alerts
                  budget delete --month M --category C
                  months                                                                  months that have data
                  help | exit
                Global option: --db <file>  use a different database file""");
    }

    // ------------------------------------------------------------ helpers

    private String month(Args args) {
        String month = args.get("month");
        return month == null ? controller.getDefaultMonth() : BudgetService.validateMonth(month);
    }

    private boolean confirm(String question) {
        out.print(question + " [y/N] ");
        out.flush();
        String answer = readLine();
        if (answer == null) {
            out.println();
            return false;
        }
        return answer.strip().toLowerCase(Locale.ROOT).startsWith("y");
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
