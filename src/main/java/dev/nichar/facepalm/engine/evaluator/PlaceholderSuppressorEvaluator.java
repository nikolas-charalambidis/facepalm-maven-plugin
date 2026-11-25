/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.pattern.ValueClassifier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.Nonnull;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Suppresses findings that appear to be placeholders, templates, or dummy data.
 * Prevents noise from non-functional secrets like {@code ${VARIABLE_NAME}} or "your_password_here".
 */
@Named
@Singleton
@SuppressFBWarnings(value = "REDOS", justification = "False positive: only possessive quantifiers, which cannot backtrack.")
class PlaceholderSuppressorEvaluator implements FindingEvaluator {

    /**
     * Environment variable name: upper-case letters and digits in at least two parts joined by {@code _}, no
     * lower-case letters. Random tokens mix cases or have no {@code _}, so AWS key IDs, upper-case hex or base32 do
     * not match.
     */
    private static final Pattern ENVIRONMENT_VARIABLE_NAME = Pattern.compile("[A-Z][A-Z0-9]*+(?:_[A-Z0-9]++)++");

    @Inject
    private FacepalmConfig config;

    @Override
    public void evaluate(@Nonnull final Finding finding, @Nonnull final FileContext context) {
        final var value = finding.getSecretValue().trim().replaceAll("[,;\"']+$", "");

        final var conf = config.getEvaluators();
        // Penalize property interpolation patterns typical of configuration templates, and references in other
        // templating syntaxes such as #{...}, $(VAR) or ~{...}~ (#386).
        if (isPlaceholder(value, conf.getInterpolationPattern()) || isPlaceholder(connectionPassword(finding, value),
            conf.getInterpolationPattern())) {
            finding.log("Interpolation/Placeholder Shield", -50, -100);
            return;
        }
        // A SCREAMING_SNAKE_CASE value names an environment variable, as in "server-password: CENTRAL_TOKEN_PASSWORD"
        // (#225). It is a reference like ${VAR}, so it gets the same discount and stays visible at INFO.
        if (ENVIRONMENT_VARIABLE_NAME.matcher(value).matches()) {
            finding.log("Environment Variable Name", -50, -100);
            return;
        }

        // A private key block is long base64 that may contain a keyword such as "fake" by chance; like the entropy
        // check, the keyword discount does not apply to it.
        if (finding.getPatternName().contains("Private Key") || value.contains("-----")) {
            return;
        }
        // Values no one would use as a secret (#386): a predictable dummy such as AAAA, 0123abcd or A1b2C3, and code
        // or a format description that a pattern matched as the value.
        if (ValueClassifier.isPredictable(value)) {
            finding.log("Predictable Value", -50, -100);
            return;
        }
        if (ValueClassifier.isNotAValue(value, context.getLineOrEmpty(finding.getLineNumber() - 1))) {
            finding.log("Code or Format Description", -50, -100);
            return;
        }
        final var lowerVal = normalize(value);
        // Penalize known dummy keywords to reduce false positives from sample data. Keywords are normalized like the
        // value, so configured ones with upper-case letters match too (#227).
        if (conf.getDummyKeywords().stream().map(PlaceholderSuppressorEvaluator::normalize).anyMatch(
            lowerVal::contains)) {
            finding.log("Dummy Keyword Discount", 0, -80);
        }
    }

    /**
     * Whether the value is a placeholder: it matches the configured interpolation pattern or is a reference in another
     * templating syntax.
     */
    private static boolean isPlaceholder(@Nonnull final String value, @Nonnull final Pattern interpolation) {
        return !value.isEmpty() && (interpolation.matcher(value).matches() || ValueClassifier.isReference(value));
    }

    /**
     * Returns the password part of a database connection finding ({@code user:password}), or an empty string for
     * other findings. A connection URI holds no secret when its password is a placeholder such as
     * {@code $(DB_PASSWORD)}.
     */
    @Nonnull
    private static String connectionPassword(@Nonnull final Finding finding, @Nonnull final String value) {
        final var colon = value.indexOf(':');
        return finding.getPatternName().endsWith(" Connection") && colon >= 0 ? value.substring(colon + 1) : "";
    }

    /**
     * Lower-cases the text and replaces {@code -} and spaces with {@code _}.
     */
    @Nonnull
    private static String normalize(@Nonnull final String text) {
        return text.toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
    }
}
