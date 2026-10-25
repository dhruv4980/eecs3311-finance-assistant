package com.financeassistant.controller;

import com.financeassistant.categorization.CategorizationResult;
import com.financeassistant.importer.ImportResult;
import java.util.List;

/** Everything that happened when an import was confirmed: saved rows, categorization and any budget alerts. */
public record ImportOutcome(ImportResult importResult, CategorizationResult categorization, List<String> alerts) {
}
