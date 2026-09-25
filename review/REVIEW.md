# 代码审查结论 · blobcache

> 审查对象：`src/blobcache/` 当前实现（Java 17，仅 JDK 标准库）。
> 判定依据：README「对外保证」6 条。所有现象均用 `javac`/`java` 对本仓库 `out/` 下的类实测复现。

## 一、保证判定

对 README「对外保证」的 6 条逐条给出判定：**违反** 或 **未违反**（二选一，不许留空）。

| 保证编号 | 判定 | 说明 |
| --- | --- | --- |
| 1 | 违反 | `decode` 从不检查输入是否恰好用完，blob 之后的尾部字节被直接丢弃，从不抛 `TrailingBytes` |
| 2 | 违反 | `decode` 没有 `keyLen + payloadLen > MAX_BLOB` 的检查，`BadLength` 在解码路径上永远不会抛出 |
| 3 | 违反 | `decode` 的 CRC 计算区间取到 4 字节对齐后的 `bodyEnd`，把 padding 算进了 CRC，非 0 padding 的合法数据被判 `BadCrc` |
| 4 | 违反 | `BlobStore.keys()` 用 `Collections.sort`（String 自然序，UTF-16 码元序），不是 UTF-8 字节序 |
| 5 | 违反 | 负的 `payloadLen`、负的 `keyLen`、声明长度超过实际输入，分别泄漏 `NegativeArraySizeException`、`StringIndexOutOfBoundsException`、`ArrayIndexOutOfBoundsException` |
| 6 | 违反 | `close()` 把 `keyTable` 置为 `null`，`BlobRef.key()` 在 close 之后抛 `NullPointerException` |

## 二、缺陷清单

| 保证编号 | 缺陷位置（文件:成员） | 触发输入 | 可观察证据 | 违反哪一条 | 正确做法 |
| --- | --- | --- | --- | --- | --- |
| 1 | BlobCodec.java:decode | `decode(concat(encode("k", {1}), {0,0,0,0}))`，即在合法 blob 后追加 4 字节 | 期望抛 `BlobFormatException`（`TrailingBytes`）；实际正常返回 `Blob{key="k"}`，尾部 4 字节被静默吞掉 | `decode` 没有把输入恰好用完，尾部剩余字节未抛 `TrailingBytes`，违反保证 1 | `decode` 末尾校验 `input.length == align4(20 + keyLen + payloadLen)`，不等则抛 `BlobFormatException("TrailingBytes", ...)` |
| 2 | BlobCodec.java:decode | 20 字节合法头部，`keyLen=0`、`payloadLen=67108865`（`MAX_BLOB+1`）、`crc32=0` | 期望抛 `BlobFormatException`（`BadLength`）；实际没有 `BadLength` 检查，抛 `ArrayIndexOutOfBoundsException: last source index 67108885 out of bounds for byte[20]` | `keyLen + payloadLen` 超过 `MAX_BLOB` 未被拒绝，`BadLength` 永不触发，违反保证 2 | `decode` 在分配数组前校验 `keyLen + payloadLen > MAX_BLOB` 时抛 `BlobFormatException("BadLength", ...)` |
| 5 | BlobCodec.java:decode | 20 字节合法头部，`keyLen=0`、`payloadLen=-1`、`crc32=0` | 期望抛受检的 `BlobFormatException`；实际抛运行时异常 `NegativeArraySizeException: -1` | 未校验 `payloadLen` 非负就执行 `new byte[payloadLen]`，泄漏运行时异常，违反保证 5 | 分配数组前校验 `payloadLen >= 0`（及 `keyLen >= 0`），非法时抛 `BlobFormatException` |
| 5 | BlobCodec.java:decode | `encode("key", {1,2,3,4})` 的结果截断为前 22 字节（头部声明 body 到偏移 27） | 期望抛受检的 `BlobFormatException`；实际抛 `ArrayIndexOutOfBoundsException: last source index 27 out of bounds for byte[22]` | 未校验输入长度是否覆盖声明的 body 就 `System.arraycopy`，泄漏运行时异常，违反保证 5 | `arraycopy` 前校验 `20 + keyLen + payloadLen <= input.length`，不足时抛 `BlobFormatException`（`Truncated`） |
| 5 | BlobCodec.java:decode | 20 字节合法头部，`keyLen=-1`、`payloadLen=0`、`crc32=0`（空内容的 CRC32 即 0，可通过 CRC 校验） | 期望抛受检的 `BlobFormatException`；实际抛 `StringIndexOutOfBoundsException: offset 20, count -1, length 20` | 未校验 `keyLen` 非负就执行 `new String(input, 20, keyLen, UTF_8)`，泄漏运行时异常，违反保证 5 | 构造字符串前校验 `keyLen >= 0` 且 `20 + keyLen <= input.length`，非法时抛 `BlobFormatException` |
| 3 | BlobCodec.java:decode | `encode("abcd", {7,8,9})` 得 28 字节（含 1 字节 padding），把最后一字节 padding 改为 `0x01` 后 `decode` | 期望正常解码返回 `key="abcd"`、`payload={7,8,9}`；实际抛 `BlobFormatException`（`BadCrc`） | CRC 计算区间为 `[20, align4(20+keyLen+payloadLen))`，把 padding 算进 CRC，非 0 padding 的合法数据被判损坏，违反保证 3 | CRC 只覆盖 `[20, 20 + keyLen + payloadLen)`（key 与 payload 字节），不含 padding；`encode` 一侧同样只对该区间取 CRC |
| 4 | BlobStore.java:keys | `put("", {1})`、`put("😀", {2})` 后调用 `keys()`（U+E000 的 UTF-8 为 `EE 80 80`，U+1F600 为 `F0 9F 98 80`） | 期望按 UTF-8 字节序返回 `[U+E000, U+1F600]`；实际返回 `[U+1F600, U+E000]`（UTF-16 码元序，高代理项 0xD83D 排在 0xE000 前） | `Collections.sort(out)` 用的是 String 自然序（UTF-16 码元序），不是 UTF-8 字节序，违反保证 4 | 按 `key.getBytes(UTF_8)` 逐字节无符号比较的 `Comparator` 排序 |
| 6 | BlobStore.java:keyOf | `BlobRef ref = put("user:42", {9}); store.close(); ref.key()` | 期望返回 `"user:42"`；实际抛 `NullPointerException: Cannot invoke "java.util.List.get(int)" because "this.keyTable" is null` | `BlobRef` 只保存槽位 id，`close()` 把 `keyTable` 置 `null` 后 `keyOf(id)` 解引用空表，句柄未与 store 解耦，违反保证 6 | `BlobRef` 在 `put` 时保存 key 字符串本身，`key()` 直接返回该字段，不再回查 store 的表 |

## 三、为什么 test/blobcache/BlobCodecTest.java 没抓到

既有 14 个用例全部只走正常路径，每个缺陷都落在它的覆盖盲区里：

- 保证 1（尾部字节）：`BlobCodecTest` 里所有 `decode` 的输入都直接来自 `encode` 的精确输出，从未构造过「合法 blob 之后追加字节」的输入，`TrailingBytes` 这条错误码没有任何用例触及。
- 保证 2（长度上限）：用例中最大的 payload 是 `roundTripLargePayload` 的 4096 字节，从未构造 `keyLen + payloadLen` 超过 `MAX_BLOB`（64 MiB）的输入，`BadLength` 这条错误码没有任何用例触及。
- 保证 3（CRC 不含 padding）：`roundTripWithPadding` 只验证 `encode` 自己产生的全 0 padding 能往返解码，从未把 padding 字节改成非 0 再解码，因此「CRC 把 padding 算进去」这一缺陷在 encode/decode 两侧相互抵消、不可见。
- 保证 4（UTF-8 字节序）：`storeKeysSortedAscii` 只用 ASCII key（alpha/bravo/charlie/delta），ASCII 范围内 UTF-16 码元序与 UTF-8 字节序完全一致；用例没有增补平面字符（如 U+1F600）与高位 BMP 字符（如 U+E000）混排的 key，两种排序的分歧暴露不出来。
- 保证 5（一律受检异常）：`expectFormat` 只喂「合法 blob 改 1~2 字节」的输入（坏 magic、坏 version、坏 CRC、短于 20 字节的头），从未把头部里的 `keyLen`/`payloadLen` 改成负值或超过实际输入长度的值，因此分配数组与 `arraycopy` 之前的边界缺失从未被触发。
- 保证 6（句柄解耦）：`refKeyBeforeClose` 只在 `close()` 之前调用 `ref.key()`，`close()` 之后对 `ref` 没有任何调用，`keyTable` 被置 `null` 的后果没有任何用例观察到。
