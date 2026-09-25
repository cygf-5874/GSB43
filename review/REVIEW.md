# 代码审查结论 · blobcache

> 按 `review/CHECKLIST.md` 的格式逐条填写；每条都可照抄复现（JDK 17，`bash scripts/build.sh` 后用 `out/` 下的类运行）。

## 一、保证判定

对 README「对外保证」的 6 条逐条给出判定：**违反** 或 **未违反**（二选一，不许留空）。

| 保证编号 | 判定 | 说明 |
| --- | --- | --- |
| 1 | 违反 | `decode` 从不比较输入长度与 blob 实际长度，尾部多余字节被静默忽略，`TrailingBytes` 在实现中从不抛出 |
| 2 | 违反 | `decode` 不校验 `keyLen + payloadLen <= MAX_BLOB`，超限声明照常解码，`BadLength` 只在 `encode` 出现 |
| 3 | 违反 | `encode`/`decode` 的 CRC 区间都算到 4 字节对齐后的 `bodyEnd`，把 padding 计入校验，非零 padding 的合法数据被判 BadCrc |
| 4 | 违反 | `BlobStore.keys()` 用 `Collections.sort`（String 自然序，UTF-16 码元序），不是 UTF-8 字节序 |
| 5 | 违反 | 负数 `payloadLen` 泄漏 `NegativeArraySizeException`，截断的 body 泄漏 `ArrayIndexOutOfBoundsException`，均非受检异常 |
| 6 | 违反 | `close()` 把 `keyTable` 置 null，`BlobRef.key()` 经 `keyOf` 回查该表，close 后调用抛 `NullPointerException` |

## 二、缺陷清单

| 保证编号 | 缺陷位置（文件:成员） | 触发输入 | 可观察证据 | 违反哪一条 | 正确做法 |
| --- | --- | --- | --- | --- | --- |
| 1 | BlobCodec.java:decode | `byte[] b = BlobCodec.encode("k", new byte[]{1})`（24 字节），末尾再拼 4 个 0 字节成 28 字节后调用 `BlobCodec.decode` | 期望抛 `BlobFormatException`（错误码 `TrailingBytes`）；实际正常返回 key="k"，多余 4 字节被静默忽略 | 保证 1 要求 decode 恰好用完输入、剩余字节必须抛 `TrailingBytes`，实现没有任何尾部长度检查 | 算出 `bodyEnd = align4(20+keyLen+payloadLen)` 后，若 `input.length > bodyEnd` 抛 `TrailingBytes` |
| 2 | BlobCodec.java:decode | 手工构造 20 字节头部：magic=0x424C4F42、version=1、keyLen=0、payloadLen=67108865（即 MAX_BLOB+1），CRC 按实现口径填好后调用 `decode` | 期望抛 `BlobFormatException`（错误码 `BadLength`）；实际正常返回 `payload().length == 67108865`，超过 MAX_BLOB=67108864 | 保证 2 要求 `keyLen + payloadLen` 不得超过 `MAX_BLOB`，decode 路径全程无此校验，`BadLength` 在解码中从不抛出 | decode 在解析头部后校验两长度非负且合计不超过 `MAX_BLOB`，超限抛 `BadLength` |
| 3 | BlobCodec.java:decode | `byte[] b = BlobCodec.encode("abcd", new byte[]{7,8,9})`（28 字节，末尾 1 字节为 padding），把 `b[27]` 改为 `0xAA` 后调用 `decode` | 期望正常解码返回 key="abcd"、payload={7,8,9}；实际抛 `BlobFormatException`（错误码 `BadCrc`） | 保证 3 规定 CRC 只覆盖 key 与 payload、不含 padding，padding 任意取值不得影响解码；实现的 CRC 区间是 `[20, align4(20+keyLen+payloadLen))`，把 padding 算了进去 | `encode` 与 `decode` 的 CRC 区间都改为 `[20, 20+keyLen+payloadLen)`，不覆盖 padding |
| 4 | BlobStore.java:keys | `store.put("𐀀", new byte[]{1})`（U+10000，UTF-8 为 F0 90 80 80）与 `store.put("￿", new byte[]{2})`（U+FFFF，UTF-8 为 EF BF BF），再调用 `store.keys()` | 期望按 UTF-8 字节序返回 `[￿, 𐀀]`（0xEF 排在 0xF0 前）；实际返回 `[𐀀, ￿]` | 保证 4 要求按 UTF-8 字节序排序；`Collections.sort(out)` 用的是 String 自然序（UTF-16 码元序），增补平面字符与 BMP 字符的相对顺序两者相反 | 用 `Comparator.comparing(k -> k.getBytes(StandardCharsets.UTF_8), Arrays::compare)` 之类的字节序比较器排序 |
| 5 | BlobCodec.java:decode | 手工构造 20 字节头部：magic=0x424C4F42、version=1、keyLen=0、payloadLen=-1，调用 `decode` | 期望抛受检的 `BlobFormatException`；实际泄漏运行时异常 `java.lang.NegativeArraySizeException`（`new byte[-1]`） | 保证 5 要求声明长度为负的非法输入一律抛受检异常，实现未校验 `payloadLen` 非负就直接分配数组 | 先校验 `keyLen`、`payloadLen` 均非负，为负抛 `BlobFormatException`（`BadLength`） |
| 5 | BlobCodec.java:decode | `byte[] b = BlobCodec.encode("k", new byte[]{1})`（24 字节），截成 22 字节后调用 `decode` | 期望抛受检的 `BlobFormatException`；实际泄漏运行时异常 `java.lang.ArrayIndexOutOfBoundsException`（CRC 区间超出输入实际长度） | 保证 5 要求截断输入抛受检异常，实现未校验 `input.length` 是否达到 `bodyEnd` 就做数组拷贝与 CRC | 在拷贝与 CRC 之前校验 `input.length >= align4(20+keyLen+payloadLen)`，不足抛 `BlobFormatException`（`Truncated`） |
| 6 | BlobStore.java:keyOf | `BlobStore.BlobRef ref = store.put("user:42", new byte[]{9}); store.close(); ref.key()` | 期望 `ref.key()` 返回 "user:42"；实际抛 `java.lang.NullPointerException`（`close()` 把 `keyTable` 置 null，`keyOf` 解引用空表） | 保证 6 要求 `BlobRef` 与 store 解耦、close 后 `ref.key()` 仍返回该次写入的 key，实现把 key 存在 store 的表里、句柄只存 id | `BlobRef` 在 `put` 时保存 key 字符串本身，`key()` 直接返回该副本，不再回查 `BlobStore` |

## 三、为什么 test/blobcache/BlobCodecTest.java 没抓到

既有 14 个用例全部使用 `encode` 自产的合法字节流或只在头部做局部改动的输入，覆盖盲区逐条对应如下：

- 保证 1（尾部多余字节）：所有 `decode` 用例的输入长度都恰好等于 `encode` 的输出长度，没有任何「blob 之后再拼字节」的用例；`expectFormat` 的 4 个异常用例（badMagic、truncatedHeader、badVersion、crcMismatch）输入长度也都不超过原 blob，尾部检查路径从未被执行。
- 保证 2（长度超限）：用例里最大的 payload 是 `roundTripLargePayload` 的 4096 字节，远低于 64 MiB 的 `MAX_BLOB`；没有手工构造声明长度超限头部的用例，`BadLength` 分支在解码路径上从未被触达。
- 保证 3（CRC 不含 padding）：唯一带 padding 的用例 `roundTripWithPadding` 用的是 `encode` 自产的全 0 padding，编解码两侧口径相同所以往返一致；`crcMismatchRejected` 改的是 payload 字节而不是 padding 字节，没有「非零 padding」的输入，CRC 区间多算 padding 这件事无法暴露。
- 保证 4（字节序排序）：`storeKeysSortedAscii` 只用 ASCII key，ASCII 范围内 UTF-16 码元序与 UTF-8 字节序完全一致，两种排序算法结论相同、无法区分；没有用增补平面字符（如 U+10000）做 key 的用例。
- 保证 5（一律受检异常）：异常用例只覆盖 badMagic、truncatedHeader、badVersion、crcMismatch 四种，全部在 `parseHeader` 阶段就被拦下；没有负数 `payloadLen`、声明长度超过输入实际长度的用例，执行流走不到 `new byte[payloadLen]` 和越界的 CRC 区间，运行时异常无从暴露。
- 保证 6（句柄解耦）：`refKeyBeforeClose` 只在 `close()` 之前调用 `ref.key()`，`finally` 里 `close()` 之后没有任何对 `ref` 的调用，close 后句柄失效的行为从未被断言。
