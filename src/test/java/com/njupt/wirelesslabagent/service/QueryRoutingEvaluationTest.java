package com.njupt.wirelesslabagent.service;

import com.njupt.wirelesslabagent.common.RouteLabel;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "knowledge.bootstrap.enabled=false")
@Slf4j
@EnabledIfEnvironmentVariable(named = "RUN_ROUTE_EVALUATION", matches = "true")
class QueryRoutingEvaluationTest {

    @Autowired
    private QueryRoutingService routingService;

    @Value("${routing.evaluation.min-accuracy:0.85}")
    private double minAccuracy;

    @Value("${routing.evaluation.min-macro-f1:0.75}")
    private double minMacroF1;

    private static final List<RouteCase> DATASET = List.of(
            c("请用 USRP 扫描 2.4GHz 附近频谱", RouteLabel.DEVICE),
            c("查看当前设备状态", RouteLabel.DEVICE),
            c("立即停止实验并释放设备", RouteLabel.DEVICE),
            c("请发送一段 QPSK 文本", RouteLabel.DEVICE),
            c("执行认知选频并选择干净频点", RouteLabel.DEVICE),
            c("那它的最大输入功率呢？", RouteLabel.FOLLOW_UP),
            c("这个参数为什么不能再高一点？", RouteLabel.FOLLOW_UP),
            c("刚才那个故障应该怎么继续排查？", RouteLabel.FOLLOW_UP),
            c("那两种调制方式相比呢？", RouteLabel.FOLLOW_UP),
            c("这个怎么弄", RouteLabel.AMBIGUOUS),
            c("USRP 好像不太对，怎么办", RouteLabel.AMBIGUOUS),
            c("信号有问题帮我看看", RouteLabel.AMBIGUOUS),
            c("为什么不工作", RouteLabel.AMBIGUOUS),
            c("设计一套从扫频、选频到自适应调制发送的完整实验方案", RouteLabel.COMPLEX),
            c("比较 BPSK、QPSK 和 16-QAM 在频谱效率与抗噪性上的取舍", RouteLabel.COMPLEX),
            c("结合硬件限制、安全边界和链路预算分析实验参数", RouteLabel.COMPLEX),
            c("系统性分析 RX overflow 的软硬件原因和排查顺序", RouteLabel.COMPLEX),
            c("What is the maximum input power of USRP-2943?", RouteLabel.ENGLISH),
            c("How does carrier frequency offset affect coherent demodulation?", RouteLabel.ENGLISH),
            c("Explain the difference between BPSK and QPSK.", RouteLabel.ENGLISH),
            c("What should I check before starting an RF experiment?", RouteLabel.ENGLISH),
            c("你好", RouteLabel.CHAT),
            c("谢谢你的帮助", RouteLabel.CHAT),
            c("给我讲个笑话", RouteLabel.CHAT),
            c("你是谁", RouteLabel.CHAT),
            c("软件无线电和传统无线电的区别是什么？", RouteLabel.STATIC),
            c("USRP-2943 的工作频率范围是多少？", RouteLabel.STATIC),
            c("BPSK 为什么适合低信噪比链路？", RouteLabel.STATIC),
            c("RX overflow 的含义是什么？", RouteLabel.STATIC),
            c("射频回环为什么必须加衰减器？", RouteLabel.STATIC)
    );

    @Test
    void evaluateAccuracyMacroF1AndConfusionMatrix() {
        Map<RouteLabel, Map<RouteLabel, Integer>> matrix = new EnumMap<>(RouteLabel.class);
        int correct = 0;
        for (RouteCase testCase : DATASET) {
            RouteLabel actual = routingService.route(testCase.message()).label();
            matrix.computeIfAbsent(testCase.expected(), ignored -> new EnumMap<>(RouteLabel.class))
                    .merge(actual, 1, Integer::sum);
            if (actual == testCase.expected()) correct++;
        }

        double accuracy = (double) correct / DATASET.size();
        double macroF1 = macroF1(matrix);
        log.info("路由评测: samples={}, accuracy={}, macroF1={}", DATASET.size(), accuracy, macroF1);
        log.info("混淆矩阵(expected -> actual): {}", matrix);

        assertTrue(accuracy >= minAccuracy,
                () -> "路由 Accuracy=" + accuracy + "，低于阈值 " + minAccuracy);
        assertTrue(macroF1 >= minMacroF1,
                () -> "路由 Macro-F1=" + macroF1 + "，低于阈值 " + minMacroF1);
    }

    private double macroF1(Map<RouteLabel, Map<RouteLabel, Integer>> matrix) {
        return DATASET.stream().map(RouteCase::expected).distinct().mapToDouble(label -> {
            int tp = matrix.getOrDefault(label, Map.of()).getOrDefault(label, 0);
            int fn = matrix.getOrDefault(label, Map.of()).values().stream().mapToInt(Integer::intValue).sum() - tp;
            int fp = matrix.entrySet().stream()
                    .filter(entry -> entry.getKey() != label)
                    .mapToInt(entry -> entry.getValue().getOrDefault(label, 0))
                    .sum();
            double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
            double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
            return precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        }).average().orElse(0);
    }

    private static RouteCase c(String message, RouteLabel expected) {
        return new RouteCase(message, expected);
    }

    private record RouteCase(String message, RouteLabel expected) {
    }
}
