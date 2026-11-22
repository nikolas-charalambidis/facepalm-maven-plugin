/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm.module;

import com.google.inject.AbstractModule;
import dev.nichar.facepalm.engine.FacepalmLog;
import lombok.RequiredArgsConstructor;

/**
 * Guice module binding the active {@link FacepalmLog} for internal component use: the adapted Maven log in the Mojos,
 * a console log in the CLI.
 *
 * @author Nikolas Charalambidis
 * @since 1.0.0
 */
@RequiredArgsConstructor
public class FacepalmLogModule extends AbstractModule {

    private final FacepalmLog log;

    @Override
    protected void configure() {
        bind(FacepalmLog.class).toInstance(log);
    }
}
