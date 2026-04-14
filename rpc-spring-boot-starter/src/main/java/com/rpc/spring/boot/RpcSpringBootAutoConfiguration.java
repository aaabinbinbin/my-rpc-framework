package com.rpc.spring.boot;

import com.rpc.core.api.annotation.RpcService;
import com.rpc.core.config.framework.RpcFrameworkConfig;
import com.rpc.spring.RpcSpringManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.beans.Introspector;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * RPC Spring Boot 自动装配入口。
 *
 * 所处阶段：Spring Boot 应用启动时，根据 classpath 和 rpc.spring.enabled 条件自动创建 RPC 相关 Bean。
 * 主要职责：
 * - 将 application.yml 绑定为 RpcFrameworkConfig。
 * - 创建 RpcSpringManager，把 rpc-core 接入 Spring 生命周期。
 * - 注册可观测 facade/endpoint。
 * - 提前扫描 @RpcService，确保服务实现类能作为 Spring Bean 被容器管理。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RpcSpringManager.class)
@ConditionalOnProperty(prefix = "rpc.spring", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({RpcSpringBootProperties.class, RpcBootFrameworkProperties.class})
public class RpcSpringBootAutoConfiguration {
    /** 将 starter 配置对象转换成 core 层统一配置对象。 */
    @Bean
    @ConditionalOnMissingBean
    public RpcFrameworkConfig rpcFrameworkConfig(RpcBootFrameworkProperties properties) {
        return properties.toFrameworkConfig();
    }

    /**
     * 创建 Spring 生命周期总控 Bean。
     *
     * 主要职责：接管 @RpcReference 注入和 @RpcService 发布；如果用户自定义了同类型 Bean，则自动装配不再覆盖。
     *
     * @return RPC Spring 管理器
     */
    @Bean
    @ConditionalOnMissingBean
    public RpcSpringManager rpcSpringManager() {
        return new RpcSpringManager();
    }

    /**
     * 创建可观测门面 Bean。
     *
     * @return RPC 可观测门面
     */
    @Bean
    @ConditionalOnMissingBean
    public RpcObservabilityFacade rpcObservabilityFacade() {
        return new RpcObservabilityFacade();
    }

    /**
     * 创建 HTTP 可观测端点。
     *
     * 边界处理：只有 classpath 存在 Spring MVC 的 RestController 时才注册，避免非 Web 应用或非 MVC 应用启动失败。
     *
     * @param facade 可观测门面
     * @return RPC 可观测 HTTP 端点
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public RpcObservabilityEndpoint rpcObservabilityEndpoint(RpcObservabilityFacade facade) {
        return new RpcObservabilityEndpoint(facade);
    }

    /**
     * 创建浏览器可视化 Dashboard。
     *
     * @return RPC 可观测 Dashboard 端点
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public RpcObservabilityDashboardEndpoint rpcObservabilityDashboardEndpoint() {
        return new RpcObservabilityDashboardEndpoint();
    }

    /**
     * 注册 @RpcService BeanDefinition 扫描器。
     *
     * 使用 static Bean 是为了让它在普通 Bean 实例化前尽早参与 BeanDefinition 阶段，
     * 避免服务实现类没有被 Spring 管理，导致 provider 发布时找不到服务 Bean。
     */
    @Bean
    public static BeanDefinitionRegistryPostProcessor rpcServiceBeanDefinitionRegistryPostProcessor(Environment environment) {
        return new RpcServiceBeanDefinitionRegistryPostProcessor(environment);
    }

    /**
     * @RpcService BeanDefinition 注册器。
     *
     * 所处阶段：BeanDefinition 注册阶段，还没有真正实例化业务 Bean。
     * 它只负责把带 @RpcService 的类注册进 Spring 容器，后续真正发布 RPC 服务由 RpcSpringManager 完成。
     */
    static class RpcServiceBeanDefinitionRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {
        /** Spring 环境对象，用于读取 rpc.spring.scan-packages 等配置。 */
        private final Environment environment;

        /**
         * 创建 @RpcService BeanDefinition 注册器。
         *
         * @param environment Spring 环境对象
         */
        RpcServiceBeanDefinitionRegistryPostProcessor(Environment environment) {
            this.environment = environment;
        }

        /** 扫描配置包或 Spring Boot 主包下的 @RpcService 类，并注册为普通 Spring Bean。 */
        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            for (String basePackage : resolveScanPackages(registry)) {
                ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
                scanner.addIncludeFilter(new AnnotationTypeFilter(RpcService.class));
                for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
                    String className = candidate.getBeanClassName();
                    if (className == null) {
                        continue;
                    }
                    String beanName = Introspector.decapitalize(ClassUtils.getShortName(className));
                    if (!registry.containsBeanDefinition(beanName)) {
                        RootBeanDefinition beanDefinition = new RootBeanDefinition();
                        beanDefinition.setBeanClassName(className);
                        registry.registerBeanDefinition(beanName, beanDefinition);
                    }
                }
            }
        }

        /**
         * BeanFactory 后处理回调。
         *
         * 当前类只需要参与 BeanDefinition 注册，不需要修改已经构建好的 BeanFactory，因此这里保持空实现。
         *
         * @param beanFactory Spring BeanFactory
         */
        @Override
        public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        }

        /**
         * 解析扫描包优先级：
         * 1. rpc.spring.scan-packages 显式配置。
         * 2. rpc.server.scan-packages 兼容旧配置。
         * 3. Spring Boot 主应用包推断，降低 example 接入配置量。
         */
        private Set<String> resolveScanPackages(BeanDefinitionRegistry registry) {
            Set<String> packages = new LinkedHashSet<>();
            addConfiguredPackages(packages, environment.getProperty("rpc.spring.scan-packages", ""));
            addConfiguredPackages(packages, environment.getProperty("rpc.server.scan-packages", ""));
            if (packages.isEmpty() && registry instanceof BeanFactory beanFactory
                    && AutoConfigurationPackages.has(beanFactory)) {
                packages.addAll(AutoConfigurationPackages.get(beanFactory));
            }
            return packages;
        }

        /** 解析逗号分隔的包名配置，忽略空白项。 */
        private void addConfiguredPackages(Set<String> packages, String configuredPackages) {
            for (String basePackage : Arrays.asList(configuredPackages.split(","))) {
                if (StringUtils.hasText(basePackage)) {
                    packages.add(basePackage.trim());
                }
            }
        }
    }
}
