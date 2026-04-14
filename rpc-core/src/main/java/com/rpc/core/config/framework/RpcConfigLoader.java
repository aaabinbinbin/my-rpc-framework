package com.rpc.core.config.framework;

import com.rpc.core.config.source.RpcPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * RPC 配置加载入口。
 *
 * 所处阶段：Bootstrap、Spring 集成或测试启动时加载框架配置。
 * 主要职责：从 classpath 读取 rpc.properties，并委托 RpcFrameworkConfigBinder 做分领域绑定。
 *
 * 边界处理：配置文件不存在时使用 RpcFrameworkConfig 默认值；配置文件存在但读取失败时直接启动失败。
 */
public final class RpcConfigLoader {
    /** 复用无状态配置绑定器，避免每次加载都重复创建绑定器树。 */
    private static final RpcFrameworkConfigBinder FRAMEWORK_CONFIG_BINDER = new RpcFrameworkConfigBinder();

    /** 工具类不允许实例化。 */
    private RpcConfigLoader() {
    }

    /**
     * 加载 RPC 框架配置。
     *
     * 注意事项：系统属性覆盖逻辑不在这里处理，而是在 RpcPropertySource#get 中统一处理。
     */
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
