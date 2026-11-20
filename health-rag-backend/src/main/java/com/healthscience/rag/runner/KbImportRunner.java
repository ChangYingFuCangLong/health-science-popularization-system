package com.healthscience.rag.runner;

import com.healthscience.rag.ai.RetrievalService;
import com.healthscience.rag.ai.model.RetrievedChunk;
import com.healthscience.rag.service.KbImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 语料导入入口（M5）。
 *
 * <p><b>只在配置了 {@code health.rag.corpus-dir} 时才执行</b>，
 * 未配置时应用照常作为 Web 服务启动 —— 避免每次启动都跑一遍耗时的导入。
 *
 * <pre>
 * java -jar app.jar --health.rag.corpus-dir="D:\毕业设计\aigetmessage1\chunks" --health.rag.rebuild=true
 * </pre>
 *
 * <p>导入完成后会做一次检索冒烟，把"入库 → 可检索"在一条命令里验证完。
 * M9.5 起冒烟走真实检索链路（pgvector 向量召回，ADR-023）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbImportRunner implements CommandLineRunner {

    private final KbImportService kbImportService;
    private final RetrievalService retrievalService;

    @Value("${health.rag.corpus-dir:}")
    private String corpusDir;

    @Value("${health.rag.rebuild:false}")
    private boolean rebuild;

    /** 导入完成后退出进程。批处理场景用（如 CI、命令行一次性导入），避免应用空转 */
    @Value("${health.rag.exit-after-import:false}")
    private boolean exitAfterImport;

    @Override
    public void run(String... args) {
        if (corpusDir == null || corpusDir.isBlank()) {
            log.info("[KbImportRunner] 未配置 corpus-dir，跳过导入");
            return;
        }
        log.info("[KbImportRunner] 开始导入 dir={} rebuild={}", corpusDir, rebuild);
        KbImportService.ImportResult result = kbImportService.importFromDir(Path.of(corpusDir), rebuild);
        log.info("[KbImportRunner] 导入完成 -> {}", result);
        smokeSearch();
        if (exitAfterImport) {
            log.info("[KbImportRunner] 按配置退出进程");
            System.exit(0);
        }
    }

    /** 导入后立刻验证可检索，避免"入库成功但检索为空"被拖到联调阶段才发现 */
    private void smokeSearch() {
        String[] questions = {"高血压怎么预防", "每天应该喝多少水", "焦虑失眠怎么办", "流感疫苗什么时候打"};
        for (String q : questions) {
            List<RetrievedChunk> hits = retrievalService.retrieve(q, "naive", 3, "global", null).chunks();
            log.info("[SmokeSearch] Q: {} -> 命中 {} 条", q, hits.size());
            for (int i = 0; i < hits.size(); i++) {
                RetrievedChunk c = hits.get(i);
                String text = c.content() == null ? "" : c.content();
                log.info("   [{}] {} | {} | {}",
                        i + 1,
                        c.meta("title"),
                        c.meta("source"),
                        text.substring(0, Math.min(70, text.length())).replace("\n", " "));
            }
        }
    }
}
