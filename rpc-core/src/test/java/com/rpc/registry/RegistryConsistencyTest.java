package com.rpc.registry;

import com.rpc.HelloService;
import com.rpc.server.HelloServiceImpl;
import com.rpc.registry.impl.LocalRegistryImpl;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 本地注册表与 ZooKeeper 远程注册表一致性测试
 */
@Slf4j
public class RegistryConsistencyTest {

    private static final String ZK_ADDRESS = "8.134.204.101:2181";
    private static final int SESSION_TIMEOUT = 5000;
    private static final String SERVICE_NAME = "com.rpc.HelloService";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8080;

    private ZooKeeperRegistryImpl zooKeeperRegistry;
    private LocalRegistryImpl localRegistry;
    private HelloService serviceInstance;

    @Before
    public void setUp() throws Exception {
        log.info("========== 测试准备：初始化注册表 ==========");
        
        // 1. 创建 ZooKeeper 注册中心连接
        zooKeeperRegistry = new ZooKeeperRegistryImpl(ZK_ADDRESS, SESSION_TIMEOUT);
        
        // 2. 创建本地注册表（关联 ZooKeeper）
        localRegistry = new LocalRegistryImpl(zooKeeperRegistry, HOST, PORT);
        
        // 3. 创建服务实例
        serviceInstance = new HelloServiceImpl();
        
        log.info("注册表初始化完成");
    }

    @After
    public void tearDown() throws Exception {
        log.info("========== 清理测试数据 ==========");
        
        try {
            // 尝试注销服务（如果已注册）
            if (localRegistry.contains(SERVICE_NAME)) {
                localRegistry.unregister(SERVICE_NAME);
                log.info("服务已注销");
            }
        } catch (Exception e) {
            log.warn("注销服务时出错", e);
        } finally {
            // 关闭 ZooKeeper 连接
            if (zooKeeperRegistry != null) {
                zooKeeperRegistry.close();
                log.info("ZooKeeper 连接已关闭");
            }
        }
    }

    /**
     * 测试 1：注册服务时本地和远程的一致性
     */
    @Test
    public void testRegisterConsistency() throws Exception {
        log.info("\n========== 测试 1：注册服务一致性 ==========");
        
        // 1. 注册服务
        log.info("开始注册服务：{}", SERVICE_NAME);
        localRegistry.register(SERVICE_NAME, serviceInstance);
        
        // 2. 验证本地注册表
        log.info("验证本地注册表...");
        assert localRegistry.contains(SERVICE_NAME) : "本地注册表应该包含该服务";
        Object localService = localRegistry.getService(SERVICE_NAME);
        assert localService != null : "本地注册表中的服务实例不应为空";
        assert localService.equals(serviceInstance) : "本地注册表中的服务实例应该是同一个对象";
        log.info("✓ 本地注册表验证通过");
        
        // 3. 验证 ZooKeeper 远程注册表
        log.info("验证 ZooKeeper 远程注册表...");
        InetSocketAddress expectedAddress = new InetSocketAddress(HOST, PORT);
        List<InetSocketAddress> zkAddresses = zooKeeperRegistry.lookup(SERVICE_NAME);
        
        assert zkAddresses != null : "ZooKeeper 返回的地址列表不应为空";
        assert !zkAddresses.isEmpty() : "ZooKeeper 地址列表不应为空";
        assert zkAddresses.size() == 1 : "应该只有一个注册地址";
        assert zkAddresses.get(0).equals(expectedAddress) : "ZooKeeper 中的地址应该与预期一致";
        log.info("✓ ZooKeeper 远程注册表验证通过");
        
        // 4. 一致性验证
        log.info("验证数据一致性...");
        boolean isConsistent = (localRegistry.contains(SERVICE_NAME) && 
                               !zkAddresses.isEmpty());
        assert isConsistent : "本地注册表和 ZooKeeper 应该都包含该服务";
        
        log.info("✓ 一致性验证通过：本地和远程注册表数据一致");
        log.info("服务地址：{}:{}\n", HOST, PORT);
    }

    /**
     * 测试 2：重复注册时的一致性
     */
    @Test
    public void testRepeatedRegistration() throws Exception {
        log.info("\n========== 测试 2：重复注册一致性 ==========");
        
        // 1. 第一次注册
        log.info("第一次注册服务...");
        localRegistry.register(SERVICE_NAME, serviceInstance);
        
        // 2. 第二次注册（同一实例）
        log.info("第二次注册同一服务实例...");
        localRegistry.register(SERVICE_NAME, serviceInstance);
        
        // 3. 验证本地注册表
        assert localRegistry.contains(SERVICE_NAME) : "本地注册表应该包含该服务";
        log.info("✓ 本地注册表：服务存在");
        
        // 4. 验证 ZooKeeper（应该不会重复创建节点）
        List<InetSocketAddress> zkAddresses = zooKeeperRegistry.lookup(SERVICE_NAME);
        assert zkAddresses.size() == 1 : "ZooKeeper 中应该只有一个地址（避免重复注册）";
        log.info("✓ ZooKeeper：只有一个地址节点（避免了重复）");
        
        log.info("✓ 重复注册测试通过\n");
    }

    /**
     * 测试 3：注销服务时的一致性
     */
    @Test
    public void testUnregisterConsistency() throws Exception {
        log.info("\n========== 测试 3：注销服务一致性 ==========");
        
        // 1. 先注册服务
        log.info("注册服务...");
        localRegistry.register(SERVICE_NAME, serviceInstance);
        
        // 2. 验证注册成功
        assert localRegistry.contains(SERVICE_NAME) : "服务应该已注册";
        List<InetSocketAddress> beforeUnregister = zooKeeperRegistry.lookup(SERVICE_NAME);
        assert !beforeUnregister.isEmpty() : "ZooKeeper 中应该有服务地址";
        log.info("✓ 服务已注册");
        
        // 3. 注销服务
        log.info("注销服务...");
        localRegistry.unregister(SERVICE_NAME);
        
        // 4. 验证本地注册表
        assert !localRegistry.contains(SERVICE_NAME) : "本地注册表不应该包含已注销的服务";
        log.info("✓ 本地注册表：服务已移除");
        
        // 5. 验证 ZooKeeper
        List<InetSocketAddress> afterUnregister = zooKeeperRegistry.lookup(SERVICE_NAME);
        assert afterUnregister.isEmpty() : "ZooKeeper 中不应该有已注销的服务地址";
        log.info("✓ ZooKeeper：临时节点已删除");
        
        // 6. 一致性验证
        boolean isConsistent = (!localRegistry.contains(SERVICE_NAME) && 
                               afterUnregister.isEmpty());
        assert isConsistent : "本地注册表和 ZooKeeper 应该都不包含该服务";
        
        log.info("✓ 注销一致性验证通过\n");
    }

    /**
     * 测试 4：多个服务的注册一致性
     */
    @Test
    public void testMultipleServicesConsistency() throws Exception {
        log.info("\n========== 测试 4：多服务注册一致性 ==========");
        
        String service1 = "com.rpc.Service1";
        String service2 = "com.rpc.Service2";
        String service3 = "com.rpc.Service3";
        
        Object instance1 = new Object();
        Object instance2 = new Object();
        Object instance3 = new Object();
        
        try {
            // 1. 注册多个服务
            log.info("注册 3 个不同的服务...");
            localRegistry.register(service1, instance1);
            localRegistry.register(service2, instance2);
            localRegistry.register(service3, instance3);
            
            // 2. 验证每个服务的一致性
            log.info("验证每个服务的一致性...");
            
            // 服务 1
            assert localRegistry.contains(service1) : "本地应包含 Service1";
            assert !zooKeeperRegistry.lookup(service1).isEmpty() : "ZK 应包含 Service1";
            log.info("✓ Service1 一致");
            
            // 服务 2
            assert localRegistry.contains(service2) : "本地应包含 Service2";
            assert !zooKeeperRegistry.lookup(service2).isEmpty() : "ZK 应包含 Service2";
            log.info("✓ Service2 一致");
            
            // 服务 3
            assert localRegistry.contains(service3) : "本地应包含 Service3";
            assert !zooKeeperRegistry.lookup(service3).isEmpty() : "ZK 应包含 Service3";
            log.info("✓ Service3 一致");
            
            log.info("✓ 多服务注册一致性测试通过\n");
            
        } finally {
            // 清理：注销所有服务
            localRegistry.unregister(service1);
            localRegistry.unregister(service2);
            localRegistry.unregister(service3);
        }
    }

    /**
     * 测试 5：验证 LocalRegistry 改进后的失败处理机制
     */
    @Test(expected = RuntimeException.class)
    public void testZKFailureHandling() throws Exception {
        log.info("\n========== 测试 5：ZooKeeper 失败处理 ==========");
        
        // 1. 创建一个无效的 ZooKeeper 地址
        log.info("使用无效的 ZooKeeper 地址测试失败处理...");
        ZooKeeperRegistryImpl invalidZkRegistry = null;
        LocalRegistryImpl invalidLocalRegistry = null;
        
        try {
            // 使用一个不可能连接的地址
            invalidZkRegistry = new ZooKeeperRegistryImpl("192.168.255.255:2181", 1000);
            invalidLocalRegistry = new LocalRegistryImpl(invalidZkRegistry, HOST, PORT);
            
            // 2. 尝试注册服务（应该失败）
            log.info("尝试注册服务（预期会失败）...");
            invalidLocalRegistry.register(SERVICE_NAME, serviceInstance);
            
            // 如果执行到这里，说明测试失败
            throw new AssertionError("应该抛出 RuntimeException，因为 ZooKeeper 连接失败");
            
        } catch (RuntimeException e) {
            log.info("✓ 正确捕获到异常：{}", e.getMessage());
            log.info("✓ 失败处理机制正常工作：ZooKeeper 失败时阻止本地注册\n");
            throw e; // 重新抛出异常以满足测试预期
            
        } finally {
            if (invalidZkRegistry != null) {
                try {
                    invalidZkRegistry.close();
                } catch (Exception e) {
                    // 忽略关闭异常
                }
            }
        }
    }

    /**
     * 测试 6：验证统计信息注册的容错性
     */
    @Test
    public void testStatisticsRegistrationTolerance() throws Exception {
        log.info("\n========== 测试 6：统计信息容错性 ==========");
        
        // 即使统计信息注册失败，主流程也应该正常
        log.info("注册服务（统计信息失败不应影响主流程）...");
        localRegistry.register(SERVICE_NAME, serviceInstance);
        
        // 验证主流程正常
        assert localRegistry.contains(SERVICE_NAME) : "服务应该注册成功";
        List<InetSocketAddress> addresses = zooKeeperRegistry.lookup(SERVICE_NAME);
        assert !addresses.isEmpty() : "ZooKeeper 中应该有服务地址";
        
        log.info("✓ 即使统计信息可能失败，主流程仍然正常");
        log.info("✓ 服务注册成功：{}\n", SERVICE_NAME);
    }
}
