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
    /**
     * 在 BeanDefinition 注册阶段向容器补充 RPC 基础设施和服务实现类。
     *
     * 处理流程：
     * 1. 先确保 RpcSpringManager 已经注册，后续才能完成引用注入和服务发布。
     * 2. 再解析 @EnableRpc 的 scanPackages，扫描并注册 @RpcService 实现类。
     *
     * 边界处理：重复注册时跳过已有 BeanDefinition，避免覆盖用户显式声明的 Bean。
     *
     * @param importingClassMetadata 标注 @EnableRpc 的导入类元数据
     * @param registry Spring BeanDefinition 注册表
     */
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

    /**
     * 扫描指定包下的 @RpcService 类并注册为 Spring BeanDefinition。
     *
     * 所处阶段：仍然是容器启动早期的定义注册阶段，不会实例化服务对象。
     * 注意事项：这里只注册服务 Bean，真正的 RPC 端口监听和注册中心注册由 RpcSpringManager.start() 完成。
     *
     * @param basePackage 需要扫描的基础包
     * @param registry Spring BeanDefinition 注册表
     */
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

    /**
     * 解析 @EnableRpc 指定的扫描包。
     *
     * 边界处理：如果用户没有显式配置扫描包，则回退到启用类所在包，减少使用 RPC 框架时的样板配置。
     *
     * @param metadata 标注 @EnableRpc 的类元数据
     * @return 去重且保持声明顺序的扫描包集合
     */
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
