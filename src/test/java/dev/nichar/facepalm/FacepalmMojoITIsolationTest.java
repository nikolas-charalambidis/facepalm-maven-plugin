/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Nikolas Charalambidis
 */

package dev.nichar.facepalm;

import static org.assertj.core.api.Assertions.assertThat;

import com.soebes.itf.jupiter.extension.MavenRepository;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the concurrent {@link FacepalmMojoIT} (#416): each test must build with its own local repository, because
 * Maven 3.6.3 does not lock a shared one. {@code @MavenRepository} on the class, a nested class or a method makes the
 * tests share a repository.
 *
 * @author Nikolas Charalambidis
 */
class FacepalmMojoITIsolationTest {

    @Test
    void integration_tests_do_not_share_a_local_repository() {
        assertThat(annotatedWithMavenRepository(FacepalmMojoIT.class))
            .as("FacepalmMojoIT runs concurrently; remove @MavenRepository so each test keeps its own repository")
            .isEmpty();
    }

    private static List<String> annotatedWithMavenRepository(final Class<?> type) {
        final var hits = new ArrayList<String>();
        if (hasMavenRepository(type)) {
            hits.add(type.getName());
        }
        Arrays.stream(type.getDeclaredMethods())
            .filter(FacepalmMojoITIsolationTest::hasMavenRepository)
            .forEach(method -> hits.add(type.getName() + "#" + method.getName()));
        for (final var nested : type.getDeclaredClasses()) {
            hits.addAll(annotatedWithMavenRepository(nested));
        }
        return hits;
    }

    /**
     * Direct, inherited ({@code @MavenRepository} is {@code @Inherited}) or through a composed annotation.
     */
    private static boolean hasMavenRepository(final AnnotatedElement element) {
        return element.isAnnotationPresent(MavenRepository.class)
            || Arrays.stream(element.getAnnotations())
                .anyMatch(annotation -> annotation.annotationType().isAnnotationPresent(MavenRepository.class));
    }
}
