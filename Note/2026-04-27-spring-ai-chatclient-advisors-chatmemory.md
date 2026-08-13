# Spring AI 答疑笔记

## 1. CommandLineRunner 是什么？

Spring Boot 提供的接口，只有一个 `run(String... args)` 方法。

**作用**：Spring 容器初始化完成后，自动调用所有实现了该接口的 Bean 的 `run` 方法。适合启动后执行一次性任务（预热缓存、测试连通性、调用 AI 等）。

**类比**：就像 `main` 方法之后的第二个入口，Spring 环境就绪了才执行。

---

## 2. ChatClient vs ChatModel

| ChatModel | ChatClient |
|---|---|
| 构造 `Prompt` 对象，手动取 `Result` | 流式链式调用 `.call().content()` |
| 底层 API | 高层封装 |
| 适合框架内部 | 适合业务代码 |

### ChatClient 使用步骤

**配置类注册 Bean**：

```java
@Configuration
public class AiConfig {
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个有用的AI助手")
                .build();
    }
}
```

**注入调用**：

```java
@Component
public class SpringAiAiInvoke implements CommandLineRunner {
    @Resource
    private ChatClient chatClient;

    @Override
    public void run(String... args) {
        String result = chatClient.prompt()
                .user("你好，你觉得先有鸡还是先有蛋？")
                .call()
                .content();
        System.out.println(result);
    }
}
```

`ChatClient.builder(chatModel)` 就是 Builder 构造器模式。

---

## 3. @Resource vs @Autowired

### 来源不同

- `@Autowired` — Spring 原生注解（`org.springframework.beans.factory.annotation`）
- `@Resource` — JDK 标准注解（`jakarta.annotation.Resource`），Java EE 规范

### 注入策略不同（核心区别）

- **`@Autowired`**：默认按**类型**（byType）查找。多个同类型 Bean 时需配合 `@Qualifier` 按名称二次筛选。
- **`@Resource`**：默认按**名称**（byName）查找。先解析属性名作为 Bean 名称去匹配，找不到再退化为按类型匹配。

示例：

```java
// 假设容器中有两个 ChatModel Bean：dashscopeChatModel、openAiChatModel

@Autowired
private ChatModel chatModel;  // 按类型 → 两个 → 报错 NoUniqueBeanDefinitionException

@Autowired
@Qualifier("dashscopeChatModel")
private ChatModel chatModel;  // 按类型 → 两个 → Qualifier 筛选 → 成功

@Resource
private ChatModel dashscopeChatModel;  // 按名称 "dashscopeChatModel" → 直接命中
```

### 注解位置

- `@Autowired`：构造器、Setter、字段、方法参数
- `@Resource`：字段、Setter（**不支持构造器注入**）

### 是否必须

- `@Autowired(required = false)` — 找不到 Bean 也不报错
- `@Resource` — 没有 required 属性，找不到直接抛异常

### 一句话总结

`@Autowired` 类型优先，`@Resource` 名称优先。属性名恰好和 Bean 名称一致时，`@Resource` 能直接命中，省去 `@Qualifier`。

---

## 4. .call() 和 .content()

### .call()

执行请求，向大模型发起调用，返回 `ChatClient.CallResponseSpec`。不调用 `.call()` 就只是构建了 Prompt，还没真正发出去。类似 Stream 的终止操作。

### .content()

从响应中提取纯文本。等同于：

```java
.call()
.chatResponse()   // 取完整响应（含 token 用量、finishReason 等元数据）
.getResult()
.getOutput()
.getText();
```

### 完整链拆解

```
.prompt()     → PromptBuilder      构建提示词
.user(...)    → PromptBuilder      设置用户消息
.call()       → CallResponseSpec   发出请求，拿到响应
.content()    → String             提取纯文本结果
```

### 其他终止方式

```java
// 流式输出
chatClient.prompt().user("...").stream().content();  // Flux<String>

// 取完整响应对象
ChatResponse response = chatClient.prompt().user("...").call().chatResponse();

// 取实体映射
MyDto dto = chatClient.prompt().user("...").call().entity(MyDto.class);
```

---

## 5. Advisors（顾问）机制

### 是什么

Spring AI 的**拦截器链**机制，在请求发给大模型之前和收到响应之后插入处理逻辑。本质是 AOP 思想在 AI 调用链上的应用。

### 工作流程

```
用户 Prompt → [Advisor1.before] → [Advisor2.before] → 大模型 → [Advisor2.after] → [Advisor1.after] → 最终结果
```

### 内置 Advisors

| Advisor | 作用 |
|---|---|
| `MessageChatMemoryAdvisor` | 自动管理对话历史，把最近 N 轮对话注入上下文 |
| `PromptChatMemoryAdvisor` | 同上，但用文本拼接方式 |
| `QuestionAnswerAdvisor` | 基于向量数据库的 RAG，自动检索知识库注入 Prompt |
| `SimpleLoggerAdvisor` | 记录请求/响应日志 |
| `SafeGuardAdvisor` | 内容安全过滤 |

### 自定义 Advisor

```java
public class LoggingAdvisor implements CallAroundAdvisor {
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        System.out.println("请求前: " + request.userText());
        AdvisedResponse response = chain.nextAroundCall(request);
        System.out.println("响应后: " + response.response().getResult().getOutput().getText());
        return response;
    }
}
```

### 一句话

Advisors = 可插拔的 AI 能力增强插件，不用侵入业务代码，挂上就能自动记忆、RAG、日志、安全过滤。

---

## 6. ChatClient + Advisors 配置详解

```java
@Bean
public ChatClient chatClient(ChatModel chatModel, VectorStore vectorStore) {
    return ChatClient.builder(chatModel)
            .defaultAdvisors(
                new MessageChatMemoryAdvisor(new InMemoryChatMemory()),  // 对话记忆
                new QuestionAnswerAdvisor(vectorStore)                    // RAG 检索
            )
            .build();
}
```

`ChatModel` 和 `VectorStore` 由 Spring 自动注入，`.defaultAdvisors()` 设置的 Advisor 会对每次 `.call()` 生效。

### 执行流程

1. **MessageChatMemoryAdvisor.before** → 从 ChatMemory 取出前几轮对话 → 拼进 Context Window
2. **QuestionAnswerAdvisor.before** → 用用户问题去 VectorStore 检索相关知识片段 → 拼进 Prompt
3. 大模型收到 = System + 历史对话 + 知识片段 + 当前问题
4. 返回回复

---

## 7. Advisor 拼接示例

### 假设场景

用户第三轮问："如何退款？"，知识库有退款政策。

**历史对话**：

```
轮1: 用户:"我的订单在哪里"  → AI:"请提供订单号"
轮2: 用户:"订单号 12345"     → AI:"订单正在运输中，预计明天到达"
```

**知识库片段**：退款政策文档切片。

### 实际发给大模型的完整 Prompt

```json
{
  "messages": [
    { "role": "system", "content": "你是一个有用的AI助手" },
    { "role": "system", "content": "以下是参考知识：\n退款政策：7天内无理由退款..." },
    { "role": "user", "content": "我的订单在哪里" },
    { "role": "assistant", "content": "请提供订单号" },
    { "role": "user", "content": "订单号 12345" },
    { "role": "assistant", "content": "订单正在运输中，预计明天到达" },
    { "role": "user", "content": "如何退款？" }
  ]
}
```

### 拼接逻辑

```
[System（defaultSystem）]
    ↓
[QuestionAnswerAdvisor → 知识片段拼成 System Message]
    ↓
[MessageChatMemoryAdvisor → 历史对话逐条拼入]
    ↓
[当前用户问题 user("如何退款？")]
```

你只写了一行 `user("如何退款？")`，其余全是 Advisor 自动拼的。

---

## 8. PromptChatMemoryAdvisor vs MessageChatMemoryAdvisor vs QuestionAnswerAdvisor

三者都从 `ChatMemory` 取历史对话，区别在**怎么把这些历史塞进 Prompt**。

### MessageChatMemoryAdvisor

按消息角色逐条插入消息列表：

```
[messages: [
  {role: user, content: "上一条问题"},
  {role: assistant, content: "上一条回答"}
]]
```

结构干净，角色边界清晰。

### PromptChatMemoryAdvisor

把所有历史拼成一段**文本**，塞到 System Message 末尾：

```
[system: "以下是历史对话：\n用户: 上一条问题\n助手: 上一条回答\n---"]
```

老模型兼容性好，但角色边界模糊。

### QuestionAnswerAdvisor

**不存对话历史**。把用户当前问题拿去 `VectorStore` 做相似度检索，把搜出来的知识片段注入 Prompt。用户问什么就搜什么，无状态。

### 总结

| Advisor | 干了什么 | 数据来源 |
|---|---|---|
| MessageChatMemoryAdvisor | 带对话历史，逐条消息插入 | ChatMemory |
| PromptChatMemoryAdvisor | 带对话历史，拼成一段文本 | ChatMemory |
| QuestionAnswerAdvisor | 不带历史，检索知识库 | VectorStore |

---

## 9. ChatMemory 是什么？

存储对话历史的接口，本质是一个 `Map<String, List<Message>>`。

```java
public interface ChatMemory {
    void add(String conversationId, List<Message> messages);  // 追加消息
    List<Message> get(String conversationId, int lastN);      // 取最近 N 条
    void clear(String conversationId);                         // 清空
}
```

### 内置实现

- **`InMemoryChatMemory`** — 存内存里，重启就没了，适合开发测试
- **`CassandraChatMemory`** / **`JdbcChatMemory`** — 持久化到数据库，生产用

### 工作机制

每次调 `.call()`，Advisor 自动做两件事：

1. **请求前**：从 `ChatMemory.get(conversationId, lastN)` 取出最近 N 条历史，拼进 Prompt
2. **响应后**：把本轮问答写回 `ChatMemory.add(conversationId, [userMsg, aiMsg])`

通过 `conversationId` 区分不同会话：

```java
chatClient.prompt()
    .user("如何退款？")
    .advisors(a -> a.param("chat_memory_conversation_id", "user-123"))
    .call()
    .content();
```

### 一句话

`ChatMemory` = 对话历史仓库，`MessageChatMemoryAdvisor` = 自动存取仓库的搬运工。
