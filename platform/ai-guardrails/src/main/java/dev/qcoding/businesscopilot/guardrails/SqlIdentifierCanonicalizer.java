package dev.qcoding.businesscopilot.guardrails;

import java.util.Locale;

/** Canonicalizes PostgreSQL double-quoted and MySQL backtick-quoted identifiers. */
final class SqlIdentifierCanonicalizer {

    private SqlIdentifierCanonicalizer() {
    }

    static String qualifiedName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return "";
        }
        String[] parts = qualifiedName.split("\\.");
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            if (!canonical.isEmpty()) {
                canonical.append('.');
            }
            canonical.append(identifier(part.trim()));
        }
        return canonical.toString();
    }

    static String identifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        if (identifier.length() >= 2
                && identifier.startsWith("\"")
                && identifier.endsWith("\"")) {
            String exact = identifier.substring(1, identifier.length() - 1)
                    .replace("\"\"", "\"");
            if (exact.equals(exact.toLowerCase(Locale.ROOT))) {
                return exact;
            }
            return '"' + exact.replace("\"", "\"\"") + '"';
        }
        if (identifier.length() >= 2
                && identifier.startsWith("`")
                && identifier.endsWith("`")) {
            return identifier.substring(1, identifier.length() - 1)
                    .replace("``", "`")
                    .toLowerCase(Locale.ROOT);
        }
        return identifier.toLowerCase(Locale.ROOT);
    }

    static String simpleName(String qualifiedName) {
        String canonical = qualifiedName(qualifiedName);
        int separator = canonical.lastIndexOf('.');
        return separator >= 0 ? canonical.substring(separator + 1) : canonical;
    }
}
