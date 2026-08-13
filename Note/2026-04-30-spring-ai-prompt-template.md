# Spring AI PromptTemplate

## 概念定义

`PromptTemplate` 是一个**字符串模板引擎**——提前写好 prompt 骨架，运行时用真实值替换其中的占位符 `{key}`。

**类比：** Word 邮件合并。模板是固定格式的邀请函，`{姓名}` 和 `{日期}` 是占位符，合并时从 Excel 表逐行填入。

**底层引擎：** Spring AI 默认用 `StTemplateRenderer`，它是开源 **StringTemplate** 引擎（Terence Parr 开发）的封装。

```java
// TemplateRenderer 接口 —— 所有模板引擎的抽象
public interface TemplateRenderer extends BiFunction<String, Map<String, Object>, String> {
    String apply(String template, Map<String, Object> variables);
}
```

**为什么用 StringTemplate 而不是其他引擎？** 语法简单（`{}` 就够了）、无外部依赖（已经在 spring-ai-template-st 里打包好）、专为 prompt 场景设计。

---

## 三层 API

`PromptTemplate` 不是一个简单工具类，它实现了多层次接口，越往上越完整：

```
PromptTemplate
  ├── PromptTemplateStringActions.render()      → String       (纯字符串)
  ├── PromptTemplateMessageActions.createMessage() → Message   (单条消息，有角色)
  └── PromptTemplateActions.create()            → Prompt       (完整请求，消息列表 + ChatOptions)
```

### 第一层：render() → 纯字符串

```java
PromptTemplate template = new PromptTemplate("你好{name}，订单{id}已发货");
String result = template.render(Map.of("name", "张三", "id", "8892"));
// "你好张三，订单8892已发货"
```

**项目实际用法**（ReReadingAdvisor.java）：

```java
String augmentedUserText = new PromptTemplate(this.re2AdviseTemplate)
        .render(Map.of("re2_input_query", userText));
```

只需要替换占位符得到字符串，因为这个字符串之后会被塞回 `ChatClientRequest`。

### 第二层：createMessage() → Message（带角色）

```java
Message msg = promptTemplate.createMessage(Map.of("name", "张三"));
// 返回的 Message 自动带上对应的 MessageType（USER/SYSTEM）
```

**和 render() 的区别：** render 只管字符串；createMessage 除了字符串替换，还创建了**路由信息**（这条消息是 USER 还是 SYSTEM 角色）。

### 第三层：create() → Prompt（完整请求容器）

```java
Prompt prompt = promptTemplate.create(Map.of("key", "value"));
// 或者带 ChatOptions
Prompt prompt = promptTemplate.create(Map.of("key", "value"), chatOptions);
// 发给模型
chatModel.call(prompt);
```

**Prompt 内部结构：**

```java
public class Prompt implements ModelRequest<List<Message>> {
    private final List<Message> messages;  // 有序消息列表
    private ChatOptions chatOptions;       // 模型参数（temperature 等）
}
```

**类比：**

| 层级 | 输出 | 类比你项目 |
|------|------|-----------|
| `render()` | 字符串 | `"你好张三"` |
| `createMessage()` | 一条带角色的消息 | `UserMessage("你好张三")` |
| `create()` | 完整请求（消息列表 + 参数） | `Prompt([systemMsg, userMsg], options)` |

---

## 类层次结构

```java
public class PromptTemplate
        implements PromptTemplateActions, PromptTemplateMessageActions {

    // === 来自 PromptTemplateStringActions ===
    String render();
    String render(Map<String, Object> model);

    // === 来自 PromptTemplateMessageActions ===
    Message createMessage();
    Message createMessage(List<Media> mediaList);
    Message createMessage(Map<String, Object> model);

    // === 来自 PromptTemplateActions ===
    Prompt create();
    Prompt create(ChatOptions modelOptions);
    Prompt create(Map<String, Object> model);
    Prompt create(Map<String, Object> model, ChatOptions modelOptions);
}
```

**为什么要分层？** 不同场景需要不同级别的输出。你项目 ReReadingAdvisor 只需要 `render()`（替换字符串），但如果从零搭建一个 ChatClient，就需要 `create()`（生成完整 Prompt）。

---

## SystemPromptTemplate —— 专门创建系统消息

```java
SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate("你是{name}，用{style}风格回答");
Message systemMessage = systemPromptTemplate.createMessage(Map.of("name", "客服小助手", "style", "专业"));
// 返回的 Message 自动标记为 MessageType.SYSTEM
```

**和 PromptTemplate 的区别：** 创建出来的 Message 角色不同。

| 类 | 创建的消息角色 |
|---|--------------|
| `PromptTemplate` | USER（默认） |
| `SystemPromptTemplate` | SYSTEM |

---

## 自定义定界符

默认占位符是 `{ }`，如果 prompt 本身含大量 JSON（花括号冲突），可以改：

```java
PromptTemplate promptTemplate = PromptTemplate.builder()
        .renderer(StTemplateRenderer.builder()
                .startDelimiterToken('<')
                .endDelimiterToken('>')
                .build())
        .template("列出配乐作曲家为<composer>的5部电影")
        .build();
```

**适用场景：** prompt 里包含 JSON Schema、代码示例、或其他大量 `{}` 的内容时，避免 StringTemplate 误把 JSON 的花括号当占位符解析。

---

## 从文件加载模板（Resource 支持）

模板可以放在 `src/main/resources/prompts/` 下，用 Spring 的 `@Value` 注入：

```java
@Value("classpath:/prompts/system-message.st")
private Resource systemResource;

SystemPromptTemplate template = new SystemPromptTemplate(systemResource);
```

**好处：** 长 prompt 不用硬编码在 Java 里，改模板不用重新编译。

---

## 项目实战代码逐段解读

`ReReadingAdvisor.java` 中全程在用 `PromptTemplate.render()`：

```java
// 1. 模板定义
private static final String DEFAULT_RE2_ADVISE_TEMPLATE = """
        {re2_input_query}
        Read the question again: {re2_input_query}
        """;

// 2. 渲染
@Override
public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    String userText = request.prompt().getUserMessage().getText();  // 取出用户原文

    String augmentedUserText = new PromptTemplate(this.re2AdviseTemplate)
            .render(Map.of("re2_input_query", userText));  // 占位符 → 原文

    return request.mutate()
            .prompt(request.prompt().augmentUserMessage(augmentedUserText))
            .build();  // 把改造后的文本塞回请求
}
```

### 为什么这里只用 render() 而不是 createMessage()？

因为这个 Advisor 做的事是"改造已有请求中的用户消息文本"，不是"创建一个新消息"。它只需要字符串替换，消息对象本身还在 `ChatClientRequest` 里。

### 为什么用 `augmentUserMessage` 而不是直接替换？

`augmentUserMessage` 会把用户原文**追加**到新文本后面（拼接），而不是删除原文。这样既保留了原始内容，又加了 RE2 增强的"再读一遍"。

---

## Prompt 便利方法

`Prompt` 类提供了一系列快捷方法取特定角色的消息：

```java
Prompt prompt = ...;

prompt.getUserMessage();                 // 最后一条用户消息
prompt.getSystemMessage();               // 第一条系统消息
prompt.getUserMessages();                // 所有用户消息（List）
prompt.getSystemMessages();              // 所有系统消息（List）
prompt.getLastUserOrToolResponseMessage(); // 最后一条用户或工具消息
```

**为什么要有这些方法？** Advisor 在处理请求时经常需要取特定角色的消息——比如 `ReReadingAdvisor` 只需取用户消息改它的文本，`MessageChatMemoryAdvisor` 需要区分系统消息和对话消息来拼接历史。没有这些便利方法就只能遍历 `messages` 列表手写过滤。

---

## 完整管道：从模板到 LLM 响应

```
字符串模板
  │
  ▼
PromptTemplate.render(Map)           → String             (纯文本替换)
PromptTemplate.createMessage(Map)    → Message            (单条消息，带角色)
PromptTemplate.create(Map, Options)  → Prompt             (请求容器)
  │
  ▼
ChatModel.call(prompt)               → ChatResponse       (LLM 返回)
  │
  ▼
ChatResponse.getResult()             → Generation         (单条生成结果)
  │
  ▼
Generation.getOutput()               → AssistantMessage   (AI 回复)
```

**类比：** JDBC 流程。`PromptTemplate` = SQL 模板 → `Prompt` = PreparedStatement → `ChatModel.call()` = executeQuery() → `ChatResponse` = ResultSet。

Spring AI 官方文档也用了这个类比：`ChatModel` 对应 JDBC core，`ChatClient` 对应 `JdbcClient`（更高层的封装）。

---

## 和项目其他组件的对比

| 组件 | 职责 | 输入 | 输出 |
|------|------|------|------|
| `PromptTemplate` | 模板渲染 | 模板字符串 + Map | String/Message/Prompt |
| `ChatClient` | 对话编排 | Prompt | ChatResponse |
| `MessageChatMemoryAdvisor` | 记忆注入 | 历史消息 | 增强后的 Prompt |
| `ReReadingAdvisor` | 问题增强 | 用户原文 | 改写后的文本 |

**它们的关系：** `PromptTemplate` 只管"把模板变成最终文本"这一步。怎么发、怎么记、怎么增强，是 `ChatClient` 和 Advisor 的活。

---

## Spring AI 版本变更

| 版本 | 类名 | 状态 |
|------|------|------|
| 1.x（当前项目 1.1.2） | `PromptTemplate` | 正常使用 |
| 2.0+ | `StPromptTemplate` | 推荐 |

**为什么改名？** `PromptTemplate` 把 `Prompt`（消息集合容器）和 `Template`（字符串替换引擎）混在一个类名里。2.0 拆开：`Prompt` 管消息组装，`StPromptTemplate` 只管字符串模板。底层一直都是 StringTemplate，功能不变。
