package top.outlands.foundation.boot;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Scan and add first level manifest classpath to url
 */
public final class ManifestCPParser {

    private ManifestCPParser() {
    }


    /**
     * Gets all manifest cp from a list of URL and add them to the end of same list
     */
    public static List<URL> parse(List<URL> roots) {
        final List<URL> manifestUrls = new ArrayList<>();
        for (URL root : roots) {
            manifestUrls.addAll(manifestClassPath(root));
        }
        roots.addAll(manifestUrls);
        return roots;
    }

    /**
     * Get cp from single jar URL
     */
    private static List<URL> manifestClassPath(URL jar) {
        final File file = file(jar);
        if (file == null || !file.isFile()) {
            return Collections.emptyList(); // a directory or a non-local URL has no manifest to read
        }
        String classPath = null;
        try (JarFile jarFile = new JarFile(file, false)) {
            final Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            }
        } catch (IOException e) {
            return Collections.emptyList(); // not a readable JAR: nothing to follow
        }
        if (classPath == null) {
            return Collections.emptyList();
        }
        final List<URL> dependencies = new ArrayList<>();
        for (String entry : classPath.trim().split("\\s+")) {
            try {
                URL resolved = new URL(jar, entry);
                dependencies.add(resolved);
            } catch (MalformedURLException _) {}
        }
        return dependencies;
    }

    private static File file(URL url) {
        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            return null;
        }
        try {
            return new File(url.toURI());
        } catch (RuntimeException | java.net.URISyntaxException e) {
            return null;
        }
    }
}
