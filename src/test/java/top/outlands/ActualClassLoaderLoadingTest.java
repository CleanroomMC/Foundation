package top.outlands;

import org.junit.jupiter.api.Test;
import top.outlands.foundation.boot.ActualClassLoader;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the class loader itself: the prefix rules must keep routing classes the way they
 * used to before the trie was replaced by {@link top.outlands.foundation.boot.PrefixMatcher}.
 */
class ActualClassLoaderLoadingTest {

    @Test
    void inclusionsGoThroughFindClassAndExclusionsAreDelegated() throws Exception {
        URL classes = Path.of("build", "classes", "java", "main").toUri().toURL();
        try (ActualClassLoader loader = new ActualClassLoader(new URL[]{classes}, ActualClassLoader.class.getClassLoader())) {
            // "top.outlands.foundation." is an inclusion -> loaded by this class loader
            Class<?> included = loader.loadClass("top.outlands.foundation.TransformerDelegate");
            assertSame(loader, included.getClassLoader(), "included packages must go through findClass");

            // "top.outlands.foundation.boot." is an exclusion -> delegated to the parent
            Class<?> excluded = loader.loadClass("top.outlands.foundation.boot.PrefixMatcher");
            assertNotSame(loader, excluded.getClassLoader(), "excluded packages must be delegated");
            assertTrue(ActualClassLoader.classLoaderExceptions.matches("top.outlands.foundation.boot.PrefixMatcher"));
        }
    }
}
