package datalevin.ni;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.jar.JarFile;
import java.util.Enumeration;

public class ResourceLister {

    public static Set<String> listResources(ClassLoader classLoader, String dir) throws IOException, URISyntaxException {
        Set<String> result = new HashSet<>();
        Enumeration<URL> roots = classLoader.getResources(dir);
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            result.addAll(listResources(root));
        }
        return result;
    }

    private static Set<String> listResources(URL root) throws IOException, URISyntaxException {
        Set<String> result = new HashSet<>();
        String protocol = root.getProtocol();
        
        if ("file".equals(protocol)) {
            Path rootPath = Paths.get(root.toURI());
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(Files::isRegularFile)
                    .map(p -> rootPath.relativize(p).toString().replace('\\', '/'))
                    .forEach(result::add);
            }
        } else if ("jar".equals(protocol)) {
            String jarPath = root.getPath().substring(5, root.getPath().indexOf("!"));
            try (JarFile jar = new JarFile(jarPath)) {
                Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith("/")) {  // not a directory
                        result.add(name);
                    }
                }
            }
        }
        
        return result;
    }
}
