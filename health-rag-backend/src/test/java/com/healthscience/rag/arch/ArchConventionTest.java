package com.healthscience.rag.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构约束固化测试（M10/B-11，任务卡 G11 验收项）。
 *
 * <p>约束 C1/C2 在 V1 只是注释里的纪律 —— 业务层（service/controller/runner）实际
 * 已多次直接依赖 Spring AI 类型。本测试把约束变成 CI 可执行规则：
 * <b>AI 框架类型只允许出现在 {@code com.healthscience.rag.ai..} 内</b>，
 * 业务层一律经由 ai 包的框架无关接口（ChatService/RetrievalService/EmbeddingService/...）。
 */
@AnalyzeClasses(packages = "com.healthscience.rag", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchConventionTest {

    @ArchTest
    static final ArchRule springAiOnlyInsideAiLayer = noClasses()
            .that().resideOutsideOfPackage("com.healthscience.rag.ai..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..")
            .because("架构约束 C1/C2：业务层只依赖 ai 包的框架无关接口，"
                    + "换 AI 框架/供应商时改动面被限制在 ai 包内");

    @ArchTest
    static final ArchRule vectorStoreNotExposed = noClasses()
            .that().resideOutsideOfPackage("com.healthscience.rag.ai..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.vectorstore..")
            .because("向量库实现细节（pgvector/未来其他实现）必须收敛在 ai 层之后");
}
