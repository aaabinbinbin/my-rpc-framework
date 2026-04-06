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

/**
 * Spring 场景下的 RPC 集成总控类。
 * ----
 * 这个类本身不负责实现 RPC 协议、网络通信或服务执行，
 * 它的核心职责是把 rpc-core 里的能力接到 Spring 生命周期上。
 * ----
 * 主要做两件事：
 * 1. 在普通业务 Bean 初始化之前，把字段上的 @RpcReference 注入成代理对象。
 * 2. 在 Spring 容器启动完成之后，把容器里的 @RpcService Bean 发布为 RPC 服务。
 * ----
 * 因为它既要参与 Bean 创建前后的过程，又要跟随容器启动和销毁，
 * 所以同时实现了 BeanPostProcessor、SmartLifecycle、DisposableBean 等接口。
 * ----
 * BeanPostProcessor 允许你在 Spring Bean 初始化前后做增强处理。
 * SmartLifecycle 让一个 Bean 能参与 Spring 容器“启动/停止”的生命周期。比普通 InitializingBean 更适合做“组件级启动与关闭”。
 * DisposableBean 容器销毁时回调 destroy()。
 * ApplicationContextAware 让当前 Bean 拿到 Spring 容器 ApplicationContext。
 * PriorityOrdered 控制当前 BeanPostProcessor 的执行优先级。
 */
public class RpcSpringManager implements BeanPostProcessor, SmartLifecycle, DisposableBean, ApplicationContextAware, PriorityOrdered {
    /** Spring 容器入口，后续需要从容器中获取配置、bootstrap 和带注解的 Bean。 */
    private ApplicationContext applicationContext;

    /** provider 侧启动器，负责把本地服务注册到 RPC 服务端。 */
    private RpcProviderBootstrap providerBootstrap;

    /** consumer 侧启动器，负责创建代理对象并准备客户端运行环境。 */
    private RpcConsumerBootstrap consumerBootstrap;

    /** 标记 providerBootstrap 是否由当前类内部创建，以便销毁时决定是否关闭。 */
    private boolean internalProviderBootstrap;

    /** 标记 consumerBootstrap 是否由当前类内部创建，以便销毁时决定是否关闭。 */
    private boolean internalConsumerBootstrap;

    /** Spring 生命周期中的运行状态标记。 */
    private volatile boolean running;

    /**
     * 在 Bean 初始化前扫描字段上的 @RpcReference，并注入 RPC 代理对象。
     * 这是 consumer 侧最关键的 Spring 接入点。
     * 业务代码写的是接口字段，但运行时注入进去的是一个可发起远程调用的代理对象。
     */
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), field -> injectReference(bean, field), this::isRpcReferenceField);
        return bean;
    }

    /**
     * 在 Spring 容器启动完成后发布所有 @RpcService Bean。
     * 这一步不是重新创建服务对象，而是复用 Spring 已经创建好的 Bean，
     * 再交给 provider bootstrap 注册到本地注册表并启动 RPC 服务端。
     */
    @Override
    public void start() {
        if (running) {
            return;
        }
        String[] serviceBeanNames = applicationContext.getBeanNamesForAnnotation(RpcService.class);
        if (serviceBeanNames.length > 0) {
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

    /** 停止时复用 destroy 逻辑，统一释放内部创建的资源。 */
    @Override
    public void stop() {
        destroy();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 允许随 Spring 容器自动启动。 */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 放在较晚阶段启动，避免在容器尚未稳定时就启动 provider 服务端。
     * 这里返回较大的 phase，表示等大多数普通 Bean 准备完成再启动。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * 让 BeanPostProcessor 阶段尽量较早执行，
     * 以便业务 Bean 在初始化时尽快拿到 @RpcReference 对应的代理对象。
     */
    @Override
    public int getOrder() {
        return PriorityOrdered.HIGHEST_PRECEDENCE;
    }

    /**
     * 释放当前类内部创建的 bootstrap。
     * 如果 bootstrap 是外部显式注入到 Spring 容器中的，
     * 则当前类不负责关闭，避免误伤外部管理的资源。
     */
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

    /**
     * 让当前 Bean 拿到 Spring 容器 ApplicationContext。
     * 后面很多工作都依赖从容器里拿对象，比如：
     *  查找 @RpcService Bean
     *  查找 RpcFrameworkConfig
     *  查找现有的 RpcProviderBootstrap
     *  查找现有的 RpcConsumerBootstrap
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /** 判断当前字段是否是需要注入 RPC 代理的字段。 */
    private boolean isRpcReferenceField(Field field) {
        return field.isAnnotationPresent(RpcReference.class);
    }

    /**
     * 把 @RpcReference 字段替换成真实的 RPC 代理对象。
     * 如果注解上显式声明了服务接口，就按注解值取；
     * 否则默认以字段类型作为服务契约。
     */
    private void injectReference(Object bean, Field field) {
        RpcReference rpcReference = field.getAnnotation(RpcReference.class);
        Class<?> serviceType = rpcReference.value() == Void.class ? field.getType() : rpcReference.value();
        Object proxy = getConsumerBootstrap().getService(serviceType);
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, bean, proxy);
    }

    /**
     * 获取 consumer bootstrap。
     * 优先级：
     * 1. 当前类已经缓存的实例。
     * 2. Spring 容器中已有的 RpcConsumerBootstrap Bean。
     * 3. 容器里的 RpcFrameworkConfig。
     * 4. 最后回退到默认配置创建。
     */
    private RpcConsumerBootstrap getConsumerBootstrap() {
        if (consumerBootstrap != null) {
            return consumerBootstrap;
        }
        RpcConsumerBootstrap existing = applicationContext.getBeanProvider(RpcConsumerBootstrap.class).getIfAvailable();
        if (existing != null) {
            consumerBootstrap = existing;
            return consumerBootstrap;
        }
        RpcFrameworkConfig frameworkConfig = applicationContext.getBeanProvider(RpcFrameworkConfig.class).getIfAvailable();
        consumerBootstrap = frameworkConfig == null
                ? RpcConsumerBootstrap.fromConfig()
                : RpcConsumerBootstrap.fromConfig(frameworkConfig);
        internalConsumerBootstrap = true;
        return consumerBootstrap;
    }

    /**
     * 获取 provider bootstrap。
     * Spring 场景下服务扫描和 Bean 创建已经由容器接管，
     * 因此这里会关闭 core 层的自动注解扫描，避免同一服务被注册两次。
     */
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
        frameworkConfig.setServerAutoRegisterAnnotatedServices(false);
        providerBootstrap = RpcProviderBootstrap.fromConfig(frameworkConfig);
        internalProviderBootstrap = true;
        return providerBootstrap;
    }

    /**
     * 解析一个 @RpcService Bean 对应的服务接口。
     * 如果注解明确声明了接口，则直接使用；
     * 否则要求实现类只能实现一个接口，避免导出服务时出现歧义。
     */
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
