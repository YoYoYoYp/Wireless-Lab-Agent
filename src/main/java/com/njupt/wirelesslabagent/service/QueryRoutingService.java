package com.njupt.wirelesslabagent.service;

import com.njupt.wirelesslabagent.common.RagStrategy;
import com.njupt.wirelesslabagent.common.RouteLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 规则优先、模型分类、异常回退三层查询路由。 */
@Slf4j
@Service
public class QueryRoutingService {

    private static final List<String> DIRECT_DEVICE_PHRASES = List.of(
            "设备状态", "usrp状态", "硬件状态", "停止实验", "停止任务", "释放设备"
    );
    private static final List<String> HARDWARE_TERMS = List.of(
            "usrp", "扫频", "频谱扫描", "底噪", "tone", "单音", "bpsk", "qpsk",
            "qam", "fsk", "自适应调制", "认知选频", "ris", "频点"
    );
    private static final Pattern COMMAND_INTENT = Pattern.compile(
            "(^|[，,。；;\\s])(请|请用|帮我|现在|立即|开始|执行|运行|停止|发送|发射|扫描|扫频|测量|配置|查询|查看|获取)"
    );

    private final ChatClient classifier;

    public QueryRoutingService(ChatModel chatModel) {
        this.classifier = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是无线实验室查询路由器，只输出以下标签之一，不要解释：
                        DEVICE：设备状态或硬件实验动作；
                        FOLLOW_UP：依赖聊天历史才能理解的追问；
                        AMBIGUOUS：表达模糊、口语化，需要重写后检索；
                        COMPLEX：需要从多个角度检索的复杂知识问题；
                        ENGLISH：需要翻译为中文后检索的英文知识问题；
                        CHAT：不需要实验知识的普通对话；
                        STATIC：表意清楚的无线实验知识问题。
                        """)
                .build();
    }

    public RoutingDecision route(String message) {
        if (isDeviceRequest(message)) {
            return new RoutingDecision(RouteLabel.DEVICE, RagStrategy.NONE, false, "device-keyword");
        }
        try {
            String output = classifier.prompt()
                    .user("用户消息: " + message + "\n分类结果:")
                    .call()
                    .content();
            return RouteLabel.parse(output)
                    .map(label -> new RoutingDecision(label, label.strategy(), false, "model"))
                    .orElseGet(() -> {
                        log.warn("路由器输出无法识别，回退到 SINGLE: {}", output);
                        return fallback("invalid-label");
                    });
        } catch (Exception exception) {
            log.warn("查询分类失败，回退到 SINGLE: {}", exception.getMessage());
            return fallback("classifier-error");
        }
    }

    private RoutingDecision fallback(String reason) {
        return new RoutingDecision(RouteLabel.UNKNOWN, RagStrategy.SINGLE, true, reason);
    }

    private boolean isDeviceRequest(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (DIRECT_DEVICE_PHRASES.stream().anyMatch(normalized::contains)) {
            return true;
        }
        boolean hasHardwareTerm = HARDWARE_TERMS.stream().anyMatch(normalized::contains);
        return hasHardwareTerm && COMMAND_INTENT.matcher(normalized).find();
    }

    public record RoutingDecision(RouteLabel label,
                                  RagStrategy strategy,
                                  boolean fallback,
                                  String reason) {
    }
}
