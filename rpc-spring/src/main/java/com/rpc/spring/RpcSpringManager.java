package com.rpc.spring;

import com.rpc.core.api.annotation.RpcReference;
import com.rpc.core.api.annotation.RpcService;
import com.rpc.core.api.bootstrap.RpcConsumerBootstrap;
import com.rpc.core.api.bootstrap.RpcProviderBootstrap;
import com.rpc.core.config.RpcConfigLoader;
import com.rpc.core.config.RpcFrameworkConfig;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

public class RpcSpringManager implements BeanPostProcessor, SmartLifecycle, DisposableBean, ApplicationContextAware, PriorityOrdered {
    private ApplicationContext applicationContext;
    private RpcProviderBootstrap providerBootstrap;
    private RpcConsumerBootstrap consumerBootstrap;
    private boolean internalProviderBootstrap;
    private boolean internalConsumerBootstrap;
    private volatile boolean running;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        // 在 Bean 初始化前注入 @RpcReference，保证业务 Bean 后续拿到的是代理对象。
        ReflectionUtils.doWithFields(bean.getClass(), field -> injectReference(bean, field), this::isRpcReferenceField);
        return bean;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        String[] serviceBeanNames = applicationContext.getBeanNamesForAnnotation(RpcService.class);
        if (serviceBeanNames.length > 0) {
            // Spring 容器已经负责创建 @RpcService Bean，这里只把它们发布到 RPC 框架里。
            RpcProviderBootstrap bootstrap = getProviderBootstrap();
            for (String beanName : serviceBeanNames) {
                Object bean = applicationContext.getBean(beanName);
                RpcService rpcService = bean.getClass().getAnnotation(RpcService.class);
                bootstrap.registerService(resolveServiceInterface(bean.getClass(), rpcService), bean);
            }
            try {
                bootstrap.start();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to start rpc provider bootstrap", e);
            }
        }
        running = true;
    }

    @Override
    public void stop() {
        destroy();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void destroy() {
        if (internalProviderBootstrap && providerBootstrap != null) {
            providerBootstrap.close();
            providerBootstrap = null;
            internalProviderBootstrap = false;
        }
        if (internalConsumerBootstrap && consumerBootstrap != null) {
            consumerBootstrap.close();
            consumerBootstrap = null;
            internalConsumerBootstrap = false;
        }
        running = false;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    private boolean isRpcReferenceField(Field field) {
        return field.isAnnotationPresent(RpcReference.class);
    }

    private void injectReference(Object bean, Field field) {
        RpcReference rpcReference = field.getAnnotation(RpcReference.class);
        Class<?> serviceType = rpcReference.value() == Void.class ? field.getType() : rpcReference.value();
        Object proxy = getConsumerBootstrap().getService(serviceType);
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, bean, proxy);
    }

    private RpcConsumerBootstrap getConsumerBootstrap() {
        if (consumerBootstrap != null) {
            return consumerBootstrap;
        }
        RpcConsumerBootstrap existing = applicationContext.getBeanProvider(RpcConsumerBootstrap.class).getIfAvailable();
        if (existing != null) {
            consumerBootstrap = existing;
            return consumerBootstrap;
        }
        // 优先复用容器里已有的 bootstrap/config，只有缺失时才内部创建，
        // 避免 Spring 集成绕过外部显式配置。
        RpcFrameworkConfig frameworkConfig = applicationContext.getBeanProvider(RpcFrameworkConfig.class).getIfAvailable();
        consumerBootstrap = frameworkConfig == null
                ? RpcConsumerBootstrap.fromConfig()
                : RpcConsumerBootstrap.fromConfig(frameworkConfig);
        internalConsumerBootstrap = true;
        return consumerBootstrap;
    }

    private RpcProviderBootstrap getProviderBootstrap() {
        if (providerBootstrap != null) {
            return providerBootstrap;
        }
        RpcProviderBootstrap existing = applicationContext.getBeanProvider(RpcProviderBootstrap.class).getIfAvailable();
        if (existing != null) {
            providerBootstrap = existing;
            return providerBootstrap;
        }
        RpcFrameworkConfig frameworkConfig = applicationContext.getBeanProvider(RpcFrameworkConfig.class)
                .getIfAvailable(RpcConfigLoader::load);
        // Spring 场景下服务扫描和 Bean 创建已由容器接管，这里关闭 core 层重复扫描，
        // 避免同一服务被注册两次。
        frameworkConfig.setServerAutoRegisterAnnotatedServices(false);
        providerBootstrap = RpcProviderBootstrap.fromConfig(frameworkConfig);
        internalProviderBootstrap = true;
        return providerBootstrap;
    }

    private Class<?> resolveServiceInterface(Class<?> beanClass, RpcService rpcService) {
        if (rpcService.value() != Void.class) {
            return rpcService.value();
        }
        Class<?>[] interfaces = beanClass.getInterfaces();
        if (interfaces.length == 1) {
            return interfaces[0];
        }
        throw new IllegalStateException("RpcService bean must declare interface explicitly: " + beanClass.getName());
    }
}

