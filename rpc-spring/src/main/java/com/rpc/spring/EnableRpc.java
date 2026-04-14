package com.rpc.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 手动 Spring 接入模式的 RPC 开关注解。
 *
 * 所处阶段：Spring 解析配置类或启动类注解时生效。
 * 主要职责：通过 @Import 引入 RpcSpringRegistrar，让普通 Spring 项目不依赖 Boot 自动装配也能完成
 * @RpcService 扫描、RpcSpringManager 注册和 @RpcReference 注入。
 *
 * 注意事项：Spring Boot starter 场景通常不需要显式添加该注解，避免和自动装配扫描逻辑重复。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(RpcSpringRegistrar.class)
public @interface EnableRpc {
    /**
     * 指定需要扫描 @RpcService 的基础包。
     *
     * 边界处理：不配置时由 RpcSpringRegistrar 回退到当前启用类所在包，降低接入配置量。
     *
     * @return 需要扫描的包名数组
     */
    String[] scanPackages() default {};
}
