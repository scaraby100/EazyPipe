/*
 * Copyright 2017-2026 Alessandro Patriarca.
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
package xyz.scarabya.eazypipe;

import java.util.Optional;

/**
 * Unchecked exception raised when a pipeline execution fails.
 */
public final class PipeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String stageName;

    /**
     * Creates a new {@link PipeException} without an associated stage name.
     *
     * @param message detail message
     * @param cause root cause
     */
    public PipeException(String message, Throwable cause) {
        this(null, message, cause);
    }

    /**
     * Creates a new {@link PipeException} associated with a specific stage.
     *
     * @param stageName name of the stage where the failure occurred, or {@code null}
     * @param message detail message
     * @param cause root cause
     */
    public PipeException(String stageName, String message, Throwable cause) {
        super(message, cause);
        this.stageName = stageName;
    }

    /**
     * Returns the name of the stage that failed, if available.
     *
     * @return an {@link Optional} containing the failing stage name
     */
    public Optional<String> stageName() {
        return Optional.ofNullable(stageName);
    }
}
