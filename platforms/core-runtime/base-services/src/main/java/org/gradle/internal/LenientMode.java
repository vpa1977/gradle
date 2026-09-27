/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.internal;

import org.jspecify.annotations.Nullable;

/**
 * Global switch for the lenient build mode, guarded by the {@code GRADLE_LENIENT}
 * environment variable. When enabled, unknown plugins, unresolvable dependencies,
 * unknown extensions and statements referencing unknown types are skipped with a
 * warning instead of failing the build.
 */
public final class LenientMode {
    private static final String ENV_VAR_NAME = "GRADLE_LENIENT";
    private static final boolean ENABLED = isEnabledValue(System.getenv(ENV_VAR_NAME));

    private LenientMode() {
    }

    /**
     * Returns whether lenient mode is enabled for this JVM. The environment is
     * fixed for the JVM lifetime, so the result is cached.
     */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * Returns whether the given {@code GRADLE_LENIENT} value enables lenient mode.
     * Any value other than null, empty, "0", "false" or "no" (compared
     * case-insensitively after trimming) enables it.
     */
    static boolean isEnabledValue(@Nullable String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty()
            && !trimmed.equalsIgnoreCase("0")
            && !trimmed.equalsIgnoreCase("false")
            && !trimmed.equalsIgnoreCase("no");
    }
}
