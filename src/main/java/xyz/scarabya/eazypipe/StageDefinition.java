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

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Internal immutable description of a pipeline stage.
 */
sealed interface StageDefinition
        permits StageDefinition.SourceStage, StageDefinition.ProcessorStage, StageDefinition.SinkStage {

    StageConfig config();

    record SourceStage<O>(StageConfig config, PipeSource<O> source) implements StageDefinition {
        public SourceStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    record ProcessorStage<I, O>(StageConfig config, PipeProcessor<? super I, O> processor)
            implements StageDefinition {
        public ProcessorStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(processor, "processor must not be null");
        }
    }

    record SinkStage<I, R>(StageConfig config, PipeSink<? super I> sink, Supplier<R> resultSupplier)
            implements StageDefinition {
        public SinkStage {
            Objects.requireNonNull(config, "config must not be null");
            Objects.requireNonNull(sink, "sink must not be null");
            Objects.requireNonNull(resultSupplier, "resultSupplier must not be null");
        }
    }
}
