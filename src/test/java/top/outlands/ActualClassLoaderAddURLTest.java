package top.outlands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.outlands.foundation.boot.ActualClassLoader;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the LuckPerms/Sponge-LTS crash: plugins inject their own libraries into the
 * plugin class loader (reflectively calling {@code addURL}) from several scheduler threads at once,
 * while other code may be walking the source list.
 */
class ActualClassLoaderAddURLTest {

    private static final int WRITER_THREADS = 8;
    private static final int URLS = 128;

    @Test
    void addURLToleratesConcurrentInjection(@TempDir Path dir) throws Exception {
        List<URL> jars = new ArrayList<>(URLS);
        for (int i = 0; i < URLS; i++) {
            Path jar = dir.resolve("dependency-" + i + ".jar");
            Files.createFile(jar);
            jars.add(jar.toUri().toURL());
        }

        ActualClassLoader loader = new ActualClassLoader(new URL[0], ActualClassLoader.class.getClassLoader());
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CyclicBarrier writersReady = new CyclicBarrier(WRITER_THREADS);
        AtomicBoolean writersDone = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(WRITER_THREADS + 1);
        try {
            // Some mods walk getSources() while others append to it.
            Future<?> reader = pool.submit(() -> {
                try {
                    start.await();
                    while (!writersDone.get()) {
                        for (URL url : loader.getSources()) {
                            if (url == null) {
                                failures.add(new AssertionError("null url in sources"));
                                return;
                            }
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });

            List<Future<?>> writers = new ArrayList<>(WRITER_THREADS);
            for (int t = 0; t < WRITER_THREADS; t++) {
                writers.add(pool.submit(() -> {
                    try {
                        start.await();
                        writersReady.await();
                        for (URL url : jars) {
                            loader.addURL(url);
                        }
                    } catch (Throwable t2) {
                        failures.add(t2);
                    }
                }));
            }

            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(60, TimeUnit.SECONDS);
            }
            writersDone.set(true);
            reader.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(failures.isEmpty(), () -> "concurrent addURL failed: " + failures);
        assertEquals(URLS, loader.getSources().size(), "each URL must be registered exactly once");
        Set<String> distinct = loader.getSources().stream().map(URL::toExternalForm).collect(Collectors.toSet());
        assertEquals(URLS, distinct.size(), "sources must not contain duplicates");
        assertEquals(URLS, loader.getURLs().length, "every URL must also reach URLClassLoader#addURL");

        // re-adding a known URL, or adding null, must be a no-op
        for (URL url : jars) {
            loader.addURL(url);
        }
        loader.addURL(null);
        assertEquals(URLS, loader.getSources().size());
        assertEquals(URLS, loader.getURLs().length);
    }
}
