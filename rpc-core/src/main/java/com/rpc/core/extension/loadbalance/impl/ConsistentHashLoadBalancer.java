package com.rpc.core.extension.loadbalance.impl;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带虚拟节点的一致性哈希负载均衡器。
 */
@Slf4j
public class ConsistentHashLoadBalancer implements LoadBalancer {
    private static final int VIRTUAL_NODES = 160;
    private final ConcurrentHashMap<String, RingState> rings = new ConcurrentHashMap<>();

    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty() || serviceName == null || serviceName.isBlank()) {
            return null;
        }

        String signature = buildAddressSignature(addresses);
        TreeMap<Integer, String> ring = rings.compute(serviceName, (ignored, existing) -> {
            if (existing != null && existing.signature().equals(signature)) {
                return existing;
            }
            return new RingState(signature, buildRing(addresses));
        }).ring();

        int hash = murmurHash(serviceName.getBytes(StandardCharsets.UTF_8));
        SortedMap<Integer, String> tailMap = ring.tailMap(hash);
        Integer nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        InetSocketAddress selected = stringToAddress(ring.get(nodeHash));
        log.info("[ConsistentHash] selected={}", selected);
        return selected;
    }

    @Override
    public String getName() {
        return "consistentHash";
    }

    private TreeMap<Integer, String> buildRing(List<InetSocketAddress> addresses) {
        TreeMap<Integer, String> ring = new TreeMap<>();
        for (InetSocketAddress address : addresses) {
            addVirtualNodes(ring, address);
        }
        return ring;
    }

    private void addVirtualNodes(TreeMap<Integer, String> ring, InetSocketAddress address) {
        String addressStr = addressToString(address);
        for (int i = 0; i < VIRTUAL_NODES; i++) {
            String virtualNodeName = addressStr + "#" + i;
            int hash = murmurHash(virtualNodeName.getBytes(StandardCharsets.UTF_8));
            ring.put(hash, addressStr);
        }
    }

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
            case 3:
                h ^= (data[offset + 2] & 0xff) << 16;
            case 2:
                h ^= (data[offset + 1] & 0xff) << 8;
            case 1:
                h ^= (data[offset] & 0xff);
                h *= m;
            default:
                break;
        }

        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h;
    }

    private String addressToString(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    private InetSocketAddress stringToAddress(String addressStr) {
        String[] parts = addressStr.split(":");
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }

    private String buildAddressSignature(List<InetSocketAddress> addresses) {
        List<String> normalized = new ArrayList<>(addresses.size());
        for (InetSocketAddress address : addresses) {
            normalized.add(addressToString(address));
        }
        normalized.sort(Comparator.naturalOrder());
        return String.join(",", normalized);
    }

    private record RingState(String signature, TreeMap<Integer, String> ring) {
    }
}
