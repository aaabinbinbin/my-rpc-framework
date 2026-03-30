package com.rpc.core.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcReference {
    /**
     * 可选的显式服务接口。
     * 如果省略，则默认把字段类型本身当作目标服务契约。
     */
    Class<?> value() default Void.class;
}

