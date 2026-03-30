package com.rpc.core.api.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class ClassPathScanner {
    private ClassPathScanner() {
    }

    public static List<Class<?>> scan(String basePackage) {
        try {
            String path = basePackage.replace('.', '/');
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(path);
            List<Class<?>> classes = new ArrayList<>();
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if (!"file".equals(resource.getProtocol())) {
                    continue;
                }
                File directory = new File(URLDecoder.decode(resource.getFile(), StandardCharsets.UTF_8));
                classes.addAll(scanDirectory(basePackage, directory));
            }
            return classes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan package: " + basePackage, e);
        }
    }

    private static List<Class<?>> scanDirectory(String packageName, File directory) {
        List<Class<?>> classes = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files == null) {
            return classes;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                classes.addAll(scanDirectory(packageName + "." + file.getName(), file));
                continue;
            }
            if (!file.getName().endsWith(".class") || file.getName().contains("$")) {
                continue;
            }
            String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
            try {
                classes.add(Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Failed to load class: " + className, e);
            }
        }
        return classes;
    }
}

