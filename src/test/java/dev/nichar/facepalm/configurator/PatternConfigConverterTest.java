/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.configurator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nichar.facepalm.config.PatternConfig;
import java.util.List;
import org.codehaus.plexus.component.configurator.converters.lookup.DefaultConverterLookup;
import org.codehaus.plexus.component.configurator.expression.DefaultExpressionEvaluator;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.junit.jupiter.api.Test;

class PatternConfigConverterTest {
    @Test
    void binds_mutable_definitions_and_exclusions() throws Exception {
        final var xml = new XmlPlexusConfiguration("patterns");
        final var pattern = xml.getChild("overrides").getChild("pattern");
        pattern.addChild("name", "Organization");
        pattern.addChild("regex", "ORG_[a-z]{24}");
        pattern.addChild("risk", "90");
        pattern.addChild("confidence", "95");
        xml.getChild("disabledPatterns").addChild("name", "AWS Access Key");
        final var config = convert(xml);
        assertThat(config.getOverrides()).last().satisfies(value -> {
            assertThat(value.getName()).isEqualTo("Organization");
            assertThat(value.getBaseRisk()).isEqualTo(90);
            assertThat(value.isMultiLine()).isFalse();
        });
        assertThat(config.getOverrides()).noneMatch(value -> value.getName().equals("AWS Access Key"));
    }

    @Test
    void empty_overrides_keep_defaults_and_unknown_fields_fail() throws Exception {
        assertThat(convert(new XmlPlexusConfiguration("patterns")).getOverrides())
            .hasSameSizeAs(new PatternConfig().getOverrides());
        final var xml = new XmlPlexusConfiguration("patterns");
        xml.getChild("overrides");
        assertThat(convert(xml).getOverrides()).hasSameSizeAs(new PatternConfig().getOverrides());
        xml.addChild("typo", "ignored");
        assertThatThrownBy(() -> convert(xml)).hasMessageContaining("typo");
    }

    private static PatternConfig convert(final XmlPlexusConfiguration xml) throws Exception {
        final var lookup = new DefaultConverterLookup();
        lookup.registerConverter(new CommaSeparatedSetConverter());
        final var converter = new PatternConfigConverter();
        assertThat(converter.canConvert(List.class)).isFalse();
        assertThat(converter.canConvert(PatternConfig.class)).isTrue();
        return (PatternConfig) converter.fromConfiguration(lookup, xml, PatternConfig.class, null,
            PatternConfigConverterTest.class.getClassLoader(), new DefaultExpressionEvaluator(), null);
    }
}
