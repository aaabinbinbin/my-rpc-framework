package com.rpc.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class RpcConfigLoader {
    private static final RpcFrameworkConfigBinder FRAMEWORK_CONFIG_BINDER = new RpcFrameworkConfigBinder();

    private RpcConfigLoader() {
    }

    public static RpcFrameworkConfig load() {
        Properties properties = new Properties();
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(RpcConfigKeys.FILE_NAME)) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RpcConfigKeys.FILE_NAME, e);
        }

        return FRAMEWORK_CONFIG_BINDER.bind(new RpcPropertySource(properties));
    }
}
