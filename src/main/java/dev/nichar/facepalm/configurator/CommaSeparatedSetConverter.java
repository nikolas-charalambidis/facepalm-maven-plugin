/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.configurator;

import dev.nichar.facepalm.config.CommaSeparatedValues;
import java.util.LinkedHashSet;
import java.util.Set;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.configurator.ConfigurationListener;
import org.codehaus.plexus.component.configurator.converters.AbstractConfigurationConverter;
import org.codehaus.plexus.component.configurator.converters.lookup.ConverterLookup;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/**
 * Custom Plexus converter for transforming comma-separated strings into {@link java.util.Set} objects.
 * Resolves Maven expressions and trims whitespace to ensure clean configuration injection.
 */
public class CommaSeparatedSetConverter extends AbstractConfigurationConverter {

    @Override
    public boolean canConvert(final Class<?> type) {
        return Set.class.isAssignableFrom(type);
    }

    @Override
    public Object fromConfiguration(final ConverterLookup converterLookup,
                                    final PlexusConfiguration configuration,
                                    final Class<?> type,
                                    final Class<?> baseType,
                                    final ClassLoader classLoader,
                                    final ExpressionEvaluator expressionEvaluator,
                                    final ConfigurationListener listener) throws ComponentConfigurationException {

        final Object evaluatedValue;
        try {
            // Resolve Maven expressions like "${project.build.directory}".
            evaluatedValue = expressionEvaluator.evaluate(configuration.getValue());
        } catch (Exception e) {
            throw new ComponentConfigurationException("Failed to evaluate configuration expression", e);
        }

        final var value = evaluatedValue != null ? evaluatedValue.toString() : null;

        // Split comma-delimited strings into unique, trimmed elements, like the CLI does (#235).
        if (value != null && !value.trim().isEmpty()) {
            return CommaSeparatedValues.split(value);
        }

        // Fallback: process traditional nested XML configuration tags.
        if (configuration.getChildCount() > 0) {
            final var set = new LinkedHashSet<>();
            for (final var child : configuration.getChildren()) {
                try {
                    final var evaluatedChild = expressionEvaluator.evaluate(child.getValue());
                    if (evaluatedChild != null) {
                        set.add(evaluatedChild.toString());
                    }
                } catch (Exception e) {
                    throw new ComponentConfigurationException("Failed to evaluate child configuration expression", e);
                }
            }
            return set;
        }

        // Default to an empty set if no configuration is provided.
        return new LinkedHashSet<>();
    }
}
