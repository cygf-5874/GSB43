package blobcache;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * blobcache 既有用例。自带极简 runner（无第三方测试框架）。
 *
 * <p>覆盖范围只包括正常路径：合法输入、ASCII key、小 payload、未关闭的 store。
 */
public final class BlobCodecTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        run("roundTripAscii", BlobCodecTest::roundTripAscii);
        run("roundTripEmptyPayload", BlobCodecTest::roundTripEmptyPayload);
        run("roundTripEmptyKey", BlobCodecTest::roundTripEmptyKey);
        run("roundTripLargePayload", BlobCodecTest::roundTripLargePayload);
        run("roundTripWithPadding", BlobCodecTest::roundTripWithPadding);
        run("headerFieldsParsed", BlobCodecTest::headerFieldsParsed);
        run("badMagicRejected", BlobCodecTest::badMagicRejected);
        run("truncatedHeaderRejected", BlobCodecTest::truncatedHeaderRejected);
        run("badVersionRejected", BlobCodecTest::badVersionRejected);
        run("crcMismatchRejected", BlobCodecTest::crcMismatchRejected);
        run("storePutGet", BlobCodecTest::storePutGet);
        run("storeOverwriteSameKey", BlobCodecTest::storeOverwriteSameKey);
        run("storeKeysSortedAscii", BlobCodecTest::storeKeysSortedAscii);
        run("refKeyBeforeClose", BlobCodecTest::refKeyBeforeClose);
        System.out.println("通过 " + passed + "/" + (passed + failed));
        if (failed > 0) {
            System.exit(1);
        }
    }

    private interface Case {
        void run() throws Exception;
    }

    private static void run(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + "  " + t);
        }
    }

    private static void check(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    private static void eq(Object expected, Object actual, String message) {
        boolean same = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!same) {
            throw new AssertionError(message + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private static void bytesEq(byte[] expected, byte[] actual, String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message + " 期望=" + Arrays.toString(expected)
                    + " 实际=" + Arrays.toString(actual));
        }
    }

    private static BlobFormatException expectFormat(byte[] input) {
        try {
            BlobCodec.decode(input);
        } catch (BlobFormatException e) {
            return e;
        }
        throw new AssertionError("期望抛出 BlobFormatException，但正常返回了");
    }

    private static void roundTripAscii() throws Exception {
        byte[] payload = "hello blob".getBytes(StandardCharsets.UTF_8);
        byte[] blob = BlobCodec.encode("user:1", payload);
        BlobCodec.Blob decoded = BlobCodec.decode(blob);
        eq("user:1", decoded.key(), "key 应往返一致");
        bytesEq(payload, decoded.payload(), "payload 应往返一致");
    }

    private static void roundTripEmptyPayload() throws Exception {
        byte[] blob = BlobCodec.encode("k", new byte[0]);
        BlobCodec.Blob decoded = BlobCodec.decode(blob);
        eq("k", decoded.key(), "key 应往返一致");
        eq(0, decoded.payload().length, "空 payload 的长度应为 0");
    }

    private static void roundTripEmptyKey() throws Exception {
        byte[] payload = new byte[] {1, 2, 3};
        byte[] blob = BlobCodec.encode("", payload);
        BlobCodec.Blob decoded = BlobCodec.decode(blob);
        eq("", decoded.key(), "空 key 应往返一致");
        bytesEq(payload, decoded.payload(), "payload 应往返一致");
    }

    private static void roundTripLargePayload() throws Exception {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        byte[] blob = BlobCodec.encode("big", payload);
        bytesEq(payload, BlobCodec.decode(blob).payload(), "4096 字节 payload 应往返一致");
    }

    private static void roundTripWithPadding() throws Exception {
        // 头部 20 + key 4 + payload 3 = 27，补齐到 28，会多出 1 字节 padding
        byte[] payload = new byte[] {7, 8, 9};
        byte[] blob = BlobCodec.encode("abcd", payload);
        eq(28, blob.length, "编码长度应按 4 字节对齐");
        BlobCodec.Blob decoded = BlobCodec.decode(blob);
        eq("abcd", decoded.key(), "key 应往返一致");
        bytesEq(payload, decoded.payload(), "payload 应往返一致");
    }

    private static void headerFieldsParsed() throws Exception {
        byte[] blob = BlobCodec.encode("kk", "xyz".getBytes(StandardCharsets.UTF_8));
        BlobHeader header = BlobCodec.parseHeader(blob);
        eq(BlobHeader.MAGIC, header.magic, "magic 应为 MAGIC");
        eq(BlobHeader.VERSION, header.version, "version 应为 VERSION");
        eq(2, header.keyLen, "keyLen 应为 2");
        eq(3, header.payloadLen, "payloadLen 应为 3");
    }

    private static void badMagicRejected() throws Exception {
        byte[] blob = BlobCodec.encode("k", new byte[] {1});
        blob[0] = 0x00;
        eq("BadMagic", expectFormat(blob).code(), "magic 非法应抛 BadMagic");
    }

    private static void truncatedHeaderRejected() throws Exception {
        byte[] tooShort = new byte[BlobHeader.SIZE - 1];
        eq("Truncated", expectFormat(tooShort).code(), "不足一个头部应抛 Truncated");
    }

    private static void badVersionRejected() throws Exception {
        byte[] blob = BlobCodec.encode("k", new byte[] {1});
        blob[4] = 0x00;
        blob[5] = 0x09;
        eq("BadVersion", expectFormat(blob).code(), "版本不支持应抛 BadVersion");
    }

    private static void crcMismatchRejected() throws Exception {
        byte[] blob = BlobCodec.encode("k", new byte[] {1, 2, 3});
        blob[BlobHeader.SIZE + 1] = (byte) (blob[BlobHeader.SIZE + 1] ^ 0x01);
        eq("BadCrc", expectFormat(blob).code(), "payload 被改动应抛 BadCrc");
    }

    private static void storePutGet() throws Exception {
        BlobStore store = new BlobStore();
        try {
            store.put("alpha", "A".getBytes(StandardCharsets.UTF_8));
            store.put("beta", "B".getBytes(StandardCharsets.UTF_8));
            bytesEq("A".getBytes(StandardCharsets.UTF_8), store.get("alpha"), "alpha 应能取回");
            bytesEq("B".getBytes(StandardCharsets.UTF_8), store.get("beta"), "beta 应能取回");
            eq(2, store.size(), "size 应为 2");
        } finally {
            store.close();
        }
    }

    private static void storeOverwriteSameKey() throws Exception {
        BlobStore store = new BlobStore();
        try {
            store.put("k", "one".getBytes(StandardCharsets.UTF_8));
            store.put("k", "two".getBytes(StandardCharsets.UTF_8));
            eq(1, store.size(), "同一 key 重复 put 不应增长");
            bytesEq("two".getBytes(StandardCharsets.UTF_8), store.get("k"), "应取回最后一次写入的值");
        } finally {
            store.close();
        }
    }

    private static void storeKeysSortedAscii() throws Exception {
        BlobStore store = new BlobStore();
        try {
            store.put("delta", new byte[] {1});
            store.put("alpha", new byte[] {2});
            store.put("charlie", new byte[] {3});
            store.put("bravo", new byte[] {4});
            List<String> keys = store.keys();
            eq(Arrays.asList("alpha", "bravo", "charlie", "delta"), keys, "ASCII key 应按序返回");
        } finally {
            store.close();
        }
    }

    private static void refKeyBeforeClose() throws Exception {
        BlobStore store = new BlobStore();
        try {
            BlobStore.BlobRef ref = store.put("user:42", new byte[] {9});
            eq(0, ref.id(), "首个 key 的槽位编号应为 0");
            eq("user:42", ref.key(), "close 之前 ref.key() 应可用");
        } finally {
            store.close();
        }
    }
}
