package com.rpc.spi;

import java.lang.annotation.*;

/**
 * 依赖注入注解
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Inject {
    /**
     * 注入的扩展名称，为空则使用默认
     */
    String value() default "";
}
