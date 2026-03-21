package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一致性 Hash 负载均衡策略
 */
@Slf4j
public class ConsistentHashLoadBalancer implements LoadBalancer {
    // 每个服务一个哈希环
    private final ConcurrentHashMap<String, TreeMap<Integer, String>> rings = new ConcurrentHashMap<>();

    // 虚拟节点数量
    private static final int VIRTUAL_NODES = 160;
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        // 获取或创建该服务的哈希环
        TreeMap<Integer, String> ring = rings.computeIfAbsent(serviceName, k -> {
            TreeMap<Integer, String> newRing = new TreeMap<>();
            // 初始化哈希环
            for (InetSocketAddress address : addresses) {
                addVirtualNodes(newRing, address);
            }
            return newRing;
        });

        // 如果环为空，重新构建
        if (ring.isEmpty()) {
            for (InetSocketAddress address : addresses) {
                addVirtualNodes(ring, address);
            }
        }

        // 使用服务名作为 hash key（也可以使用方法名等）
        int hash = murmurHash(serviceName.getBytes(StandardCharsets.UTF_8));

        // 顺时针查找
        SortedMap<Integer, String> tailMap = ring.tailMap(hash);
        Integer nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();

        String selectedAddress = ring.get(nodeHash);
        InetSocketAddress selected = stringToAddress(selectedAddress);

        log.info("[ConsistentHash] 选择：{}", selected);
        return selected;
    }

    /**
     * 添加虚拟节点
     */
    private void addVirtualNodes(TreeMap<Integer, String> ring, InetSocketAddress address) {
        String addressStr = addressToString(address);
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            String virtualNodeName = addressStr + "#" + i;
            int hash = murmurHash(virtualNodeName.getBytes(StandardCharsets.UTF_8));
            ring.put(hash, addressStr);
        }
    }

    @Override
    public String getName() {
        return "consistentHash";
    }

    // ==================== 辅助方法 ====================

    private int murmurHash(byte[] data) {
        int len = data.length;
        int seed = 0x1234ABCD;
        int m = 0x5BD1E995;
        int r = 24;

        int h = seed ^ len;
        int len4 = len / 4;

        for (int i = 0; i < len4; i++) {
            int i4 = i * 4;
            int k = (data[i4] & 0xff) + ((data[i4 + 1] & 0xff) << 8)
                    + ((data[i4 + 2] & 0xff) << 16) + ((data[i4 + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }

        int offset = len4 * 4;
        switch (len - offset) {
            case 3: h ^= (data[offset + 2] & 0xff) << 16;
            case 2: h ^= (data[offset + 1] & 0xff) << 8;
            case 1: h ^= (data[offset] & 0xff);
                h *= m;
        }

        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;

        return h;
    }

    private String addressToString(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    private InetSocketAddress stringToAddress(String addressStr) {
        String[] parts = addressStr.split(":");
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }
}
