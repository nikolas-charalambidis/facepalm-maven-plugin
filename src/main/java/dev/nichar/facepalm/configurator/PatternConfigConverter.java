/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.configurator;

import dev.nichar.facepalm.config.CustomPatternConfig;
import dev.nichar.facepalm.config.PatternConfig;
import java.util.List;
import java.util.Set;
import lombok.Data;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.configurator.ConfigurationListener;
import org.codehaus.plexus.component.configurator.converters.AbstractConfigurationConverter;
import org.codehaus.plexus.component.configurator.converters.lookup.ConverterLookup;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/** Adapts Maven's mutable XML model to the existing compiled-pattern Java API. */
public class PatternConfigConverter extends AbstractConfigurationConverter {
    /** Intermediate bean; standard Plexus converters handle fields, collections and expressions. */
    @Data
    public static class Configuration {
        private List<CustomPatternConfig> overrides;
        private Set<String> disabledPatterns;
    }

    @Override
    public boolean canConvert(final Class<?> type) {
        return type == PatternConfig.class;
    }

    @Override
    public Object fromConfiguration(final ConverterLookup lookup,
                                    final PlexusConfiguration configuration,
                                    final Class<?> type,
                                    final Class<?> baseType,
                                    final ClassLoader loader,
                                    final ExpressionEvaluator evaluator,
                                    final ConfigurationListener listener)
            throws ComponentConfigurationException {
        final var bean = (Configuration) lookup.lookupConverterForType(Configuration.class)
            .fromConfiguration(lookup, configuration, Configuration.class, baseType, loader, evaluator, listener);
        try {
            final var result = new PatternConfig();
            if (bean.getOverrides() != null) {
                result.setOverrides(bean.getOverrides().stream().map(definition -> {
                    if (definition == null) {
                        throw new IllegalArgumentException("Custom pattern must not be null");
                    }
                    return definition.toSecretPattern();
                }).toList());
            }
            result.setDisabledPatterns(bean.getDisabledPatterns());
            result.getOverrides();
            return result;
        } catch (IllegalArgumentException e) {
            throw new ComponentConfigurationException(e.getMessage(), e);
        }
    }
}
