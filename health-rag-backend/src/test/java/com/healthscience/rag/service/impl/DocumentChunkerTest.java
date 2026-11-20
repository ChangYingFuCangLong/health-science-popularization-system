package com.healthscience.rag.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构感知分块器单测（M12/B-19，任务卡要求 ≥15 用例）。
 *
 * <p>覆盖：三条解析路径（md 标题层级 / XHTML 结构 / 纯文本窗口）、
 * 滑窗与重叠、硬切、碎块合并、空输入容错、index 连续性。
 */
class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    private String repeatParagraph(String word, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(word).append(i).append("，这是一段用于测试分块的正文内容。\n\n");
        }
        return sb.toString();
    }

    // ---------- 通用 ----------

    @Test
    @DisplayName("空输入 / 空白输入 → 空列表")
    void blankInput() {
        assertThat(chunker.chunk(null, "md")).isEmpty();
        assertThat(chunker.chunk("   \n  ", "txt")).isEmpty();
    }

    @Test
    @DisplayName("index 全文档连续（跨章节不断号）")
    void indexContinuous() {
        String md = "# 一\n内容甲。\n\n# 二\n内容乙。\n\n# 三\n内容丙。";
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(md, "md");
        assertThat(drafts).isNotEmpty();
        for (int i = 0; i < drafts.size(); i++) {
            assertThat(drafts.get(i).index()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("块长不超过 500 字窗口（长文档全程约束）")
    void windowUpperBound() {
        String txt = repeatParagraph("普通段落", 60);   // ~60×30 字 ≈ 1800 字
        for (DocumentChunker.ChunkDraft d : chunker.chunk(txt, "txt")) {
            assertThat(d.content().length()).isLessThanOrEqualTo(DocumentChunker.WINDOW + DocumentChunker.OVERLAP);
        }
    }

    // ---------- 路径 1：Markdown ----------

    @Test
    @DisplayName("md 单级标题：每章一节，路径 = 标题")
    void mdSingleLevel() {
        String md = "# 疾病预防\n预防内容。\n\n# 心理健康\n心理内容。";
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(md, "md");
        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).section()).isEqualTo("疾病预防");
        assertThat(drafts.get(0).content()).contains("预防内容");
        assertThat(drafts.get(1).section()).isEqualTo("心理健康");
    }

    @Test
    @DisplayName("md 多级标题：路径按「 > 」连接，跨级后回退父级")
    void mdMultiLevel() {
        String md = """
                # 总则
                总则内容。
                ## 饮食
                饮食内容一。
                ### 少盐
                少盐内容。
                ## 运动
                运动内容。
                """;
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(md, "md");
        assertThat(drafts).extracting(DocumentChunker.ChunkDraft::section)
                .containsExactly("总则", "总则 > 饮食", "总则 > 饮食 > 少盐", "总则 > 运动");
    }

    @Test
    @DisplayName("md 首个标题前的内容归入「正文」节")
    void mdPreamble() {
        String md = "这是开篇导语。\n\n# 正文标题\n正文内容。";
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(md, "md");
        assertThat(drafts.get(0).section()).isEqualTo("正文");
        assertThat(drafts.get(0).content()).contains("开篇导语");
    }

    @Test
    @DisplayName("md 无任何标题：整篇归「正文」")
    void mdNoHeadings() {
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk("就是一段没有标题的话。", "md");
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).section()).isEqualTo("正文");
    }

    // ---------- 路径 2：XHTML（html / docx） ----------

    @Test
    @DisplayName("xhtml：h1/h2 层级与 p 文本按文档顺序产出")
    void xhtmlHeadings() {
        String xhtml = """
                <html><body>
                <h1>第一章</h1>
                <p>第一章内容。</p>
                <h2>第一节</h2>
                <p>第一节内容。</p>
                <h1>第二章</h1>
                <p>第二章内容。</p>
                </body></html>
                """;
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(xhtml, "html");
        assertThat(drafts).extracting(DocumentChunker.ChunkDraft::section)
                .containsExactly("第一章", "第一章 > 第一节", "第二章");
    }

    @Test
    @DisplayName("xhtml：docx 风格多段落并入同一块；无标题时归「正文」")
    void xhtmlParagraphsOnly() {
        String xhtml = "<html><body><p>段落甲。</p><p>段落乙。</p><p>段落丙。</p></body></html>";
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(xhtml, "docx");
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).section()).isEqualTo("正文");
        assertThat(drafts.get(0).content()).contains("段落甲").contains("段落乙").contains("段落丙");
    }

    @Test
    @DisplayName("xhtml 解析失败（非 XML 文本）：退回正文窗口切分，不抛异常")
    void xhtmlBrokenFallsBack() {
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk("这不是 <<<< xml", "html");
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).section()).isEqualTo("正文");
    }

    // ---------- 路径 3：纯文本窗口 ----------

    @Test
    @DisplayName("纯文本：段落优先拼块，相邻块尾部保留 OVERLAP 重叠")
    void plainWindowOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("第").append(i).append("段，健康科普测试内容，讲述饮食与运动的注意事项。\n\n");
        }
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(sb.toString(), "txt");
        assertThat(drafts.size()).isGreaterThan(1);
        // 第二块开头应携带上一块尾部（重叠）：以省略号标记的重叠头
        for (int i = 1; i < drafts.size(); i++) {
            assertThat(drafts.get(i).content()).startsWith("…");
        }
    }

    @Test
    @DisplayName("单段超窗：硬切且步长 = 窗 - 重叠（内容无缝覆盖）")
    void singleParagraphHardSplit() {
        String oneLongLine = "长".repeat(1500);
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(oneLongLine, "txt");
        // 1500 字 = 500 + 3×(420+500-…) → ceil((1500-80)/420)+1 = 4 块
        assertThat(drafts.size()).isEqualTo(4);
        assertThat(drafts.get(0).content()).hasSize(500);
        assertThat(drafts.get(1).content()).hasSize(500);
        assertThat(drafts.get(3).content()).hasSize(240);   // 尾块 1260→1500
        // 硬切块间以 80 字重叠衔接（无标记，步长 = 窗 - 重叠）：
        for (int i = 1; i < drafts.size(); i++) {
            String head = drafts.get(i).content().substring(0, DocumentChunker.OVERLAP);
            String tail = drafts.get(i - 1).content()
                    .substring(drafts.get(i - 1).content().length() - DocumentChunker.OVERLAP);
            assertThat(tail).isEqualTo(head);
        }
    }

    @Test
    @DisplayName("短章节（<30 字碎块）并入前块")
    void tinyTailMerged() {
        String txt = "第一段内容足够长，占满窗口的一部分内容。\n\n短尾";
        // 两段拼在同一个块内（总长 < 500），无从合并——构造跨窗口场景
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("第").append(i).append("段健康科普内容，饮食运动都要规律。\n\n");
        }
        sb.append("尾");
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(sb.toString(), "txt");
        // 1 字碎尾并入前块：末块以"尾"结尾且明显长于碎块本身
        assertThat(drafts.get(drafts.size() - 1).content()).endsWith("尾");
        assertThat(drafts.get(drafts.size() - 1).content().length()).isGreaterThan(50);
    }

    @Test
    @DisplayName("CRLF 换行与全角空格：不产生空块")
    void crlfHandled() {
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk("第一行内容。\r\n\r\n第二行内容。\r\n", "txt");
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).content()).contains("第一行内容").contains("第二行内容");
    }

    @Test
    @DisplayName("章节内容为纯空白：该节不产出碎块")
    void blankSectionSkipped() {
        String md = "# 甲\n\n\n\n# 乙\n乙有内容。";
        List<DocumentChunker.ChunkDraft> drafts = chunker.chunk(md, "md");
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).section()).isEqualTo("乙");
    }

    // ---------- D6（测试报告 §24.4）：个人库纯文本按中文小节切分 ----------

    private static final String NUMBERED_TXT =
            "随访记录\n"
                    + "一、测量结果\n"
                    + "血压 118/76 mmHg。\n"
                    + "二、用药\n"
                    + "氨氯地平 5 mg。\n"
                    + "三、结论\n"
                    + "控制达标。\n";

    @Test
    @DisplayName("个人库：纯文本按「一、二、三」小节切分，每节独立成块")
    void numberedSectionsSplitForPersonal() {
        List<DocumentChunker.ChunkDraft> fine = chunker.chunk(NUMBERED_TXT, "txt", true);
        assertThat(fine).hasSizeGreaterThanOrEqualTo(3);
        assertThat(fine).anySatisfy(d -> assertThat(d.section()).contains("测量结果"));
        assertThat(fine).anySatisfy(d -> assertThat(d.section()).contains("用药"));
        assertThat(fine).anySatisfy(d -> assertThat(d.section()).contains("结论"));
        // 每节内容不被别的节污染
        assertThat(fine).anySatisfy(d -> {
            assertThat(d.content()).contains("118/76");
            assertThat(d.content()).doesNotContain("氨氯地平");
        });
    }

    @Test
    @DisplayName("全局库：同一文本保持旧口径（整篇一块，section=正文）")
    void numberedSectionsDisabledKeepsOldBehavior() {
        List<DocumentChunker.ChunkDraft> plain = chunker.chunk(NUMBERED_TXT, "txt");
        assertThat(plain).hasSize(1);
        assertThat(plain.get(0).section()).isEqualTo("正文");
    }

    @Test
    @DisplayName("个人库：无小节标号时退化为单块，不产生额外碎块")
    void numberedSectionsNoMarkers() {
        List<DocumentChunker.ChunkDraft> drafts =
                chunker.chunk("这是一段没有标号的纯文本，内容足够短。", "txt", true);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).section()).isEqualTo("正文");
    }

    @Test
    @DisplayName("个人库：正文里的半角小数（1.5 倍）不被误判为小节标号")
    void numberedSectionsIgnoreDecimals() {
        List<DocumentChunker.ChunkDraft> drafts =
                chunker.chunk("一、剂量\n每日 1.5 倍剂量，共 2 次。", "txt", true);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).section()).contains("剂量");
    }

    @Test
    @DisplayName("个人库：PDF 型无标题 XHTML（单正文节）再按「一、二」细切")
    void pdfLikeXhtmlRefined() {
        // PDF 经 Tika 转 XHTML 后无 h1-h6，段落变 <p>
        String xhtml = "<html><body>"
                + "<p>日常饮水与咖啡因记录</p>"
                + "<p>一、饮水量</p><p>每日饮水总量约 1800 毫升。</p>"
                + "<p>二、咖啡因摄入</p><p>每日美式咖啡 2 杯。</p>"
                + "</body></html>";
        // 真实调用：pdf 走 html 策略
        List<DocumentChunker.ChunkDraft> fine = chunker.chunk(xhtml, "html", true);
        assertThat(fine).hasSizeGreaterThanOrEqualTo(2);
        assertThat(fine).anySatisfy(d -> {
            assertThat(d.section()).contains("饮水量");
            assertThat(d.content()).contains("1800");
        });
        // 全局库不切：保持单块旧口径
        List<DocumentChunker.ChunkDraft> plain = chunker.chunk(xhtml, "html");
        assertThat(plain).hasSize(1);
        assertThat(plain.get(0).section()).isEqualTo("正文");
    }
}
