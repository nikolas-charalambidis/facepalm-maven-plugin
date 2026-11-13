/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.nichar.facepalm.module.FacepalmConfigModule;
import dev.nichar.facepalm.module.FacepalmLogModule;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.eclipse.sisu.space.BeanScanning;
import org.eclipse.sisu.space.SpaceModule;
import org.eclipse.sisu.space.URLClassSpace;
import org.eclipse.sisu.wire.WireModule;

/**
 * The plugin's real dependency injection wiring (as in {@link FacepalmCLI}), so tests use components as Maven does
 * instead of setting their private fields. Each call creates a fresh container with its own singletons.
 */
public final class TestContainer {

    private TestContainer() {
    }

    /**
     * Creates a container bound to the given configuration.
     */
    public static Injector create(final FacepalmConfig config) {
        return Guice.createInjector(
            new WireModule(
                new SpaceModule(new URLClassSpace(TestContainer.class.getClassLoader()), BeanScanning.CACHE),
                new FacepalmLogModule(new SystemStreamLog()),
                new FacepalmConfigModule(config)));
    }
}
