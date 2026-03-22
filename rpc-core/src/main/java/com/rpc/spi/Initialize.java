package com.rpc.spi;

import java.lang.annotation.*;

/**
 * 初始化方法注解
 * 
 * 用于标注扩展实例创建后需要执行的初始化方法
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Initialize {
}