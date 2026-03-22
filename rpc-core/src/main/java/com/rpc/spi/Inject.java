package com.rpc.spi;

import java.lang.annotation.*;

/**
 * 依赖注入注解
 * 
 * 用于标注需要注入的扩展实例字段
 * 
 * 使用示例：
 * <pre>
 * public class MyExtension implements SomeInterface {
 *     &#64;Inject
 *     private Serializer serializer;  // 注入默认序列化器
 *     
 *     &#64;Inject("json")
 *     private Serializer jsonSerializer;  // 注入指定名称的序列化器
 *     
 *     &#64;Inject(value = "kryo", required = false)
 *     private Serializer optionalSerializer;  // 可选注入，失败不报错
 * }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Inject {
    
    /**
     * 注入的扩展名称
     * 为空则使用默认扩展
     */
    String value() default "";
    
    /**
     * 是否必须注入
     * 如果为 true，注入失败会抛出异常
     * 如果为 false，注入失败只记录警告日志
     */
    boolean required() default true;
}
