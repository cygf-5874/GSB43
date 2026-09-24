package blobcache;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * blob 的编解码。二进制布局见 {@link BlobHeader} 与 README「二进制格式」一节。
 */
public final class BlobCodec {

    /** 单个 blob（key 的 UTF-8 字节 + payload）允许的最大字节数。 */
    public static final int MAX_BLOB = 64 * 1024 * 1024;

    private static final int ALIGN = 4;

    private BlobCodec() {
    }

    /** 解码结果：key 与其 payload。 */
    public static final class Blob {

        private final String key;
        private final byte[] payload;

        Blob(String key, byte[] payload) {
            this.key = key;
            this.payload = payload;
        }

        public String key() {
            return key;
        }

        public byte[] payload() {
            return payload;
        }
    }

    /** 把 key 与 payload 编码成一个 blob。 */
    public static byte[] encode(String key, byte[] payload) throws BlobFormatException {
        if (key == null) {
            throw new BlobFormatException("BadArgument", "key 不能为 null");
        }
        if (payload == null) {
            throw new BlobFormatException("BadArgument", "payload 不能为 null");
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length + payload.length > MAX_BLOB) {
            throw new BlobFormatException("BadLength", "key 与 payload 合计超过 MAX_BLOB");
        }
        int bodyLen = keyBytes.length + payload.length;
        int total = align4(BlobHeader.SIZE + bodyLen);
        byte[] out = new byte[total];
        System.arraycopy(keyBytes, 0, out, BlobHeader.SIZE, keyBytes.length);
        System.arraycopy(payload, 0, out, BlobHeader.SIZE + keyBytes.length, payload.length);
        CRC32 crc = new CRC32();
        crc.update(out, BlobHeader.SIZE, total - BlobHeader.SIZE);
        BlobHeader header =
                new BlobHeader(BlobHeader.VERSION, 0, keyBytes.length, payload.length, (int) crc.getValue());
        header.write(out, 0);
        return out;
    }

    /** 只解析并校验头部。 */
    public static BlobHeader parseHeader(byte[] input) throws BlobFormatException {
        if (input == null) {
            throw new BlobFormatException("BadArgument", "input 不能为 null");
        }
        if (input.length < BlobHeader.SIZE) {
            throw new BlobFormatException("Truncated", "不足 " + BlobHeader.SIZE + " 字节，无法读出头部");
        }
        BlobHeader header = BlobHeader.parse(input, 0);
        if (header.magic != BlobHeader.MAGIC) {
            throw new BlobFormatException("BadMagic", "magic 不匹配");
        }
        if (header.version != BlobHeader.VERSION) {
            throw new BlobFormatException("BadVersion", "不支持的版本 " + header.version);
        }
        return header;
    }

    /** 解码一个 blob。 */
    public static Blob decode(byte[] input) throws BlobFormatException {
        BlobHeader header = parseHeader(input);
        int keyLen = header.keyLen;
        int payloadLen = header.payloadLen;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(input, BlobHeader.SIZE + keyLen, payload, 0, payloadLen);
        int bodyEnd = align4(BlobHeader.SIZE + keyLen + payloadLen);
        CRC32 crc = new CRC32();
        crc.update(input, BlobHeader.SIZE, bodyEnd - BlobHeader.SIZE);
        if ((int) crc.getValue() != header.crc32) {
            throw new BlobFormatException("BadCrc", "CRC32 不匹配");
        }
        String key = new String(input, BlobHeader.SIZE, keyLen, StandardCharsets.UTF_8);
        return new Blob(key, payload);
    }

    static int align4(int n) {
        return (n + ALIGN - 1) / ALIGN * ALIGN;
    }
}
