/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import dev.nichar.facepalm.FacepalmConfig;
import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
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
        // Penalize property interpolation patterns typical of configuration templates.
        if (conf.getInterpolationPattern().matcher(value).matches()) {
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
        final var lowerVal = value.toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        // Penalize known dummy keywords to reduce false positives from sample data.
        if (conf.getDummyKeywords().stream().anyMatch(lowerVal::contains)) {
            finding.log("Dummy Keyword Discount", 0, -80);
        }
    }
}
