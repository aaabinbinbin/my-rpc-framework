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

    /**
     * 返回指定大小的响应体，用于测试序列化和网络传输压力。
     */
    String echoPayload(String payload);

    /**
     * 服务端主动 sleep，用于测试超时、重试、线程池堆积和长尾延迟。
     */
    String sleep(Long millis);

    /**
     * 按固定比例制造失败，用于测试错误率、重试、熔断和降级。
     */
    String unstable(String name, Integer failurePercent);
}
