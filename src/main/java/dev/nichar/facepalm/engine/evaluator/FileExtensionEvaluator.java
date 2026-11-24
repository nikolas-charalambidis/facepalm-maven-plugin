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
import java.util.Objects;
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
        final var fileName = Objects.toString(context.getPath().getFileName(), "").toLowerCase(Locale.ROOT);
        final var lowRisk = config.getEvaluators().getLowRiskExtensions().stream().anyMatch(fileName::endsWith);

        // Elevate risk for sensitive formats like .env or .properties.
        if (config.getEvaluators().getHighRiskExtensions().stream()
            .anyMatch(extension -> isHighRisk(fileName, extension, lowRisk))) {
            finding.log("High Risk Configuration File", 15, 20);
        }
        // Discount findings in low-risk files like documentation or logs.
        else if (lowRisk) {
            finding.log("Documentation/Log File", -30, -40);
        }
    }

    /**
     * A file has a high-risk extension if its name ends with it ({@code .env}, {@code app.env}) or, for a dotfile
     * named after it, is a variant of it ({@code .env.local}, {@code .env.production}, #288). A variant that ends with
     * a low-risk extension is a template ({@code .env.example}, {@code .env.sample}) and is not high-risk.
     */
    private static boolean isHighRisk(final String fileName, final String extension, final boolean lowRisk) {
        return fileName.endsWith(extension) || !lowRisk && fileName.startsWith(extension + ".");
    }
}
