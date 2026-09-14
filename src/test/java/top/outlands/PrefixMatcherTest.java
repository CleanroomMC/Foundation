package top.outlands;

import org.junit.jupiter.api.Test;
import top.outlands.foundation.boot.ActualClassLoader;
import top.outlands.foundation.boot.PrefixMatcher;
import top.outlands.foundation.trie.PrefixTrie;
import top.outlands.foundation.trie.TrieNode;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefixMatcherTest {

    @Test
    void matchingIsBarePrefixWithoutPackageBoundary() {
        PrefixMatcher matcher = new PrefixMatcher();
        matcher.add("net.minecraft");

        assertTrue(matcher.matches("net.minecraft"));
        assertTrue(matcher.matches("net.minecraft.client.Minecraft"));
        // bare prefix semantics: "net.minecraft" also matches "net.minecraftforge.*"
        assertTrue(matcher.matches("net.minecraftforge.fml.common.FMLCommonHandler"));
        // ...but it is still a prefix, not a substring/segment match
        assertFalse(matcher.matches("net.minecra"));
        assertFalse(matcher.matches("com.example.Foo"));
    }

    @Test
    void anyStoredPrefixMatchesAndOverlappingOnesCoexist() {
        PrefixMatcher matcher = new PrefixMatcher();
        matcher.add("com.google.");
        matcher.add("com.google.common.");

        assertTrue(matcher.matches("com.google.gson.Gson"));
        assertTrue(matcher.matches("com.google.common.collect.ImmutableList"));
        assertEquals(2, matcher.size(), "overlapping prefixes are both stored");
    }

    @Test
    void addingTheSamePrefixTwiceIsANoOp() {
        PrefixMatcher matcher = new PrefixMatcher();
        assertTrue(matcher.add("org.spongepowered.asm.mixin."));
        assertFalse(matcher.add("org.spongepowered.asm.mixin."), "the second add must be ignored");
        assertEquals(1, matcher.size());
    }

    @Test
    void removeDropsThePrefixEntirely() {
        PrefixMatcher matcher = new PrefixMatcher();
        matcher.add("some.mod.");
        matcher.add("other.mod.");

        assertTrue(matcher.remove("some.mod."));
        assertFalse(matcher.matches("some.mod.Class"), "a removed prefix must not match");
        assertEquals(List.of("other.mod."), matcher.prefixes(), "a removed prefix must not be listed");
        assertEquals(1, matcher.size());

        assertFalse(matcher.remove("some.mod."), "removing it again reports that it was not present");
        assertFalse(matcher.remove("never.added."));
        assertEquals(1, matcher.size());

        // and it can be registered again
        assertTrue(matcher.add("some.mod."));
        assertTrue(matcher.matches("some.mod.Class"));
        assertEquals(2, matcher.size());
    }

    /**
     * Behaviour that intentionally differs from the trie: the trie kept a {@code false} tombstone, so
     * removing {@code "com.foo."} also neutered the separately stored {@code "com.foo.bar."}. Removing
     * the entry for real leaves the longer prefix in effect.
     */
    @Test
    void removingAShortPrefixLetsLongerOnesApplyAgain() {
        PrefixMatcher matcher = new PrefixMatcher();
        matcher.add("com.foo.");
        matcher.add("com.foo.bar.");

        assertTrue(matcher.matches("com.foo.bar.Baz"));
        matcher.remove("com.foo.");
        assertTrue(matcher.matches("com.foo.bar.Baz"), "the longer prefix is still registered");
        assertFalse(matcher.matches("com.foo.Other.Class"), "names outside the longer prefix no longer match");
        matcher.remove("com.foo.bar.");
        assertFalse(matcher.matches("com.foo.bar.Baz"));
    }

    @Test
    void anyCharacterIsAllowedInAPrefix() {
        PrefixMatcher matcher = new PrefixMatcher();
        // the trie silently rejected prefixes containing unsupported characters
        for (String prefix : List.of("has space ", "a/b/", "foo;bar", "array[", "λ.")) {
            assertTrue(matcher.add(prefix));
        }
        assertEquals(5, matcher.size());
        assertTrue(matcher.matches("a/b/c/D"));
        assertTrue(matcher.matches("foo;bar.Baz"));
        assertTrue(matcher.matches("array[0].Class"));
    }

    @Test
    void nullPrefixIsRejected() {
        PrefixMatcher matcher = new PrefixMatcher();
        assertThrows(NullPointerException.class, () -> matcher.add(null));
        assertThrows(NullPointerException.class, () -> matcher.remove(null));
    }

    @Test
    void classLoaderStyleExclusionList() {
        PrefixMatcher matcher = new PrefixMatcher();
        for (String key : new String[]{
                "javassist", "com.google.", "org.spongepowered.", "org.apache.commons.",
                "org.apache.http.", "org.apache.maven.", "com.google.common.",
                "org.objectweb.asm.", "com.google.gson.", "net.minecraftforge.fml.repackage."}) {
            matcher.add(key);
        }

        assertTrue(matcher.matches("com.google.common.collect.RegularImmutableBiMap"));
        assertTrue(matcher.matches("org.apache.commons.io.IOUtils"));
        assertTrue(matcher.matches("com.google.gson.Gson"));
        assertFalse(matcher.matches("com.other.Thing"));
    }

    /**
     * Migration guard: for the prefix sets {@link ActualClassLoader} really uses, the matcher must
     * answer exactly like the trie it replaced. Can be deleted together with the trie package.
     */
    @Test
    void parityWithPrefixTrieOnTheRealConfiguration() {
        ActualClassLoader loader = new ActualClassLoader(
                new URL[0], ActualClassLoader.class.getClassLoader());

        List<String> prefixes = new ArrayList<>();
        prefixes.addAll(ActualClassLoader.classLoaderInclusions.prefixes());
        prefixes.addAll(ActualClassLoader.classLoaderExceptions.prefixes());
        prefixes.addAll(ActualClassLoader.transformerExceptions.prefixes());
        prefixes.add("some.random.mod.asm.");     // as if a mod registered one at runtime

        PrefixTrie<Boolean> trie = new PrefixTrie<>();
        PrefixMatcher matcher = new PrefixMatcher();
        for (String prefix : prefixes) {
            trie.put(prefix, Boolean.TRUE);
            matcher.add(prefix);
        }

        List<String> probes = new ArrayList<>(List.of(
                "net.minecraft.client.Minecraft", "net.minecraftforge.fml.common.FMLCommonHandler",
                "org.objectweb.asm.ClassReader", "com.google.common.collect.ImmutableList",
                "com.google.gson.Gson", "org.spongepowered.asm.mixin.Mixin",
                "com.mojang.blaze3d.platform.GlStateManager", "javax.annotation.Nullable",
                "net.minecraft.launchwrapper.Launch", "top.outlands.foundation.boot.ActualClassLoader",
                "top.outlands.foundation.boot.PrefixMatcher", "some.random.mod.asm.Transformer",
                "com.example.mod.asm.CorePlugin", "io.github.somemod.Thing",
                "org.lwjgl.system.MemoryUtil", "zone.rong.mixinbooter.MixinBooterPlugin"));
        for (String prefix : prefixes) {
            probes.add(prefix + "Sub.Class");
            probes.add(prefix);
        }

        for (String name : probes) {
            TrieNode<Boolean> node = trie.getFirstKeyValueNode(name);
            boolean expected = node != null && node.getValue();
            assertEquals(expected, matcher.matches(name), "lookup mismatch for " + name);
        }
        assertEquals(new HashSet<>(prefixes), new HashSet<>(matcher.prefixes()),
                "both structures must hold the same prefixes");
        assertEquals(new HashSet<>(prefixes), new HashSet<>(trie.getRoot().getKeyValueNodes().stream()
                .map(TrieNode::getKey).toList()));
    }

    @Test
    void concurrentAddRemoveAndMatchLosesNothing() throws Exception {
        int writers = 8;
        int prefixesPerWriter = 250;
        PrefixMatcher matcher = new PrefixMatcher();
        List<String> prefixes = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicBoolean stopReaders = new AtomicBoolean(false);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 2; r++) {
                readers.add(pool.submit(() -> {
                    try {
                        start.await();
                        while (!stopReaders.get()) {
                            matcher.matches("com.unknown.pkg.Class");
                            matcher.matches("net.minecraft.client.Minecraft");
                            for (String prefix : matcher.prefixes()) {
                                if (prefix == null || prefix.isEmpty()) {
                                    failures.add(new AssertionError("corrupted snapshot entry: " + prefix));
                                    return;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }));
            }

            start.countDown();

            // phase 1: everybody adds its own prefixes
            List<Future<?>> adders = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                final int writer = w;
                adders.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < prefixesPerWriter; i++) {
                            String prefix = "com.example.mod" + writer + ".pkg" + i + ".";
                            matcher.add(prefix);
                            prefixes.add(prefix);
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                }));
            }
            for (Future<?> adder : adders) {
                adder.get(60, TimeUnit.SECONDS);
            }
            assertEquals(writers * prefixesPerWriter, matcher.size(), "no prefix may be lost while adding");

            // phase 2: everybody removes its own prefixes again
            List<Future<?>> removers = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                final int writer = w;
                removers.add(pool.submit(() -> {
                    for (int i = 0; i < prefixesPerWriter; i++) {
                        if (!matcher.remove("com.example.mod" + writer + ".pkg" + i + ".")) {
                            failures.add(new AssertionError("prefix disappeared before removal"));
                        }
                    }
                }));
            }
            for (Future<?> remover : removers) {
                remover.get(60, TimeUnit.SECONDS);
            }
            assertEquals(0, matcher.size(), "every prefix must be gone after the removals");

            stopReaders.set(true);
            for (Future<?> reader : readers) {
                reader.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(failures.isEmpty(), () -> "concurrent add/remove failed: " + failures);
        for (String prefix : prefixes) {
            assertFalse(matcher.matches(prefix + "Class"), "removed prefix still matches: " + prefix);
        }
    }
}
