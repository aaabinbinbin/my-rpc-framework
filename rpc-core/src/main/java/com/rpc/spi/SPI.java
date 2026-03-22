package com.rpc.spi;

import java.lang.annotation.*;

/**
 * SPI 扩展点注解
 * 标注在接口上，表示该接口是一个扩展点
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SPI {
    
    /**
     * 默认实现名称
     */
    String value() default "";
}