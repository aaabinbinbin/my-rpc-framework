package com.rpc.core.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcService {
    /**
     * 可选的显式服务接口。
     * 如果省略，则由启动代码尝试推断实现类唯一实现的接口。
     */
    Class<?> value() default Void.class;
}

