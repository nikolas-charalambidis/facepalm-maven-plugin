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

        final var lowerVal = value.toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        // Penalize known dummy keywords to reduce false positives from sample data.
        if (conf.getDummyKeywords().stream().anyMatch(lowerVal::contains)) {
            finding.log("Dummy Keyword Discount", 0, -80);
        }
    }
}
