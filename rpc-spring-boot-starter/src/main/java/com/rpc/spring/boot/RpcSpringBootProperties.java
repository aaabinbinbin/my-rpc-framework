package com.rpc.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * RPC Spring 集成层配置。
 *
 * 所处阶段：Spring Boot 自动装配启动早期绑定 rpc.spring.* 配置。
 * 主要职责：控制 Spring 集成开关和 @RpcService 扫描包，和 rpc.* 主配置对象分离，便于用户只理解接入层参数。
 */
@ConfigurationProperties(prefix = "rpc.spring")
public class RpcSpringBootProperties {
    /** 是否启用 RPC Spring 自动装配，默认启用以降低接入成本。 */
    private boolean enabled = true;
    /** @RpcService 扫描包列表；为空时自动回退到 Spring Boot 主应用包。 */
    private List<String> scanPackages = new ArrayList<>();

    /**
     * 判断 RPC Spring 自动装配是否启用。
     *
     * @return 启用返回 true
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置 RPC Spring 自动装配开关。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取 @RpcService 扫描包列表。
     *
     * @return 扫描包列表
     */
    public List<String> getScanPackages() {
        return scanPackages;
    }

    /**
     * 设置 @RpcService 扫描包列表。
     *
     * @param scanPackages 扫描包列表
     */
    public void setScanPackages(List<String> scanPackages) {
        this.scanPackages = scanPackages;
    }
}
