package com.rpc;

/**
 * 测试接口
 */
public interface HelloService {
    /**
     * 说你好
     * @param name 名字
     * @return 问候语
     */
    String sayHello(String name);

    /**
     * 打招呼
     */
    String sayHi(String name);

    /**
     * 加法计算
     */
    Integer add(Integer a, Integer b);
}
