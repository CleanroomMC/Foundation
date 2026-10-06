package net.minecraft.launchwrapper;

import top.outlands.foundation.boot.ActualClassLoader;
import top.outlands.foundation.boot.ManifestCPParser;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;

public class LaunchClassLoader extends ActualClassLoader {
    private Set<String> classLoaderExceptions = new HashSet<String>();
    private Set<String> transformerExceptions = new HashSet<String>();
    /**
     * FoamFix (and many other mods) are still using these even some of them have long gone from upstream
     */
    private Map<Package, Manifest> packageManifests = null;
    private static Manifest EMPTY = new Manifest();
    private final Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    private final Set<String> invalidClasses = new HashSet<>(0);

    private final Map<String, byte[]> resourceCache = new ConcurrentHashMap<>(0);
    private final Set<String> negativeResourceCache = ConcurrentHashMap.newKeySet();

    public LaunchClassLoader(URL[] sources) {
        super(ManifestCPParser.parse(List.of(sources)).toArray(new URL[0]), LaunchClassLoader.class.getClassLoader());
        Launch.classLoader = this;
    }

    public LaunchClassLoader(ClassLoader loader) {
        super(getUCP(), loader);
        Launch.classLoader = this;
    }

    private static URL[] getUCP() {
        String[] classpaths = System.getProperty("java.class.path").split(File.pathSeparator);
        List<URL> urls = new ArrayList<>();
        try {
            for (String classpath : classpaths) {
                urls.add(new File(classpath).toURI().toURL());
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        return ManifestCPParser.parse(urls).toArray(new URL[0]);
    }

    /**
     * CCL is calling this
     */
    public byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        return super.runTransformers(name, transformedName, basicClass);
    }

}
