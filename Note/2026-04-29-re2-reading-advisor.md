# RE2 (Re-Reading) Advisor

## 1. 概念定义

**RE2（Re-Reading）** 出自论文 *Re-Reading Improves Reasoning in Large Language Models*。核心思想：把用户问题重复一遍再发给大模型，强制模型"审题两遍"，显著提升推理准确率。成本几乎为零（只多了一句话的 token）。

**Spring AI 的 `ReReadingAdvisor`** 是一个 `BaseAdvisor` 实现，在请求发给 LLM **之前**把用户消息用模板改造（复制一遍），响应侧什么都不改。

### 为什么有效？

大模型的注意力机制在处理长文本时可能忽略关键信息。把问题再说一遍，等于给关键信息"二次曝光"，注意力权重重新聚焦到问题本身。

---

## 2. 核心对象/方法拆解

| 对象 | 作用 |
|---|---|
| `BaseAdvisor` | 旧版 Advisor 接口，定义 `before()` / `after()` / `getOrder()` |
| `PromptTemplate` | 模板引擎，把占位符 `{xxx}` 替换成实际值 |
| `ChatClientRequest.mutate()` | 复制原请求并修改字段（request 不可变，只能 mutate） |
| `augmentUserMessage()` | 替换 Prompt 中的用户消息文本 |

### before() 三条核心操作

```
取出原文 → 模板填空 → mutate 替换
```

- **取出原文**：`request.prompt().getUserMessage().getText()` 一层层剥到用户输入的纯文本
- **模板填空**：`new PromptTemplate(模板).render(Map.of("占位符", 原文))` 产出改造后的文本
- **mutate 替换**：`request.mutate().prompt(原prompt.augmentUserMessage(新文本)).build()` 生成新请求

### 为什么用 mutate 而不是直接改？

`ChatClientRequest` 是不可变对象（immutable），不能 `setXxx()`。只能 `mutate()` 创建副本 + 改字段 + `build()` 生成新对象。

---

## 3. 项目实战代码逐段解读

```java
// 文件：src/main/java/com/njupt/wirelesslabagent/advisor/ReReadingAdvisor.java

// 默认模板：{re2_input_query} 是占位符，名字可以随便起，但要和填空时的 key 一致
private static final String DEFAULT_RE2_ADVISE_TEMPLATE = """
        {re2_input_query}
        Read the question again: {re2_input_query}
        """;

// 两个构造函数：无参用默认模板，有参可自定义
public ReReadingAdvisor() {
    this(DEFAULT_RE2_ADVISE_TEMPLATE);
}
public ReReadingAdvisor(String re2AdviseTemplate) {
    this.re2AdviseTemplate = re2AdviseTemplate;
}

// 核心：before() 在请求到达 LLM 之前执行
@Override
public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
    // ① 取出用户原文
    String userText = chatClientRequest.prompt().getUserMessage().getText();

    // ② 模板填空：{re2_input_query} → 用户原文
    String augmentedUserText = new PromptTemplate(this.re2AdviseTemplate)
            .render(Map.of("re2_input_query", userText));

    // ③ 复制请求，替换用户消息
    return chatClientRequest.mutate()
            .prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))
            .build();
}

// after() 什么都不做——RE2 只管输入侧
@Override
public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
    return chatClientResponse;  // 注意：不能 return null，否则 NPE
}

// 链式设置优先级（order 越小越先执行）
public ReReadingAdvisor withOrder(int order) {
    this.order = order;
    return this;
}
```

### 注册到 ChatClient

```java
// 文件：src/main/java/com/njupt/wirelesslabagent/app/WirelessLabAgentApp.java

this.chatClient = ChatClient.builder(chatModel)
    .defaultSystem(SYSTEM_PROMPT)
    .defaultAdvisors(
        builder(chatMemory).build(),   // Memory
        new MyLogAdvisor(),            // 日志
        new ReReadingAdvisor()         // RE2
    )
    .build();
```

### 运行时日志验证

```
用户输入: "你好，我叫张三，今年28岁。"

ReReadingAdvisor: 你好，我叫张三，今年28岁。
Read the question again: 你好，我叫张三，今年28岁。

MyLogAdvisor 打印的请求中 UserMessage 已是改造后的内容
→ LLM 收到的就是重复两遍的问题
```

---

## 4. 对比表

### RE2 改造前后

| | 发给 LLM 的内容 |
|---|---|
| **不用 RE2** | `你好，我叫张三，今年28岁。` |
| **用 RE2** | `你好，我叫张三，今年28岁。` <br> `Read the question again: 你好，我叫张三，今年28岁。` |

### BaseAdvisor（旧 API）vs CallAdvisor/StreamAdvisor（新 API）

| | BaseAdvisor（RE2 在用） | CallAdvisor/StreamAdvisor（MyLog 在用） |
|---|---|---|
| **非流式方法** | `before()` / `after()` | `adviseCall()` |
| **流式方法** | 不支持 | `adviseStream()` |
| **链调用** | 框架自动处理 | `chain.nextCall(request)` 显式调用 |
| **适用场景** | 只改输入不改输出的简单场景 | 需要区分流式/非流式的复杂场景 |
| **after() 返回值** | 必须返回 response（return null 会 NPE） | 无 after，在 adviseCall 内直接处理 |

### RE2 vs MyLog vs Recursive

| | ReReadingAdvisor | MyLogAdvisor | RecursiveAdvisor |
|---|---|---|---|
| **改输入** | 是 | 否 | 每轮可改 |
| **改输出** | 否 | 否（仅记录） | 每轮可改 |
| **LLM 调用次数** | 1 次 | 1 次 | 多次循环 |
| **API** | BaseAdvisor | CallAdvisor + StreamAdvisor | CallAdvisor |

---

## 5. 使用方式

### 默认模板开箱即用

```java
new ReReadingAdvisor()
```

### 自定义模板

```java
new ReReadingAdvisor("请仔细阅读以下问题：{re2_input_query}\n确认理解后回答：{re2_input_query}")
```

### 调整执行顺序

```java
new ReReadingAdvisor().withOrder(-10)  // 负数让它比默认 Advisor 更早执行
```

### 适用场景

- 数学/逻辑推理题（RE2 论文的主要测试场景）
- 长问题、多约束条件（容易漏信息）
- 代码理解任务
- 不适合：简单闲聊（收益为零）

---

## 6. 关键要点（面试向）

1. **RE2 的本质**：不是让模型多想，而是让它多读。在输入侧做文章，不碰输出侧。
2. **为什么 `after()` 必须返回 response 而不是 null**：返回 null 会导致后续 Advisor 或调用方拿不到响应而 NPE。
3. **为什么用 `PromptTemplate` 而不是直接字符串拼接**：解耦模板和数据。模板是固定套路，数据是用户输入，换模板不改代码。
4. **`{re2_input_query}` 只是个变量名**：叫什么都行，只要模板和 render 时的 key 对上。
5. **BaseAdvisor 的 `before()` 在 CallAdvisor 的 `adviseCall()` 之前执行**：因为 BaseAdvisor 是更外层的包装，先改请求再交给内层链。
