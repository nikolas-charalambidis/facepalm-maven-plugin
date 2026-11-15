/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.engine.evaluator;

import dev.nichar.facepalm.engine.FileContext;
import dev.nichar.facepalm.engine.Finding;
import dev.nichar.facepalm.engine.GitIgnoreService;
import jakarta.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Evaluates findings based on their visibility in Git history.
 * Reduces risk for files that match .gitignore patterns and are not tracked by Git, suggesting lower public exposure.
 */
@Named
@Singleton
class PublicExposureEvaluator implements FindingEvaluator {

    private final GitIgnoreService gitIgnoreService;

    /**
     * Requires {@code @Inject}: without it Sisu cannot create this component and silently drops it (issue #194).
     */
    @Inject
    PublicExposureEvaluator(@Nonnull final GitIgnoreService gitIgnoreService) {
        this.gitIgnoreService = gitIgnoreService;
    }

    @Override
    public void evaluate(@Nonnull final Finding finding, @Nonnull final FileContext context) {
        // De-prioritize findings in files that are ignored by Git and not tracked: a tracked file is committed and
        // public even if it matches .gitignore (force-added, or committed before it was ignored, #223).
        if (gitIgnoreService.isIgnored(context.getPath()) && !gitIgnoreService.isTracked(context.getPath())) {
            finding.log("Recursive .gitignore Match (Low Exposure)", -40, 0);
        }
    }
}
