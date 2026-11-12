/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.convention;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Enforces the convention that every static field (final or not) in production code has a Javadoc comment.
 * PMD cannot express this: XPath rules do not see comments and {@code CommentRequired} covers all fields.
 */
class StaticFieldJavadocTest {

    /**
     * Production sources checked by this convention.
     */
    private static final Path SOURCES = Path.of("src/main/java");

    /**
     * A static field declaration: modifiers including {@code static}, then a type and a name followed by
     * {@code =} or {@code ;} before any parenthesis (which would make it a method).
     */
    private static final Pattern STATIC_FIELD = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|final|transient|volatile)\\s+)*static\\s+"
            + "(?:(?:final|transient|volatile)\\s+)*[\\w.<>\\[\\], ?]+\\s+\\w+\\s*[=;].*");

    @Test
    void every_static_field_has_javadoc() throws IOException {
        final List<String> violations = new ArrayList<>();
        try (var files = Files.walk(SOURCES)) {
            for (final var file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                final var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (STATIC_FIELD.matcher(lines.get(i)).matches() && !hasJavadoc(lines, i)) {
                        violations.add(file + ":" + (i + 1) + "  " + lines.get(i).trim());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            () -> "Static fields without Javadoc:\n" + String.join("\n", violations));
    }

    /**
     * Walks upwards over annotations to the closing line of the preceding comment and checks it is a Javadoc.
     */
    private static boolean hasJavadoc(final List<String> lines, final int declaration) {
        int i = declaration - 1;
        while (i >= 0 && lines.get(i).trim().startsWith("@")) {
            i--;
        }
        if (i < 0 || !lines.get(i).trim().endsWith("*/")) {
            return false;
        }
        while (i >= 0 && !lines.get(i).contains("/*")) {
            i--;
        }
        return i >= 0 && lines.get(i).trim().startsWith("/**");
    }
}
