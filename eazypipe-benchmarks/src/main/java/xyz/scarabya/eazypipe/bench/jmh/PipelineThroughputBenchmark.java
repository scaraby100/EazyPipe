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
package xyz.scarabya.eazypipe.bench.jmh;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import xyz.scarabya.eazypipe.EazyPipe;
import xyz.scarabya.eazypipe.PipeConfig;
import xyz.scarabya.eazypipe.StageConfig;
import xyz.scarabya.eazypipe.adaptive.AdaptiveConfig;

/**
 * JMH throughput benchmark comparing EazyPipe static/adaptive pipelines against manual
 * {@link BlockingQueue} executors, {@link java.util.stream.Stream#parallel()} (CPU only),
 * and Project Reactor over 10,000 items across two transformation stages.
 *
 * <p>Note: {@code Gatherers.mapConcurrent} requires Java 24+ and is omitted on this Java 21 baseline.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 3)
@Fork(1)
@State(Scope.Benchmark)
public class PipelineThroughputBenchmark {

    static final int ITEM_COUNT = 10_000;
    private static final long CPU_TOKENS = 1_500L;
    private static final int IO_CONCURRENCY = 64;
    private static final int POISON = Integer.MIN_VALUE;

    @Param({"cpu", "io"})
    public String workload;

    private int cores;
    private boolean isIo;

    @Setup
    public void setup() {
        this.cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.isIo = "io".equals(workload);
    }

    @Benchmark
    public void eazypipeStatic(Blackhole bh) {
        StageConfig s1 = isIo
                ? StageConfig.ioBound("s1").concurrency(IO_CONCURRENCY, IO_CONCURRENCY, IO_CONCURRENCY).build()
                : StageConfig.cpuBound("s1").concurrency(cores, cores, cores).build();
        StageConfig s2 = isIo
                ? StageConfig.ioBound("s2").concurrency(IO_CONCURRENCY, IO_CONCURRENCY, IO_CONCURRENCY).build()
                : StageConfig.cpuBound("s2").concurrency(cores, cores, cores).build();

        EazyPipe.<Integer>from(emitter -> {
                    for (int i = 0; i < ITEM_COUNT; i++) {
                        emitter.emit(i);
                    }
                })
                .map(s1, this::processItem)
                .map(s2, this::processItem)
                .run(bh::consume);
    }

    @Benchmark
    public void eazypipeAdaptive(Blackhole bh) {
        StageConfig s1 = isIo
                ? StageConfig.ioBound("s1").concurrency(4, 16, IO_CONCURRENCY).build()
                : StageConfig.cpuBound("s1").concurrency(1, cores, cores * 2).build();
        StageConfig s2 = isIo
                ? StageConfig.ioBound("s2").concurrency(4, 16, IO_CONCURRENCY).build()
                : StageConfig.cpuBound("s2").concurrency(1, cores, cores * 2).build();

        AdaptiveConfig adaptive = AdaptiveConfig.balanced().toBuilder()
                .tickInterval(Duration.ofMillis(50))
                .build();

        EazyPipe.<Integer>from(emitter -> {
                    for (int i = 0; i < ITEM_COUNT; i++) {
                        emitter.emit(i);
                    }
                })
                .configure(PipeConfig.builder().cpuBudget(cores).adaptive(adaptive).build())
                .map(s1, this::processItem)
                .map(s2, this::processItem)
                .run(bh::consume);
    }

    @Benchmark
    public void blockingQueueManual(Blackhole bh) throws Exception {
        int workers = isIo ? IO_CONCURRENCY : cores;
        BlockingQueue<Integer> q01 = new ArrayBlockingQueue<>(256);
        BlockingQueue<Integer> q12 = new ArrayBlockingQueue<>(256);
        ExecutorService exec = isIo
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(cores);

        try {
            AtomicInteger stage1Remaining = new AtomicInteger(workers);
            AtomicInteger stage2Remaining = new AtomicInteger(workers);
            List<Future<?>> futures = new ArrayList<>(workers * 2 + 1);

            futures.add(exec.submit(() -> {
                try {
                    for (int i = 0; i < ITEM_COUNT; i++) {
                        q01.put(i);
                    }
                    for (int w = 0; w < workers; w++) {
                        q01.put(POISON);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));

            for (int w = 0; w < workers; w++) {
                futures.add(exec.submit(() -> {
                    try {
                        while (true) {
                            Integer v = q01.take();
                            if (v == POISON) {
                                if (stage1Remaining.decrementAndGet() == 0) {
                                    for (int k = 0; k < workers; k++) {
                                        q12.put(POISON);
                                    }
                                }
                                break;
                            }
                            q12.put(processItem(v));
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            for (int w = 0; w < workers; w++) {
                futures.add(exec.submit(() -> {
                    try {
                        while (true) {
                            Integer v = q12.take();
                            if (v == POISON) {
                                stage2Remaining.decrementAndGet();
                                break;
                            }
                            bh.consume(processItem(v));
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            exec.shutdownNow();
        }
    }

    @Benchmark
    public void reactor(Blackhole bh) {
        Scheduler scheduler = isIo ? Schedulers.boundedElastic() : Schedulers.parallel();
        int concurrency = isIo ? IO_CONCURRENCY : cores;

        Flux.range(0, ITEM_COUNT)
                .flatMap(i -> Mono.fromCallable(() -> processItem(i)).subscribeOn(scheduler), concurrency)
                .flatMap(i -> Mono.fromCallable(() -> processItem(i)).subscribeOn(scheduler), concurrency)
                .doOnNext(bh::consume)
                .blockLast();
    }

    private int processItem(int value) {
        if (isIo) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
            return value + 1;
        }
        Blackhole.consumeCPU(CPU_TOKENS);
        return value + 1;
    }

    /**
     * Separate CPU-only benchmark for {@link IntStream#parallel()} (parallel streams are unsuitable for blocking I/O).
     */
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Warmup(iterations = 2, time = 2)
    @Measurement(iterations = 3, time = 3)
    @Fork(1)
    public static class StreamParallelCpuBenchmark {

        @Benchmark
        public void streamParallelCpu(Blackhole bh) {
            IntStream.range(0, ITEM_COUNT)
                    .parallel()
                    .map(v -> {
                        Blackhole.consumeCPU(CPU_TOKENS);
                        return v + 1;
                    })
                    .map(v -> {
                        Blackhole.consumeCPU(CPU_TOKENS);
                        return v + 1;
                    })
                    .forEach(bh::consume);
        }
    }
}
