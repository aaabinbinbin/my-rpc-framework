package com.rpc.spring;

import com.rpc.core.api.annotation.RpcService;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.beans.Introspector;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * ImportBeanDefinitionRegistrar 在 Spring 容器还没真正开始创建 Bean 之前，先往容器里“塞”一些 BeanDefinition。
 * 当前类的作用：
 * 1.如果容器里还没有 RpcSpringManager，先注册一个。
 * 2.扫描 @EnableRpc(scanPackages=...) 指定的包，
 *   把带 @RpcService 的类注册成 Spring BeanDefinition。
 * RpcSpringRegistrar 不是实例化 Bean，它只是告诉 Spring：“这里有个类，将来你要把它当 Bean 创建出来。
 */
public class RpcSpringRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        if (!registry.containsBeanDefinition(RpcSpringManager.class.getName())) {
            registry.registerBeanDefinition(RpcSpringManager.class.getName(), new RootBeanDefinition(RpcSpringManager.class));
        }
        // Registrar 的职责只是把 @RpcService 标注的类注册成 Spring Bean，
        // 真正的 RPC 发布动作留给 RpcSpringManager 在容器启动阶段完成。
        for (String basePackage : resolveScanPackages(importingClassMetadata)) {
            registerAnnotatedServices(basePackage, registry);
        }
    }

    private void registerAnnotatedServices(String basePackage, BeanDefinitionRegistry registry) {
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

    private Set<String> resolveScanPackages(AnnotationMetadata metadata) {
        Map<String, Object> attributesMap = metadata.getAnnotationAttributes(EnableRpc.class.getName());
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(attributesMap);
        Set<String> packages = new LinkedHashSet<>();
        if (attributes != null) {
            for (String basePackage : attributes.getStringArray("scanPackages")) {
                if (StringUtils.hasText(basePackage)) {
                    packages.add(basePackage.trim());
                }
            }
        }
        if (packages.isEmpty()) {
            // 未显式指定扫描包时，默认回退到启动类所在包，符合 Spring 常见习惯。
            packages.add(ClassUtils.getPackageName(metadata.getClassName()));
        }
        return packages;
    }
}

