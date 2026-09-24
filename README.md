# blobcache

一个内存中的**二进制 blob 编解码与缓存**小库。`BlobCodec` 负责把 `(key, payload)`
编成一段自校验的字节、再解回；`BlobStore` 在内存里按 key 存这些 blob。
key 是字符串，payload 是任意字节串。

```java
BlobStore store = new BlobStore();
BlobStore.BlobRef ref = store.put("user:42", payload);
byte[] back = store.get("user:42");
List<String> keys = store.keys();
store.close();
```

## 目录

```
src/blobcache/BlobCodec.java          编解码
src/blobcache/BlobHeader.java         头部布局与字段读写
src/blobcache/BlobStore.java          内存存储与 BlobRef
src/blobcache/BlobFormatException.java 受检异常与错误码
test/blobcache/BlobCodecTest.java     既有用例（14 个）
scripts/build.sh                      编译到 out/
scripts/test.sh                       跑既有用例
scripts/check.sh                      固定验收入口（勿改）
check/Checker.java                    固定验收程序（勿改）
review/CHECKLIST.md                   审查核对表（勿改）
review/REVIEW.md                      审查结论（待填，本任务的交付物）
```

## 语言版本前提

- Java 17（`javac` / `java`）。
- **只用 JDK 标准库**，不引入 Maven / Gradle，不引入任何第三方依赖。
- 构建产物放在 `out/`，已在 `.gitignore` 中忽略。

## 怎么跑

```bash
bash scripts/build.sh      # 编译
bash scripts/test.sh       # 既有用例
bash scripts/check.sh      # 固定验收；支持 -list 与 --only <组名>
```

## 二进制格式

大端。头部固定 20 字节，其后依次是 key 的 UTF-8 字节、payload，
最后补 0 字节使整个 blob 的长度是 4 的倍数（补出的部分叫 padding）。

```
偏移  0..3   magic      int32    0x424C4F42（"BLOB"）
偏移  4..5   version    uint16   1
偏移  6..7   flags      uint16   保留，当前恒为 0
偏移  8..11  keyLen     int32    key 的 UTF-8 字节数
偏移 12..15  payloadLen int32    payload 字节数
偏移 16..19  crc32      int32    CRC32
```

`MAX_BLOB = 64 MiB`，指 **key 的 UTF-8 字节数 + payload 字节数** 的上限。

## 错误码

所有解码 / 校验失败都抛**受检的** `BlobFormatException`，其 `code()` 取值为：

| code | 含义 |
| --- | --- |
| `BadArgument` | 入参为 `null` |
| `Truncated` | 输入不足以构成一个完整的头部 |
| `BadMagic` | 头部 magic 不匹配 |
| `BadVersion` | 头部 version 不受支持 |
| `BadLength` | key + payload 超出 `MAX_BLOB` |
| `TrailingBytes` | 输入在 blob 结束后仍有剩余字节 |
| `BadCrc` | CRC32 校验不通过 |

## 对外保证

下面 6 条是 `blobcache` 的**对外契约**，实现必须全部守住；它们是本题验收点的唯一出处。

1. **完全消费输入**：`decode` 必须把输入**恰好**用完——blob 结束后若还有剩余字节，
   必须抛 `BlobFormatException`（`TrailingBytes`），不许静默忽略。
2. **长度受限**：解码得到的 `payload` 长度必须等于头部声明的 `payloadLen`，
   且 `keyLen + payloadLen` **不得超过** `MAX_BLOB`。
3. **CRC 覆盖范围固定**：头部的 `crc32` 覆盖 **key 的字节与 payload 的字节**，
   **不包含** padding。因此 padding 取值任意（含非 0）都不影响解码，
   合法的旧数据不得被判为损坏。
4. **key 按字节序比较**：`BlobStore.keys()` 返回的 key 列表必须按 key 的
   **UTF-8 字节序**升序排列（等价于逐字节比较），**不是** `String` 的自然序
   （UTF-16 码元序）；两者在含增补平面字符的 key 上结论不同。
5. **非法输入一律受检异常**：任何输入（空数组、截断、声明长度超限或为负、
   CRC 不符）都必须抛**受检的** `BlobFormatException`，
   不得泄漏 `ArrayIndexOutOfBoundsException` / `NegativeArraySizeException` 等运行时异常。
6. **句柄与 store 解耦**：`put` 返回的 `BlobRef` 必须与 `BlobStore` 解耦——
   `store.close()` 之后再调用 `ref.key()`，仍必须返回该次写入的 key，不得抛异常。
