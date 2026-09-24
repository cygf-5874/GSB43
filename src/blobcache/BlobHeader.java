package blobcache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * blob 头部。字节序为大端，共 {@link #SIZE} 字节：
 *
 * <pre>
 * 偏移  0..3   magic      int32   {@link #MAGIC}
 * 偏移  4..5   version    uint16  {@link #VERSION}
 * 偏移  6..7   flags      uint16  保留位，当前恒为 0
 * 偏移  8..11  keyLen     int32   key 的 UTF-8 字节数
 * 偏移 12..15  payloadLen int32   payload 字节数（声明值）
 * 偏移 16..19  crc32      int32   key 与 payload 的 CRC32
 * </pre>
 *
 * <p>头部之后依次是 key、payload，最后补齐 0 字节使整个 blob 长度为 4 的倍数。
 *
 * <p>本类只负责字段的读写与承载，不做合法性判定。
 */
public final class BlobHeader {

    /** 头部魔数：ASCII 的 "BLOB"。 */
    public static final int MAGIC = 0x424C4F42;

    /** 当前支持的格式版本。 */
    public static final int VERSION = 1;

    /** 头部长度（字节）。 */
    public static final int SIZE = 20;

    public final int magic;
    public final int version;
    public final int flags;
    public final int keyLen;
    public final int payloadLen;
    public final int crc32;

    /** 构造一个 magic 为 {@link #MAGIC} 的头部。 */
    public BlobHeader(int version, int flags, int keyLen, int payloadLen, int crc32) {
        this(MAGIC, version, flags, keyLen, payloadLen, crc32);
    }

    private BlobHeader(int magic, int version, int flags, int keyLen, int payloadLen, int crc32) {
        this.magic = magic;
        this.version = version;
        this.flags = flags;
        this.keyLen = keyLen;
        this.payloadLen = payloadLen;
        this.crc32 = crc32;
    }

    /** 从 {@code in[offset..offset+SIZE)} 读出头部。调用方需保证区间完整。 */
    public static BlobHeader parse(byte[] in, int offset) {
        ByteBuffer buf = ByteBuffer.wrap(in).order(ByteOrder.BIG_ENDIAN);
        int magic = buf.getInt(offset);
        int version = buf.getShort(offset + 4) & 0xFFFF;
        int flags = buf.getShort(offset + 6) & 0xFFFF;
        int keyLen = buf.getInt(offset + 8);
        int payloadLen = buf.getInt(offset + 12);
        int crc32 = buf.getInt(offset + 16);
        return new BlobHeader(magic, version, flags, keyLen, payloadLen, crc32);
    }

    /** 把本头部写入 {@code out[offset..offset+SIZE)}。调用方需保证区间完整。 */
    public void write(byte[] out, int offset) {
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(offset, magic);
        buf.putShort(offset + 4, (short) version);
        buf.putShort(offset + 6, (short) flags);
        buf.putInt(offset + 8, keyLen);
        buf.putInt(offset + 12, payloadLen);
        buf.putInt(offset + 16, crc32);
    }

    @Override
    public String toString() {
        return "BlobHeader{magic=0x" + Integer.toHexString(magic)
                + ", version=" + version
                + ", flags=" + flags
                + ", keyLen=" + keyLen
                + ", payloadLen=" + payloadLen
                + ", crc32=0x" + Integer.toHexString(crc32) + "}";
    }
}
