package io.pskenny.pkspkms.io.feed;

import io.pskenny.pkspkms.io.fs.SynthesizedFileSystem.SynthFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Parallel fetch machinery shared by the synthesized vaults: runs fetch tasks
 * through a small pool so a big mount takes roughly the slowest task's time.
 * Failures are per task: they log a warning naming the task and are skipped.
 */
final class Mounts {

    private static final Logger logger = LoggerFactory.getLogger(Mounts.class);
    private static final int POOL = 4;

    private Mounts() {}

    @FunctionalInterface
    interface TaskFn<T> {
        Map<String, SynthFile> run(T task) throws IOException;
    }

    // Results come back in task order; failed tasks are absent. describe(label)
    // is used for the skip warning.
    static <T> List<Map<String, SynthFile>> fetch(List<T> tasks, Function<T, String> describe, TaskFn<T> fetch) throws IOException {
        List<Map<String, SynthFile>> results = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            results.add(null);
        }
        if (tasks.isEmpty()) {
            return results;
        }

        ExecutorService pool = Executors.newFixedThreadPool(POOL);
        try {
            List<java.util.concurrent.Future<Map<String, SynthFile>>> futures = new ArrayList<>();
            for (T task : tasks) {
                futures.add(pool.submit(() -> fetch.run(task)));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.set(i, futures.get(i).get());
                } catch (ExecutionException e) {
                    logger.warn("Skipping feed {}: {}", describe.apply(tasks.get(i)), e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while fetching feeds", e);
                }
            }
        } finally {
            pool.shutdown();
        }
        return results;
    }
}
