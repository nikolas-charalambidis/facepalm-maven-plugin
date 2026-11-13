/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.configurator;

import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.configurator.BasicComponentConfigurator;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.configurator.ComponentConfigurator;
import org.codehaus.plexus.component.configurator.ConfigurationListener;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.PlexusConfiguration;

/**
 * Custom Maven configurator for mapping comma-separated strings to {@link java.util.Set} parameters.
 * Enables flat XML values and CLI properties to be injected as unique, trimmed sets.
 */
@Component(role = ComponentConfigurator.class, hint = CommaSeparatedConfigurator.COMMA_SEPARATED_CONFIGURATOR)
public class CommaSeparatedConfigurator extends BasicComponentConfigurator {

    /**
     * Unique hint for linking this configurator to Maven Mojos.
     */
    public static final String COMMA_SEPARATED_CONFIGURATOR = "comma-separated-configurator";

    /**
     * Registers the converter for Set-based parameters once. The configurator is a singleton, so registering it on
     * every execution would grow the converter list without bound (large reactors, mvnd).
     */
    public CommaSeparatedConfigurator() {
        super();
        converterLookup.registerConverter(new CommaSeparatedSetConverter());
    }

    @Override
    public void configureComponent(final Object component,
                                   final PlexusConfiguration configuration,
                                   final ExpressionEvaluator evaluator,
                                   final ClassRealm realm,
                                   final ConfigurationListener listener) throws ComponentConfigurationException {

        // Delegate component configuration to the base Plexus logic.
        super.configureComponent(component, configuration, evaluator, realm, listener);
    }
}
