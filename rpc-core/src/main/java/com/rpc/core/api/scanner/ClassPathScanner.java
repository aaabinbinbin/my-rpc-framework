package com.rpc.core.api.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * classpath 包扫描工具。
 *
 * 所处阶段：provider 启动时扫描 @RpcService 注解服务类。
 * 主要职责：把指定 basePackage 下的 class 文件加载为 Class 对象，供 Bootstrap 后续识别和注册服务。
 *
 * 注意事项：当前只支持 file 协议资源，适合本地开发和普通 classpath 目录；如果打成复杂 fat jar，需扩展 jar 协议扫描。
 */
public final class ClassPathScanner {
    /** 工具类不允许实例化。 */
    private ClassPathScanner() {
    }

    /**
     * 扫描指定包下的所有顶层类。
     *
     * 边界处理：非 file 协议资源会跳过；扫描 IO 异常会转为 IllegalStateException，让启动阶段快速失败。
     */
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

    /**
     * 递归扫描目录下的 class 文件。
     *
     * 注意事项：跳过包含 $ 的内部类/匿名类，避免把编译器生成类误识别为可注册服务。
     */
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

