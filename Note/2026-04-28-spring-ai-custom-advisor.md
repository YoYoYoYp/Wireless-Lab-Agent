# Spring AI 自定义 Advisor 笔记

> 项目实战：`src/main/java/com/njupt/wirelesslabagent/advisor/MyLogAdvisor.java`
> 注册位置：`src/main/java/com/njupt/wirelesslabagent/app/WirelessLabAgentApp.java`

---

## 1. 什么是 Advisor？

Advisor 是请求发给 LLM **之前**和拿到响应**之后**插入自定义逻辑的拦截器机制。执行顺序类似洋葱圈（栈式：先进后出）：

```
Request → [Advisor-A 前置] → [Advisor-B 前置] → [LLM] → [Advisor-B 后置] → [Advisor-A 后置] → Response
```

order 值越小越先执行前置，越后执行后置。

---

## 2. API 命名演变（为什么之前叫 CallAroundAdvisor？）

Spring AI 在 GA 前改了名：

| 版本 | 接口名 |
|------|--------|
| 1.0 M2 | `RequestAdvisor` / `ResponseAdvisor`（前后分离） |
| 1.0 M3 | `CallAroundAdvisor` / `StreamAroundAdvisor`（around 式） |
| **1.0.0+** | **`CallAdvisor` / `StreamAdvisor`**（去掉了 Around 后缀） |

只是改名，around 拦截语义不变。当前项目用的是 1.1.x，所以用 `CallAdvisor`。

---

## 3. 核心接口（官方）

```java
// 基础接口
public interface Advisor extends Ordered {
    String getName();   // 名称，调试用
    int getOrder();     // 执行顺序，值越小越先执行
}

// 非流式 Advisor
public interface CallAdvisor extends Advisor {
    ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain);
}

// 流式 Advisor
public interface StreamAdvisor extends Advisor {
    Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain);
}
```

同时实现两个接口 → 流式和非流式都生效。

---

## 4. 核心对象

### 4.1 ChatClientRequest —— 发给 LLM 的请求包

```java
request.userText()       // 用户输入文本
request.prompt()         // 完整 Prompt（含 system/user/function messages）
request.userParams()     // 用户传入的参数 Map
request.adviseContext()  // Advisor 间共享数据的上下文
request.chatOptions()    // 模型参数（temperature、topP 等）
```

修改请求：

```java
request.mutate()
    .userText("改后的文本")
    .systemText("新的 system prompt")
    .build();
```

### 4.2 ChatClientResponse —— LLM 返回的响应包

```java
response.getResult()              // ChatGeneration
    .getOutput()                  // AssistantMessage
        .getText()                // AI 回复文本 ← 最常用
response.getResults()             // 多候选时
response.getChatResponse()        // 原始 Spring AI ChatResponse
```

### 4.3 CallAdvisorChain —— 非流式调用链

```java
// 把请求传给下一个 Advisor，最终到达 LLM
// 不调用这行 = 链路断了 = LLM 永远不被调用
ChatClientResponse response = chain.nextCall(request);
```

比喻：服务员（Advisor）接单 → 递单给厨师（`chain.nextCall`）→ 拿到菜（response）→ 上菜。

### 4.4 StreamAdvisorChain —— 流式调用链

```java
// 返回 Flux，数据逐片到达，不是一次性返回
Flux<ChatClientResponse> flux = chain.nextStream(request);
```

### 4.5 ChatClientMessageAggregator —— 流式聚合器

流式返回是碎片（每个 chunk 一个 Response），需要拼成完整响应才能读文本：

```java
new ChatClientMessageAggregator()
    .aggregateChatClientResponse(flux, fullResp -> {
        // flux:     碎片流（"你"→"好"→"世"→"界"）
        // fullResp: 拼接后的完整响应（"你好世界"）
        log.info("完整响应: {}", fullResp);
    });
```

**方法签名：**

- 第 1 个参数：`Flux<ChatClientResponse>` —— `chain.nextStream(request)` 返回的碎片流
- 第 2 个参数：`Consumer<ChatClientResponse>` —— 拼接完成后执行的回调

---

## 5. 项目实战：MyLogAdvisor（逐行解读）

```java
package com.njupt.wirelesslabagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

@Slf4j
public class MyLogAdvisor implements CallAdvisor, StreamAdvisor {  // 同时支持两种模式

    // ==================== 非流式 ====================
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        log.info("MyLogAdvisor: {}", request);        // ① 前置：打印请求
        ChatClientResponse response = chain.nextCall(request);  // ② 交给下一个/LLM
        log.info("MyLogAdvisor: {}", response);       // ③ 后置：打印响应
        return response;
    }

    // ==================== 流式 ====================
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        log.info("MyLogAdvisor: {}", request);        // ① 前置：打印请求
        Flux<ChatClientResponse> flux = chain.nextStream(request);  // ② 交给下一个/LLM，拿到 Flux
        Flux<ChatClientResponse> result = new ChatClientMessageAggregator()
                .aggregateChatClientResponse(flux, resp -> {
                    // ③ 碎片到齐后回调，resp 已是完整响应
                    log.info("MyLogAdvisor: {}", resp);
                });
        return result;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();  // → "MyLogAdvisor"
    }

    @Override
    public int getOrder() {
        return 0;  // 值越小越先执行
    }
}
```

**关键点总结：**

| 对比 | adviseCall（非流式） | adviseStream（流式） |
|------|---------------------|---------------------|
| 前置 | `log.info(request)` | `log.info(request)` |
| 调用 | `chain.nextCall(request)` 直接拿到完整响应 | `chain.nextStream(request)` 拿到 Flux |
| 后置 | 直接 `log.info(response)` | 必须用 `ChatClientMessageAggregator` 聚合后再打日志 |

---

## 6. 注册到 ChatClient

在 `InterviewApp.java` 中的实际注册方式：

```java
this.chatClient = ChatClient.builder(chatModel)
    .defaultSystem(SYSTEM_PROMPT)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),  // 内置：记忆
        new MyLogAdvisor()                                      // 自定义：日志
    )
    .build();
```

调用时无需改动，Advisor 自动生效：

```java
this.chatClient.prompt()
    .user(message)
    .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
    .call()
    .chatResponse();
```

---

## 7. 常见内置 Advisor（官方）

| Advisor | 功能 |
|---------|------|
| `MessageChatMemoryAdvisor` | 会话记忆，基于 ChatMemory |
| `PromptChatMemoryAdvisor` | 旧版记忆，拼到 system text |
| `QuestionAnswerAdvisor` | RAG 向量检索增强 |
| `RetrievalAugmentationAdvisor` | 模块化 RAG |
| `ReReadingAdvisor` | RE2 策略：重复问题提升推理 |
| `SafeGuardAdvisor` | 内容安全过滤 |

---

## 8. 31位/32位/64位补充

| 概念 | 说明 |
|------|------|
| 1 Byte = 8 bit | 基本换算 |
| bit → Byte | 除以 8 |
| Byte → bit | 乘以 8 |
| 32位（bit） | CPU 一次处理 4 Byte 数据 |
| 32位寻址上限 | 2³² = 4GB |
| 32位有符号整数范围 | -2³¹ ~ 2³¹-1（约 ±21 亿） |

例子：
- `01001` = 5 bit，十进制值 = 1×2⁰ + 0×2¹ + 0×2² + 1×2³ + 0×2⁴ = 9
- IPv4 地址 32 bit（4 段 × 8 bit）
- RGBA 颜色值 32 bit（4 通道 × 8 bit）
