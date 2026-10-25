package com.financeassistant.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.financeassistant.controller.AgentController;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.FakeLLMClient;
import com.financeassistant.model.Transaction;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class CLITest {
    private static final String TD = "sample-data/td-chequing-jul-sep-2026.csv";
    private DatabaseManager db;

    @Before
    public void setUp() {
        db = DatabaseManager.open(null);
    }

    /** Claude stand-in: rent lines -> Rent, everything else -> Other. */
    private static FakeLLMClient fakeClaude() {
        return new FakeLLMClient(prompt -> {
            StringBuilder json = new StringBuilder("{");
            for (String line : prompt.split("\n")) {
                if (line.matches("\\d+\\. .*")) {
                    String n = line.substring(0, line.indexOf('.'));
                    json.append(json.length() > 1 ? "," : "")
                            .append('"').append(n).append("\":\"").append(line.contains("RENT") ? "Rent" : "Other").append('"');
                }
            }
            return json.append('}').toString();
        });
    }

    private String run(String stdin, String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CLI cli = new CLI(new AgentController(fakeClaude()),
                new BufferedReader(new StringReader(stdin)), new PrintStream(bytes, true));
        cli.run(args);
        return bytes.toString();
    }

    @Test
    public void importAsksForConfirmationAndCategorizes() {
        String cancelled = run("n\n", "import", "--bank", "TD", "--account", "Chequing", "--file", TD);
        assertTrue(cancelled.contains("nothing was saved"));
        assertEquals(0, db.findAll(Transaction.class).size());

        String output = run("y\n", "import", "--bank", "TD", "--account", "Chequing", "--file", TD);
        assertTrue(output, output.contains("Imported 38 transaction(s)"));
        assertTrue(output.contains("by AI"));
        assertTrue(db.findAll(Transaction.class).stream().noneMatch(Transaction::isUncategorized));
    }

    @Test
    public void budgetAlertIsPrintedAfterImport() {
        run("", "budget", "set", "--month", "2026-09", "--category", "Rent", "--amount", "1500");
        String output = run("", "import", "--bank", "TD", "--account", "Chequing", "--file", TD, "--yes");
        assertTrue(output, output.contains("[ALERT] Rent budget for 2026-09 is 97% used"));
        String status = run("", "budget", "status", "--month", "2026-09");
        assertTrue(status, status.contains("1450.00"));
        assertTrue(status.contains("WARNING"));
    }

    @Test
    public void recategorizeConflictCanKeepTheExistingRule() {
        run("", "import", "--bank", "TD", "--account", "Chequing", "--file", TD, "--yes");
        List<Transaction> netflix = db.find(Transaction.class, t -> t.getDescription().startsWith("NETFLIX"));
        run("", "recategorize", "--id", String.valueOf(netflix.get(0).getId()), "--category", "Subscriptions", "--similar");
        String output = run("n\n", "recategorize", "--id", String.valueOf(netflix.get(1).getId()),
                "--category", "Entertainment", "--similar");
        assertTrue(output, output.contains("Keeping the existing rule"));
        assertTrue(run("", "rules").contains("\"NETFLIX.COM\" -> Subscriptions"));
    }

    @Test
    public void errorsAreReportedNotThrown() {
        assertTrue(run("", "import", "--bank", "TD", "--file", TD).contains("Missing --account"));
        assertTrue(run("", "budget", "set", "--month", "2026-09", "--category", "Dining", "--amount", "abc")
                .contains("must be a number"));
        assertTrue(run("", "recategorize", "--id", "999", "--category", "Dining").contains("No transaction with id 999"));
        assertTrue(run("", "frobnicate").contains("Unknown command"));
        assertTrue(run("", "budget", "status", "--month", "2026-01").contains("No budgets set"));
    }

    @Test
    public void interactiveModeRunsCommandsUntilExit() {
        String output = run("help\nmonths\nexit\n");
        assertTrue(output.contains("Commands"));
        assertTrue(output.contains("fake"));
    }

    @Test
    public void tokenizeKeepsQuotedText() {
        assertEquals(List.of("import", "--file", "my statement.csv", "--yes"),
                Args.tokenize("import --file \"my statement.csv\"  --yes"));
    }
}
