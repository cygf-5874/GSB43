import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * blobcache 审查题的固定验收入口。
 *
 * <p>只校验 review/REVIEW.md 的交付格式、可判定性，以及「保证判定 ↔ 缺陷清单」的自洽与对
 * 6 条对外保证的覆盖；结论是否正确由人按 README 逐条复核。
 *
 * <p>用法：java -cp out Checker [-list] [--only <组名>[,<组名>...]]
 */
public final class Checker {

    /** 不可修改文件的 SHA-256 基线：<相对路径>=<sha256>。 */
    private static final String[] BASELINE = {
        "src/blobcache/BlobFormatException.java=c353c483672aec444d986deb18c8afea5bc9e2c30541960bddb0c9aa47b4d22b",
        "src/blobcache/BlobHeader.java=de17495225b950279823bfda92b6737937c09153089fcd3df9d7fd28b3daacae",
        "src/blobcache/BlobCodec.java=f054dd2e5ffcd780943194c74c99918bd4213831cdc08a423c2d7cbe46306419",
        "src/blobcache/BlobStore.java=f15a4e33dd4fa944ada9451ac648ea58e76d13b6b3540639824af84304f4fea6",
        "test/blobcache/BlobCodecTest.java=98a139dd8d4d13e781a528db52f9adb47e3e9d92526aec7683263e4148ae83f4",
        "scripts/build.sh=ce68a7835188f03e4957161daf229337dfe9573a357e57c7b77b2595b604b201",
        "scripts/test.sh=24702dba86c25aefcb7520b2adc86429ee9f9369ebf85effe56f6280d2b4d7b4",
        "scripts/check.sh=787fce3fa7c5f7f2390b81b57144851b7da5cd5c62045a7e792b0656b7ba549d",
    };

    /** src/blobcache 下已有的类名（用于校验「缺陷位置」指向真实位置）。 */
    private static final String[] SOURCE_BASES = {
        "BlobCodec", "BlobHeader", "BlobStore", "BlobFormatException"
    };

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private interface Body {
        /** 返回 null 表示 PASS，否则返回失败原因。 */
        String run() throws Exception;
    }

    private static final class Scenario {
        final String group;
        final String name;
        final Body body;

        Scenario(String name, Body body) {
            this.name = name;
            this.body = body;
            int slash = name.indexOf('/');
            this.group = slash < 0 ? name : name.substring(0, slash);
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> only = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) only.add(g);
                }
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        Path root = Paths.get("").toAbsolutePath().normalize();
        Context ctx = new Context(root);
        List<Scenario> all = scenarios(ctx);

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        int total = 0;
        int pass = 0;
        for (Scenario s : all) {
            if (!only.isEmpty() && !only.contains(s.group)) continue;
            total++;
            String err;
            try {
                err = s.body.run();
            } catch (Throwable t) {
                err = "检查过程抛异常：" + t;
            }
            if (err == null) {
                pass++;
                System.out.println("PASS " + s.name);
            } else {
                System.out.println("FAIL " + s.name + "  " + err);
            }
        }
        if (total == 0) {
            System.out.println("没有匹配的场景（--only " + only + "）");
            System.exit(2);
        }
        System.out.println("结果：通过 " + pass + "/" + total);
        if (pass != total) {
            System.exit(1);
        }
    }

    private static List<Scenario> scenarios(Context ctx) {
        List<Scenario> out = new ArrayList<>();

        // ---------------- format ----------------
        out.add(new Scenario("format/review-exists", () -> {
            if (ctx.review == null) {
                return "期望=review/REVIEW.md 存在且含「保证判定」与「缺陷清单」两节 实际=文件不存在";
            }
            if (!ctx.review.contains("缺陷")) {
                return "期望=REVIEW.md 含「缺陷清单」一节 实际=未找到";
            }
            if (!ctx.review.contains("判定")) {
                return "期望=REVIEW.md 含「保证判定」一节 实际=未找到";
            }
            if (ctx.review.trim().length() < 80) {
                return "期望=REVIEW.md 是填好的结论 实际=去空白后仅 " + ctx.review.trim().length() + " 字";
            }
            return null;
        }));

        out.add(new Scenario("format/table-shape", () -> {
            if (ctx.review == null) return "期望=review/REVIEW.md 存在 实际=文件不存在";
            if (ctx.defectRows.isEmpty()) return "期望=缺陷清单至少 1 条数据行 实际=0 条";
            for (int i = 0; i < ctx.defectRows.size(); i++) {
                String[] row = ctx.defectRows.get(i);
                if (row.length != 6) {
                    return "期望=缺陷清单每行 6 列 实际=第 " + (i + 1) + " 行 " + row.length + " 列";
                }
                for (int c = 0; c < 6; c++) {
                    if (row[c].isEmpty()) {
                        return "期望=缺陷清单第 " + (i + 1) + " 行第 " + (c + 1) + " 列非空 实际=空";
                    }
                }
            }
            return null;
        }));

        out.add(new Scenario("format/no-vague", () -> {
            String text = ctx.review == null ? "" : ctx.review;
            String[] banned = {"可能有风险", "建议关注", "看起来", "或许", "也许", "大概",
                    "疑似", "需要注意", "可能存在问题", "有待", "不太对", "可能有问题"};
            for (String w : banned) {
                if (text.contains(w)) {
                    return "期望=不出现无法核对的措辞 实际=出现「" + w + "」";
                }
            }
            return null;
        }));

        // ---------------- evidence ----------------
        out.add(new Scenario("evidence/location", () -> eachRow(ctx, (n, row) -> {
            if (row.length < 2) return "期望=第 " + n + " 行有「缺陷位置」列 实际=列数不足";
            String loc = row[1];
            String base = null;
            for (String b : SOURCE_BASES) {
                if (loc.contains(b)) {
                    base = b;
                    break;
                }
            }
            if (base == null) {
                return "期望=第 " + n + " 行「缺陷位置」指向 src/blobcache 下的类 实际=「" + loc + "」未提到已有类";
            }
            int colon = Math.max(loc.lastIndexOf(':'), loc.lastIndexOf('：'));
            if (colon < 0 || colon == loc.length() - 1) {
                return "期望=「缺陷位置」写成 文件:成员 实际=「" + loc + "」";
            }
            String member = loc.substring(colon + 1).trim();
            String src = ctx.source.get(base);
            if (src == null || !src.contains(member)) {
                return "期望=成员「" + member + "」在 " + base + ".java 中真实存在 实际=未找到";
            }
            return null;
        })));

        out.add(new Scenario("evidence/trigger", () -> eachRow(ctx, (n, row) -> {
            if (row.length < 3) return "期望=第 " + n + " 行有「触发输入」列 实际=列数不足";
            String t = row[2];
            if (t.length() < 8) {
                return "期望=第 " + n + " 行「触发输入」给出可照抄的具体输入 实际=「" + t + "」过短";
            }
            for (String w : new String[] {"TODO", "待补", "待定", "略", "……"}) {
                if (t.contains(w)) {
                    return "期望=「触发输入」是具体输入 实际=出现占位词「" + w + "」";
                }
            }
            return null;
        })));

        out.add(new Scenario("evidence/observable", () -> eachRow(ctx, (n, row) -> {
            if (row.length < 4) return "期望=第 " + n + " 行有「可观察证据」列 实际=列数不足";
            String t = row[3];
            if (t.length() < 8) {
                return "期望=第 " + n + " 行「可观察证据」给出具体现象 实际=「" + t + "」过短";
            }
            boolean concrete = t.matches(".*\\d.*") || t.contains("Exception") || t.contains("Error")
                    || t.contains("抛出") || t.contains("返回") || t.contains("错误码") || t.contains("null");
            if (!concrete) {
                return "期望=「可观察证据」是可判定的现象（异常类型 / 错误码 / 返回值 / 具体数值） 实际=「" + t + "」";
            }
            return null;
        })));

        out.add(new Scenario("evidence/guarantee-ids", () -> eachRow(ctx, (n, row) -> {
            if (row.length < 1 || row[0].isEmpty()) {
                return "期望=第 " + n + " 行「保证编号」非空 实际=空";
            }
            Integer id = parseInt(row[0]);
            if (id == null || id < 1 || id > 6) {
                return "期望=第 " + n + " 行「保证编号」是 1~6 的整数 实际=「" + row[0] + "」";
            }
            return null;
        })));

        // ---------------- coverage ----------------
        for (int g = 1; g <= 6; g++) {
            final int gg = g;
            out.add(new Scenario("coverage/G" + g, () -> {
                if (ctx.review == null) return "期望=review/REVIEW.md 存在 实际=文件不存在";
                String verdict = ctx.verdicts.get(Integer.valueOf(gg));
                List<String[]> rows = ctx.rowsFor(gg);
                if (verdict == null || "INVALID".equals(verdict)) {
                    String actual = (verdict == null) ? "未给出判定" : "判定无法识别（只能是「违反」或「未违反」）";
                    return "期望=对第 " + gg + " 条保证给出明确判定 实际=" + actual;
                }
                if ("VIOLATED".equals(verdict)) {
                    if (rows.isEmpty()) {
                        return "期望=第 " + gg + " 条判为「违反」时，缺陷清单里有保证编号 " + gg + " 的条目 实际=没有";
                    }
                    return null;
                }
                if (!rows.isEmpty()) {
                    return "期望=第 " + gg + " 条判为「未违反」时，缺陷清单里不出现该编号 实际=出现 " + rows.size() + " 条";
                }
                return null;
            }));
        }

        // ---------------- notes ----------------
        out.add(new Scenario("notes/why-tests-missed", () -> {
            if (ctx.review == null) return "期望=review/REVIEW.md 存在 实际=文件不存在";
            String notes = ctx.notesSection == null ? "" : ctx.notesSection;
            String squeezed = notes.replaceAll("\\s+", "");
            if (squeezed.isEmpty()) {
                return "期望=说明「为什么既有用例没抓到」 实际=该节为空";
            }
            if (squeezed.length() < 80) {
                return "期望=该节逐条说明既有用例的覆盖盲区 实际=去空白后仅 " + squeezed.length() + " 字";
            }
            if (!notes.contains("BlobCodecTest") && !notes.contains("test/")) {
                return "期望=该节指向既有用例 test/blobcache/BlobCodecTest.java 实际=未提到";
            }
            return null;
        }));

        // ---------------- integrity ----------------
        out.add(new Scenario("integrity/immutable-files", () -> {
            int checked = 0;
            for (String entry : BASELINE) {
                if (entry == null) continue;
                String e = entry.trim();
                if (e.isEmpty() || e.startsWith("@")) continue;
                int eq = e.indexOf('=');
                if (eq < 0) continue;
                String rel = e.substring(0, eq).trim();
                String want = e.substring(eq + 1).trim();
                checked++;
                Path p = ctx.root.resolve(rel);
                if (!Files.exists(p)) {
                    return "期望=" + rel + " 存在 实际=缺失";
                }
                if (!sha256(p).equals(want)) {
                    return "期望=" + rel + " 保持原样 实际=SHA-256 与基线不一致";
                }
            }
            if (checked == 0) {
                return "期望=基线已内置 实际=基线为空（固定件未正确生成）";
            }
            return null;
        }));

        return out;
    }

    private static String eachRow(Context ctx, BiFunction<Integer, String[], String> validator) {
        if (ctx.review == null) return "期望=review/REVIEW.md 存在 实际=文件不存在";
        if (ctx.defectRows.isEmpty()) return "期望=缺陷清单至少 1 条数据行 实际=0 条";
        for (int i = 0; i < ctx.defectRows.size(); i++) {
            String err = validator.apply(Integer.valueOf(i + 1), ctx.defectRows.get(i));
            if (err != null) return err;
        }
        return null;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 计算文件内容的 SHA-256；先去掉 \r，使结果与换行风格无关。 */
    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] raw = Files.readAllBytes(p);
        byte[] normalized = new byte[raw.length];
        int n = 0;
        for (byte b : raw) {
            if (b != '\r') {
                normalized[n++] = b;
            }
        }
        md.update(normalized, 0, n);
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    /** 从一段 markdown 里取出表格数据行（去掉表头与分隔行）。 */
    private static List<String[]> parseRows(String section) {
        List<String[]> rows = new ArrayList<>();
        if (section == null) return rows;
        for (String line : section.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|")) continue;
            String[] parts = t.split("\\|", -1);
            List<String> cells = new ArrayList<>();
            for (int i = 1; i < parts.length - 1; i++) {
                cells.add(parts[i].trim());
            }
            if (cells.isEmpty()) continue;
            boolean allEmpty = true;
            boolean allSep = true;
            for (String c : cells) {
                if (!c.isEmpty()) allEmpty = false;
                if (!c.matches("[-: ]*")) allSep = false;
            }
            if (allEmpty || allSep) continue;
            String joined = String.join("", cells);
            if (joined.contains("保证编号") || joined.contains("缺陷位置")
                    || joined.contains("可观察证据") || joined.contains("正确做法")
                    || joined.contains("触发输入")) {
                continue;
            }
            rows.add(cells.toArray(new String[0]));
        }
        return rows;
    }

    /** 解析 REVIEW.md 得到判定表、缺陷清单与说明节。 */
    private static final class Context {

        final Path root;
        final String review;
        final List<String[]> defectRows;
        final Map<Integer, String> verdicts = new LinkedHashMap<>();
        final Map<String, String> source = new LinkedHashMap<>();
        String notesSection = null;

        Context(Path root) {
            this.root = root;
            String text = null;
            Path reviewPath = root.resolve("review/REVIEW.md");
            try {
                if (Files.exists(reviewPath)) {
                    text = new String(Files.readAllBytes(reviewPath), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                text = null;
            }
            this.review = text;

            StringBuilder defect = new StringBuilder();
            StringBuilder verdict = new StringBuilder();
            StringBuilder notes = new StringBuilder();
            int current = 0;
            if (text != null) {
                for (String line : text.split("\r\n|\r|\n")) {
                    String t = line.trim();
                    if (t.startsWith("#")) {
                        if (t.contains("缺陷")) {
                            current = 1;
                        } else if (t.contains("判定")) {
                            current = 2;
                        } else if (t.contains("没抓到") || t.contains("为什么")) {
                            current = 3;
                        } else {
                            current = 0;
                        }
                    }
                    if (current == 1) {
                        defect.append(line).append('\n');
                    } else if (current == 2) {
                        verdict.append(line).append('\n');
                    } else if (current == 3) {
                        notes.append(line).append('\n');
                    }
                }
                this.notesSection = notes.toString();
            }

            this.defectRows = parseRows(defect.toString());
            for (String[] row : parseRows(verdict.toString())) {
                Integer id = row.length >= 1 ? parseInt(row[0]) : null;
                if (id == null || id.intValue() < 1 || id.intValue() > 6) continue;
                String cell = row.length >= 2 ? row[1] : "";
                String v;
                if (cell.contains("未违反") || cell.contains("不违反") || "无".equals(cell) || "否".equals(cell)) {
                    v = "OK";
                } else if (cell.contains("违反")) {
                    v = "VIOLATED";
                } else {
                    v = "INVALID";
                }
                this.verdicts.put(id, v);
            }

            for (String base : SOURCE_BASES) {
                Path p = root.resolve("src/blobcache/" + base + ".java");
                try {
                    if (Files.exists(p)) {
                        source.put(base, new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    // 读不到就当不存在，由 evidence/location 报出来
                }
            }
        }

        List<String[]> rowsFor(int guarantee) {
            List<String[]> out = new ArrayList<>();
            for (String[] row : defectRows) {
                Integer id = row.length >= 1 ? parseInt(row[0]) : null;
                if (id != null && id.intValue() == guarantee) {
                    out.add(row);
                }
            }
            return out;
        }
    }
}
