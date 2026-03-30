package com.rpc.spring.boot;

import com.rpc.core.api.annotation.RpcService;
import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.spring.RpcSpringManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.beans.Introspector;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RpcSpringManager.class)
@ConditionalOnProperty(prefix = "rpc.spring", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({RpcSpringBootProperties.class, RpcBootFrameworkProperties.class})
public class RpcSpringBootAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RpcFrameworkConfig rpcFrameworkConfig(RpcBootFrameworkProperties properties) {
        return properties.toFrameworkConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public RpcSpringManager rpcSpringManager() {
        return new RpcSpringManager();
    }

    @Bean
    public static BeanDefinitionRegistryPostProcessor rpcServiceBeanDefinitionRegistryPostProcessor(Environment environment) {
        // Boot 场景不要求显式写 @EnableRpc，因此这里通过自动配置补上
        // @RpcService 的扫描注册能力。
        return new RpcServiceBeanDefinitionRegistryPostProcessor(environment);
    }

    static class RpcServiceBeanDefinitionRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {
        private final Environment environment;

        RpcServiceBeanDefinitionRegistryPostProcessor(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            for (String basePackage : resolveScanPackages()) {
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

        @Override
        public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        }

        private Set<String> resolveScanPackages() {
            Set<String> packages = new LinkedHashSet<>();
            String configuredPackages = environment.getProperty("rpc.spring.scan-packages", "");
            // starter 只按配置扫描，避免无边界全项目扫描带来启动时副作用。
            for (String basePackage : Arrays.asList(configuredPackages.split(","))) {
                if (StringUtils.hasText(basePackage)) {
                    packages.add(basePackage.trim());
                }
            }
            return packages;
        }
    }
}

