package com.rpc.core.extension.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要扩展依赖注入的字段。
 * 如果提供了 value（值），则注入指定名称的 SPI（可插拔扩展点）实现；
 * 否则注入该 SPI（可插拔扩展点）的默认实现。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Inject {
    String value() default "";

    boolean required() default true;
}
