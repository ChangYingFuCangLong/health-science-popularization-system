package com.healthscience.rag.service.impl;

import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知分块器（M12/B-19，自研——论文「设计与实现」的正分量）。
 *
 * <p><b>三条解析路径</b>（按文件类型选择）：
 * <ol>
 *   <li><b>Markdown</b>：原生标题层级（{@code #{1,6}}）切分，章节路径 = 各级标题「 &gt; 」相连；</li>
 *   <li><b>html / docx</b>：上游先把正文转成 Tika XHTML（docx 的 Word 标题样式会映射为 h1-h6），
 *       本器 DOM 遍历 h1-h6/p 重建层级；</li>
 *   <li><b>pdf / txt</b>：无可靠结构信号 → 整篇作为单一「正文」章节。</li>
 * </ol>
 * 章节内部超过窗口（500 字）时按<b>段落优先</b>滑窗切分，重叠 80 字 —— 与 V1 语料
 * 的 500 字上限口径一致；段落优先避免把一句话拦腰截断。
 *
 * <p>分块只负责「文本 → 带章节路径的草稿」，向量化与落库在流水线（Ingestor）完成。
 */
@Component
public class DocumentChunker {

    /** 章节内滑窗长度（字符） */
    static final int WINDOW = 500;
    /** 相邻块重叠字符数：保证跨块句子在下一块仍完整出现，检索两侧都能命中 */
    static final int OVERLAP = 80;
    /** 单段超窗时的硬切步长（窗 - 重叠） */
    private static final int STEP = WINDOW - OVERLAP;
    /** 章节内末尾碎块并入上一块的最小块长 */
    private static final int MIN_TAIL_CHARS = 30;

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*$");

    /** 分块草稿：index 为全文档连续序号（从 0），section 为章节路径 */
    public record ChunkDraft(int index, String section, String content) {
    }

    private record Section(String path, StringBuilder text) {
    }

    /** 统一入口：rawText 为原文；fileType 取 txt/md/html/pdf/docx（html 含 htm） */
    public List<ChunkDraft> chunk(String rawText, String fileType) {
        return chunk(rawText, fileType, false);
    }

    /**
     * @param splitNumberedSections 无标题结构的纯文本（txt/pdf）是否按「一、二、三」中文小节标号切分。
     *
     * <p>个人资料多为体检/随访/饮食记录这类短文本，一篇里含多个小节、总长不足一个窗口，
     * 整篇会压成 1 块，块向量变成多主题的平均值，具体数值检不回来
     * （实测：236 字的《饮水与咖啡因》被"心脏康复运动"块挤掉，见测试报告 §24.4 D6）。
     * 按小节切开后，「一、饮水量 每日 1800 毫升」可独立成块。
     *
     * <p>全局库传 false：保持既有分块口径不变，避免影响四策略消融实验的可比性。
     */
    public List<ChunkDraft> chunk(String rawText, String fileType, boolean splitNumberedSections) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        List<Section> sections = switch (fileType == null ? "" : fileType.toLowerCase()) {
            case "md" -> markdownSections(normalizeLineBreaks(rawText));
            case "html", "htm", "docx" -> {
                List<Section> parsed = xhtmlSections(rawText);
                // pdf 经 Tika 转 XHTML 后通常没有 h1-h6（原文无标题标签），整篇落成单个
                // 「正文」节 —— 个人库对这种无结构文本再按中文小节细切一次
                yield splitNumberedSections ? refinePlainSection(parsed) : parsed;
            }
            default -> splitNumberedSections
                    ? numberedSections(normalizeLineBreaks(rawText))
                    : List.of(new Section("正文", new StringBuilder(normalizeLineBreaks(rawText))));
        };
        return splitSections(sections);
    }

    // ---------- 路径 1：Markdown 标题层级（线性单遍，天然保持文档顺序） ----------

    private List<Section> markdownSections(String md) {
        List<Section> out = new ArrayList<>();
        String[] paths = new String[7];           // paths[level] = 该级最近一个标题
        StringBuilder buf = new StringBuilder();
        String currentPath = "正文";

        for (String line : md.split("\n", -1)) {
            Matcher m = MD_HEADING.matcher(line);
            if (m.matches()) {
                flush(out, currentPath, buf);
                int level = m.group(1).length();
                String title = m.group(2).isBlank() ? "未命名" : m.group(2).trim();
                paths[level] = title;
                for (int i = level + 1; i <= 6; i++) {
                    paths[i] = null;              // 降级回父级后清空更深层级
                }
                StringBuilder path = new StringBuilder();
                for (int i = 1; i <= level; i++) {
                    if (paths[i] != null) {
                        if (!path.isEmpty()) {
                            path.append(" > ");
                        }
                        path.append(paths[i]);
                    }
                }
                currentPath = path.isEmpty() ? "正文" : path.toString();
            } else {
                buf.append(line).append('\n');
            }
        }
        flush(out, currentPath, buf);
        return out;
    }

    /** 收尾当前章节：内容非空才产出，避免空章节 */
    private static void flush(List<Section> out, String path, StringBuilder buf) {
        if (!buf.toString().isBlank()) {
            out.add(new Section(path, new StringBuilder(buf.toString())));
        }
        buf.setLength(0);
    }

    // ---------- 路径 1b：纯文本的中文小节标号（仅个人库启用） ----------

    /**
     * 行首「一、」「二、」…或「1、」（不含「1.5」这类小数，要求标号后紧跟顿号/句点）。
     * 只认行首并带标号，避免把正文里的数字误当成分节。
     */
    private static final Pattern CN_SECTION = Pattern.compile(
            "^\\s*(?:[一二三四五六七八九十]{1,3}|\\d{1,2})\\s*[、．]\\s*(\\S.*)$");

    /**
     * 对「解析出来只有单个正文节」的文本（典型是 PDF）再做一次小节切分；
     * 已解析出多节（md / docx / 带标签的 html）则原样返回，不重复切。
     */
    private List<Section> refinePlainSection(List<Section> parsed) {
        if (parsed.size() != 1 || !"正文".equals(parsed.get(0).path())) {
            return parsed;
        }
        String text = parsed.get(0).text().toString();
        List<Section> numbered = numberedSections(text);
        // 没命中任何小节标号时 numberedSections 会退回单节，此时等价于原结果
        return numbered;
    }

    /**
     * 按中文小节标号切分纯文本。没有命中任何标号时，退化为整篇一个「正文」章节，
     * 与旧行为一致。小节名取标号后的标题（截断 24 字），空标题保留上一节名。
     */
    private List<Section> numberedSections(String text) {
        List<Section> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String current = "正文";
        for (String line : text.split("\n", -1)) {
            Matcher m = CN_SECTION.matcher(line);
            if (m.matches()) {
                flush(out, current, buf);
                String title = m.group(1).trim();
                if (!title.isEmpty()) {
                    current = title.length() > 24 ? title.substring(0, 24) : title;
                }
            }
            buf.append(line).append('\n');
        }
        flush(out, current, buf);
        return out;
    }

    // ---------- 路径 2：Tika XHTML（html / docx） ----------

    private List<Section> xhtmlSections(String xhtml) {
        List<Section> sections = new ArrayList<>();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            var dom = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xhtml.getBytes(StandardCharsets.UTF_8)));
            Deque<Section> stack = new ArrayDeque<>();
            walk(dom.getDocumentElement(), stack, sections);
            popAll(stack, sections);
        } catch (Exception e) {
            // XHTML 解析失败退回整篇窗口切分，不让上传流水线中断
            return List.of(new Section("正文", new StringBuilder(stripTags(xhtml))));
        }
        return sections;
    }

    /** 按文档顺序遍历：h1-h6 压标题栈，p/pre 收集文本，其余元素递归 */
    private void walk(Node node, Deque<Section> stack, List<Section> out) {
        String name = node.getNodeName() == null ? "" : node.getNodeName().toLowerCase();
        if (name.matches("h[1-6]")) {
            int level = name.charAt(1) - '0';
            popToLevel(stack, out, level);
            String title = node.getTextContent().trim();
            if (title.isEmpty()) {
                title = "未命名";
            }
            String parent = currentPath(stack);
            String path = parent.isEmpty() ? title : parent + " > " + title;
            stack.push(new Section(path, new StringBuilder()));
            return;
        }
        if ("p".equals(name) || "pre".equals(name)) {
            String text = node.getTextContent();
            if (text != null && !text.isBlank()) {
                if (stack.isEmpty()) {
                    stack.push(new Section("正文", new StringBuilder()));
                }
                stack.peek().text().append(text.strip()).append("\n\n");
            }
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                walk(child, stack, out);
            }
        }
    }

    /**
     * 结束所有层级 ≥ level 的已打开章节，按<b>文档顺序</b>追加。
     * 栈顶是最深章节，直接 pop 会得到逆序 —— 批量收集后反转再落序。
     * 进入更深标题（level 更大）时不弹出任何章节，父级保持打开。
     */
    private void popToLevel(Deque<Section> stack, List<Section> out, int level) {
        List<Section> popped = new ArrayList<>();
        while (!stack.isEmpty() && depthOf(stack.peek().path()) >= level) {
            popped.add(stack.pop());
        }
        Collections.reverse(popped);
        for (Section s : popped) {
            if (!s.text().toString().isBlank()) {
                out.add(s);
            }
        }
    }

    private void popAll(Deque<Section> stack, List<Section> out) {
        popToLevel(stack, out, 1);
    }

    private int depthOf(String path) {
        return (int) (path.chars().filter(c -> c == '>').count() + 1);
    }

    private String currentPath(Deque<Section> stack) {
        return stack.isEmpty() ? "" : stack.peek().path();
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ");
    }

    // ---------- 章节内切分：段落优先滑窗 ----------

    private List<ChunkDraft> splitSections(List<Section> sections) {
        List<ChunkDraft> drafts = new ArrayList<>();
        String carried = "";   // 上一个块的尾部重叠，跨章节延续，保证上下文连续
        for (Section section : sections) {
            String body = section.text().toString().strip();
            if (body.isEmpty()) {
                continue;
            }
            List<String> pieces = windowSplit(body);
            for (int i = 0; i < pieces.size(); i++) {
                String content = i == 0 ? (carried.isEmpty() ? pieces.get(i) : carried + "\n" + pieces.get(i)).strip() : pieces.get(i);
                if (!content.isBlank()) {
                    drafts.add(new ChunkDraft(drafts.size(), section.path(), content));
                }
            }
            carried = tailForOverlap(pieces.get(pieces.size() - 1));
        }
        return drafts;
    }

    /**
     * 段落优先滑窗：按空行分段，逐段并入当前块；超窗即出块，新块以「上一块尾部 OVERLAP 字」开头。
     * 单段超窗硬切（步长 = 窗 - 重叠）。尾部不足 MIN_TAIL_CHARS 并入前块，避免碎块。
     */
    private List<String> windowSplit(String body) {
        List<String> out = new ArrayList<>();
        for (String paragraph : body.split("\\n\\s*\\n")) {
            String para = paragraph.strip();
            if (para.isEmpty()) {
                continue;
            }
            if (para.length() > WINDOW) {
                // 单段超窗：硬切，块间保留 OVERLAP
                for (int i = 0; i < para.length(); i += STEP) {
                    out.add(para.substring(i, Math.min(para.length(), i + WINDOW)));
                    if (i + WINDOW >= para.length()) {
                        break;
                    }
                }
                continue;
            }
            if (out.isEmpty()) {
                out.add(para);
                continue;
            }
            String last = out.get(out.size() - 1);
            String merged = last + "\n" + para;
            if (merged.length() > WINDOW) {
                out.add(tailForOverlap(last) + para);
            } else {
                out.set(out.size() - 1, merged);
            }
        }
        if (out.size() >= 2 && out.get(out.size() - 1).length() < MIN_TAIL_CHARS) {
            String tail = out.remove(out.size() - 1);
            out.set(out.size() - 1, out.get(out.size() - 1) + "\n" + tail);
        }
        return out;
    }

    private String tailForOverlap(String block) {
        String stripped = block.strip();
        return stripped.length() <= OVERLAP ? stripped : "…" + stripped.substring(stripped.length() - OVERLAP);
    }

    private static String normalizeLineBreaks(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }
}
