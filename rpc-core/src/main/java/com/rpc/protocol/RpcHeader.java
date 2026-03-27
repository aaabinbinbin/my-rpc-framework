package com.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcHeader {
    @Builder.Default
    private int magicNumber = 0x12345678;

    @Builder.Default
    private byte version = 1;

    private byte serializerType;

    private byte messageType;

    private byte reserved;

    private long requestId;

    private int bodyLength;

    private long checksum;

    public static final int HEADER_LENGTH = 24;

    public static final int MAGIC_NUMBER = 0x12345678;

    public static final byte VERSION = 1;
}
