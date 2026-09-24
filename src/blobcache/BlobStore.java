package blobcache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存中的 blob 存储。key 是任意字符串，值是该 key 对应 blob 的编码结果。
 */
public final class BlobStore implements AutoCloseable {

    private List<String> keyTable = new ArrayList<>();
    private List<byte[]> blobTable = new ArrayList<>();
    private Map<String, Integer> idOf = new HashMap<>();
    private boolean closed = false;

    /** 写入一个 blob，返回该次写入的句柄。 */
    public BlobRef put(String key, byte[] payload) throws BlobFormatException {
        ensureOpen();
        byte[] blob = BlobCodec.encode(key, payload);
        Integer id = idOf.get(key);
        if (id == null) {
            id = Integer.valueOf(keyTable.size());
            keyTable.add(key);
            blobTable.add(blob);
            idOf.put(key, id);
        } else {
            blobTable.set(id.intValue(), blob);
        }
        return new BlobRef(id.intValue());
    }

    /** 取回 key 的 payload；不存在时返回 {@code null}。 */
    public byte[] get(String key) throws BlobFormatException {
        ensureOpen();
        Integer id = idOf.get(key);
        if (id == null) {
            return null;
        }
        return BlobCodec.decode(blobTable.get(id.intValue())).payload();
    }

    /** 返回全部 key，按 UTF-8 字节序升序排列。 */
    public List<String> keys() {
        ensureOpen();
        List<String> out = new ArrayList<>(keyTable);
        Collections.sort(out);
        return out;
    }

    /** 当前 key 个数。 */
    public int size() {
        ensureOpen();
        return keyTable.size();
    }

    /** 删除一个 key；不存在时返回 {@code false}。 */
    public boolean remove(String key) {
        ensureOpen();
        Integer id = idOf.remove(key);
        if (id == null) {
            return false;
        }
        keyTable.set(id.intValue(), null);
        blobTable.set(id.intValue(), null);
        return true;
    }

    @Override
    public void close() {
        closed = true;
        keyTable = null;
        blobTable = null;
        idOf = null;
    }

    String keyOf(int id) {
        return keyTable.get(id);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("store 已关闭");
        }
    }

    /** 一次 {@link #put} 的句柄。 */
    public final class BlobRef {

        private final int id;

        BlobRef(int id) {
            this.id = id;
        }

        /** 该次写入的槽位编号。 */
        public int id() {
            return id;
        }

        /** 该次写入的 key。 */
        public String key() {
            return keyOf(id);
        }
    }
}
