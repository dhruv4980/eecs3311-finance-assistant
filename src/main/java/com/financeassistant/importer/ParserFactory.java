package com.financeassistant.importer;

import java.util.List;
import java.util.Locale;

/**
 * Factory: chooses the parser for the bank the user selected, so ImportService
 * never needs to know which concrete parser classes exist. Supporting a new
 * bank means adding one parser class and one case here.
 */
public class ParserFactory {

    public StatementParser createParser(String bankName) {
        String bank = bankName == null ? "" : bankName.strip().toUpperCase(Locale.ROOT);
        return switch (bank) {
            case "TD" -> new TDParser();
            case "CIBC" -> new CIBCParser();
            default -> throw new IllegalArgumentException(
                    "Unsupported bank '" + bankName + "'. Supported banks: " + String.join(", ", supportedBanks()));
        };
    }

    public List<String> supportedBanks() {
        return List.of("TD", "CIBC");
    }
}
