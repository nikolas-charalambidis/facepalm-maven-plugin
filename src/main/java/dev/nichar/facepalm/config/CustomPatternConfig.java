/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import dev.nichar.facepalm.pattern.SecretPattern;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.Data;

/** Maven-bindable definition of an additional detector. */
@Data
public class CustomPatternConfig {
    private String name;
    private String regex;
    private Integer risk;
    private Integer confidence;
    private boolean multiLine;

    /** Validates and compiles this definition without including regex contents in diagnostics. */
    @SuppressWarnings("PMD.PreserveStackTrace") // PatternSyntaxException includes the potentially sensitive regex.
    public SecretPattern toSecretPattern() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Custom pattern name must not be blank");
        }
        final var label = name.strip();
        if (regex == null || regex.isBlank()) {
            throw new IllegalArgumentException("Custom pattern '" + label + "': regex must not be blank");
        }
        if (risk == null || risk < 0 || risk > 100 || confidence == null || confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("Custom pattern '" + label
                + "': risk and confidence must be between 0 and 100");
        }
        try {
            return new SecretPattern(label, Pattern.compile(regex), risk, confidence, multiLine);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Custom pattern '" + label + "': invalid regex (index " + e.getIndex()
                + ")");
        }
    }
}
