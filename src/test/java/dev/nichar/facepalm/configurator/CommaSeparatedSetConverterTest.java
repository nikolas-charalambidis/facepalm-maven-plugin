/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.configurator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.configurator.expression.DefaultExpressionEvaluator;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Comma-separated and nested XML values for collection parameters such as {@code skipDirs}.
 */
class CommaSeparatedSetConverterTest {

    private final CommaSeparatedSetConverter converter = new CommaSeparatedSetConverter();

    @Test
    void converts_only_sets() {
        assertTrue(converter.canConvert(Set.class));
        assertFalse(converter.canConvert(List.class));
    }

    @Test
    void splits_comma_separated_value_trimming_and_deduplicating_in_order() throws Exception {
        final var configuration = new XmlPlexusConfiguration("skipDirs");
        configuration.setValue(" target , node_modules,,target , dist ");

        assertEquals(List.of("target", "node_modules", "dist"), List.copyOf(convert(configuration)));
    }

    @Test
    void reads_nested_children() throws Exception {
        final var configuration = new XmlPlexusConfiguration("skipDirs");
        configuration.addChild("skipDir", "target");
        configuration.addChild("skipDir", "logs");

        assertEquals(List.of("target", "logs"), List.copyOf(convert(configuration)));
    }

    /**
     * {@code -DskipDirs=} yields an empty set, which {@code EngineConfig} treats as "use the defaults".
     */
    @Test
    void blank_value_without_children_is_an_empty_set() throws Exception {
        final var configuration = new XmlPlexusConfiguration("skipDirs");
        configuration.setValue("  ");

        assertTrue(convert(configuration).isEmpty());
    }

    private Set<?> convert(final PlexusConfiguration configuration) throws ComponentConfigurationException {
        return (Set<?>) converter.fromConfiguration(null, configuration, Set.class, null,
            getClass().getClassLoader(), new DefaultExpressionEvaluator(), null);
    }
}
