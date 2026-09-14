package top.outlands.foundation.boot;

import net.minecraft.launchwrapper.LaunchClassLoader;

import java.net.URL;

public class LoadsAllClassLoader extends LaunchClassLoader {
    public LoadsAllClassLoader(URL[] sources) {
        super(sources);
    }

    public LoadsAllClassLoader(ClassLoader loader) {
        super(loader);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return super.loadClass(name);
        }
    }
}
