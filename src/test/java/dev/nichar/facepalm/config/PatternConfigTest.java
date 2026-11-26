/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.nichar.facepalm.pattern.SecretPattern;
import dev.nichar.facepalm.pattern.SecretPatternRegistry;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PatternConfigTest {
    @Test
    void additions_preserve_defaults_and_java_entry_points() {
        final var custom = new SecretPattern("Organization", Pattern.compile("ORG_[a-z]{24}"), 90, 95, false);
        final var config = new PatternConfig(List.of(custom));
        assertThat(config.isConfigured()).isTrue();
        assertThat(config.getOverrides()).containsExactlyElementsOf(
            java.util.stream.Stream.concat(SecretPatternRegistry.DEFAULT_PATTERNS.stream(), List.of(custom).stream())
                .toList());
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> config.getOverrides().clear());
        config.setOverrides(List.of());
        assertThat(config.getOverrides()).containsExactlyElementsOf(SecretPatternRegistry.DEFAULT_PATTERNS);
        assertThat(new PatternConfig().isConfigured()).isFalse();
    }

    @Test
    void exclusions_are_explicit_and_defaults_are_unchanged() {
        final var config = new PatternConfig();
        final var name = SecretPatternRegistry.DEFAULT_PATTERNS.get(0).getName();
        config.setDisabledPatterns(Set.of(" " + name + " "));
        assertThat(config.getOverrides()).noneMatch(pattern -> name.equals(pattern.getName()));
        assertThat(new PatternConfig().getOverrides()).containsExactlyElementsOf(
            SecretPatternRegistry.DEFAULT_PATTERNS);
        config.setDisabledPatterns(Set.of("unknown"));
        assertThatIllegalArgumentException().isThrownBy(config::getOverrides).withMessageContaining("Unknown disabled");
        config.setDisabledPatterns(SecretPatternRegistry.DEFAULT_PATTERNS.stream().map(SecretPattern::getName)
            .collect(java.util.stream.Collectors.toSet()));
        assertThatIllegalArgumentException().isThrownBy(config::getOverrides).withMessageContaining("remain active");
    }

    @Test
    void duplicates_are_rejected_even_when_disabled() {
        final var builtin = SecretPatternRegistry.DEFAULT_PATTERNS.get(0);
        final var config = new PatternConfig(List.of(builtin));
        config.setDisabledPatterns(Set.of(builtin.getName()));
        assertThatIllegalArgumentException().isThrownBy(config::getOverrides).withMessageContaining("Duplicate");
        final var custom = definition().toSecretPattern();
        assertThatIllegalArgumentException().isThrownBy(() -> new PatternConfig(List.of(custom, custom))
            .getOverrides());
    }

    @Test
    void definition_compiles_with_explicit_scores_and_flags() {
        final var dto = definition();
        dto.setName(" Organization ");
        dto.setRegex("(?s)BEGIN\\R[a-z]{4}\\REND");
        dto.setRisk(0);
        dto.setConfidence(100);
        dto.setMultiLine(true);
        final var pattern = dto.toSecretPattern();
        assertThat(pattern.getName()).isEqualTo("Organization");
        assertThat(pattern.isMultiLine()).isTrue();
        assertThat(pattern.getBaseRisk()).isZero();
        assertThat(pattern.getBaseConfidence()).isEqualTo(100);
        assertThat(pattern.getPattern().matcher("BEGIN\ntext\nEND").find()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blank_fields_are_rejected(final String value) {
        final var dto = definition();
        dto.setName(value);
        assertThatIllegalArgumentException().isThrownBy(dto::toSecretPattern);
        dto.setName("Organization");
        dto.setRegex(value);
        assertThatIllegalArgumentException().isThrownBy(dto::toSecretPattern);
    }

    @Test
    void invalid_scores_and_regex_are_rejected_without_echoing_regex() {
        for (final Integer value : java.util.Arrays.asList(null, -1, 101)) {
            final var dto = definition();
            dto.setRisk(value);
            assertThatIllegalArgumentException().isThrownBy(dto::toSecretPattern);
            dto.setRisk(90);
            dto.setConfidence(value);
            assertThatIllegalArgumentException().isThrownBy(dto::toSecretPattern);
        }
        final var dto = definition();
        dto.setRegex("private-value[");
        assertThatIllegalArgumentException().isThrownBy(dto::toSecretPattern)
            .withMessageContaining("Organization").withMessageNotContaining("private-value");
    }

    @Test
    void invalid_programmatic_definitions_fail_before_scanning() {
        final var regex = Pattern.compile("ORG_[a-z]{24}");
        final var invalid = java.util.Arrays.asList(
            (SecretPattern) null,
            new SecretPattern(null, regex, 90, 95, false),
            new SecretPattern(" ", regex, 90, 95, false),
            new SecretPattern("Organization", null, 90, 95, false),
            new SecretPattern("Organization", regex, -1, 95, false),
            new SecretPattern("Organization", regex, 101, 95, false),
            new SecretPattern("Organization", regex, 90, -1, false),
            new SecretPattern("Organization", regex, 90, 101, false));
        for (final var pattern : invalid) {
            assertThatIllegalArgumentException().isThrownBy(
                () -> new PatternConfig(java.util.Collections.singletonList(pattern)).getOverrides());
        }
        final var config = new PatternConfig(List.of(definition().toSecretPattern()));
        config.setDisabledPatterns(Set.of("Organization"));
        assertThat(config.getOverrides()).containsExactlyElementsOf(SecretPatternRegistry.DEFAULT_PATTERNS);
        config.setDisabledPatterns(java.util.Collections.singleton(null));
        assertThatIllegalArgumentException().isThrownBy(config::getOverrides);
        config.setDisabledPatterns(null);
        assertThat(config.getOverrides()).hasSize(SecretPatternRegistry.DEFAULT_PATTERNS.size() + 1);
    }

    @Test
    void custom_confidence_controls_high_volume_scoring(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path root)
            throws Exception {
        final var random = new java.util.Random(79);
        final var alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        final var lines = new java.util.ArrayList<String>();
        for (int i = 0; i < 16; i++) {
            final var value = new StringBuilder("ORG_");
            for (int c = 0; c < 24; c++) {
                value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            lines.add(value.toString());
        }
        java.nio.file.Files.write(root.resolve("data.properties"), lines);
        for (final var confidence : List.of(95, 50)) {
            final var config = new dev.nichar.facepalm.FacepalmConfig();
            config.setPatterns(new PatternConfig(List.of(new SecretPattern("Organization",
                Pattern.compile("ORG_[A-Za-z0-9]{24}"), 90, confidence, false))));
            final var findings = dev.nichar.facepalm.TestContainer.create(config)
                .getInstance(dev.nichar.facepalm.engine.ScannerEngine.class).scan(root);
            assertThat(findings).hasSize(16).allSatisfy(finding -> assertThat(finding.getScoreHistory().stream()
                .anyMatch(entry -> entry.startsWith("High Volume File")))
                    .isEqualTo(confidence < 90));
        }
    }

    private static CustomPatternConfig definition() {
        final var dto = new CustomPatternConfig();
        dto.setName("Organization");
        dto.setRegex("ORG_[a-z]{24}");
        dto.setRisk(90);
        dto.setConfidence(95);
        return dto;
    }
}
