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
 * Evaluates findings based on file extensions.
 * Prioritizes sensitive configuration formats and discounts findings in documentation or logs.
 */
@Named
@Singleton
class FileExtensionEvaluator implements FindingEvaluator {

    @Inject
    private FacepalmConfig config;

    @Override
    public void evaluate(@Nonnull final Finding finding, @Nonnull final FileContext context) {
        final var fileName = context.getPath().getFileName().toString().toLowerCase(Locale.ROOT);

        // Elevate risk for sensitive formats like .env or .properties.
        if (config.getEvaluators().getHighRiskExtensions().stream().anyMatch(fileName::endsWith)) {
            finding.log("High Risk Configuration File", 15, 20);
        }
        // Discount findings in low-risk files like documentation or logs.
        else if (config.getEvaluators().getLowRiskExtensions().stream().anyMatch(fileName::endsWith)) {
            finding.log("Documentation/Log File", -30, -40);
        }
    }
}
