# Spring AI 整体架构

## 四层结构

```
┌──────────────────────────────────────────────────────────────────┐
│  ChatClient  (编排层)                                             │
│  - 负责把 prompt + advisor + model 串起来                         │
│  - 你项目 LoveApp.this.chatClient                                │
│  - 类比: JdbcClient                                               │
└───────────────────┬──────────────────────────────────────────────┘
                    │ 持有
      ┌─────────────┴─────────────┐
      │    Advisor 拦截器链         │
      │  - ReReadingAdvisor        │  before → 改用户消息
      │  - MyLogAdvisor            │  before/after → 打日志
      │  - MessageChatMemoryAdvisor │  before → 注入历史，after → 存新消息
      └─────────────┬──────────────┘
                    │ 最终调用
      ┌─────────────┴──────────────┐
      │   ChatModel  (通信层)       │
      │  - DashScopeChatModel      │  发给阿里百炼 deepseek-v3.2
      │  - 只做一件事：收 Prompt，返回 ChatResponse                │
      │  - 类比: JDBC Statement    │
      └─────────────┬──────────────┘
                    │ 依赖
      ┌─────────────┴──────────────┐
      │   ChatMemory  (记忆存储)    │
      │  - MessageWindowChatMemory  │  策略层: 只保留最近 20 条
      │  - FileChatMemoryRepository │  存储层: 每个会话一个 .json 文件
      └─────────────────────────────┘
```

---

## 各组件职责

### ChatModel — 通信层

只管发送 Prompt，返回 ChatResponse。不关心历史、日志、增强。

```java
ChatResponse response = chatModel.call(new Prompt(/* 消息列表 */));
```

**对比：** JDBC 的 `Statement.executeQuery()`。

### ChatClient — 编排层

在 ChatModel 上面包了一层编排。用 Builder 声明式配置：

```java
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultSystem("系统提示词")       // 默认系统消息
        .defaultAdvisors(advisor1, advisor2) // 默认 Advisor 链
        .build();
```

做了 ChatModel 不做的事：跑 Advisor 链、管理默认配置、提供流式调用。

### Advisor — 拦截器

在 ChatModel 调用前后插入逻辑。两个接口：

| 接口 | 方法 | 你项目谁实现了 |
|------|------|--------------|
| `BaseAdvisor` | `before()` / `after()` | ReReadingAdvisor |
| `CallAdvisor` | `adviseCall()` | MyLogAdvisor |

三者对比：

| Advisor | 位置 | before（调 LLM 前） | after（调 LLM 后） |
|---------|------|-------------------|-------------------|
| MessageChatMemoryAdvisor | 内置 | 取历史注入 prompt | 存新消息 |
| ReReadingAdvisor | 项目自定义 | 复制用户问题（RE2 增强） | 原样放行 |
| MyLogAdvisor | 项目自定义 | 打印请求 | 打印响应 |

**为什么用 Advisor 而不是手写？** 不然每次对话前后都要手动写取历史、存消息、打日志、改文本。Advisor 一次配置，自动执行。

### ChatMemory — 记忆

两层设计：

```
MessageWindowChatMemory  (策略层: 决定留哪些)
       ↓
FileChatMemoryRepository (存储层: 决定存哪 + 怎么存)
```

| 类 | 接口 | 职责 |
|---|------|------|
| MessageWindowChatMemory | ChatMemory | 策略：只保留最近 N 条 |
| FileChatMemoryRepository | ChatMemoryRepository | 存储：JSON 文件持久化 |

---

## 一次完整请求的步骤

以 `loveApp.doChat("我叫持久化测试员", "test-001")` 为例：

### 初始化（构造时执行一次）

```
new LoveApp(chatModel)
├── new FileChatMemoryRepository("chat-memory/")     → 创建存储目录
├── MessageWindowChatMemory.builder()                → 创建记忆策略
│     .chatMemoryRepository(repository)               → 注入文件存储
│     .maxMessages(20)                                 → 窗口大小 20
│     .build()
├── ChatClient.builder(chatModel)                    → 创建编排器
│     .defaultSystem(SYSTEM_PROMPT)                   → 默认系统消息
│     .defaultAdvisors(                               → Advisor 链
│         MessageChatMemoryAdvisor.builder(chatMemory).build(),  // 记忆
│         new MyLogAdvisor(),                                     // 日志
│         new ReReadingAdvisor()                                  // RE2增强
│     )
│     .build()
```

### 每次调用 doChat("我叫持久化测试员", "test-001")

```
1. ChatClient 收请求
   chatClient.prompt()
       .user("我叫持久化测试员")
       .advisors(spec → spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, "test-001"))
       .call()

2. call() 内部构建 Prompt
   Prompt = [
       SystemMessage("扮演深耕恋爱心理领域的专家..."),
       UserMessage("我叫持久化测试员")
   ]

3. Advisor 链 before 阶段（order 从小到大依次执行）
   │
   ├── [order=0] MessageChatMemoryAdvisor.before()
   │   ├── chatMemory.get("test-001")
   │   │   ├── MessageWindowChatMemory.get("test-001")
   │   │   └── FileChatMemoryRepository.findByConversationId("test-001")
   │   │       ├── 读 chat-memory/test-001.json
   │   │       ├── 反序列化 JSON → List<MessageWrapper> → List<Message>
   │   │       └── 返回历史消息（或空列表）
   │   └── 把历史消息注入 Prompt（LLM 就知道之前聊了什么）
   │
   ├── [order=0] ReReadingAdvisor.before()
   │   ├── 取出 Prompt 中 UserMessage 的文本
   │   ├── PromptTemplate.render({re2_input_query → "我叫持久化测试员"})
   │   │   模板: "{re2_input_query}\nRead the question again: {re2_input_query}"
   │   │   结果: "我叫持久化测试员\nRead the question again: 我叫持久化测试员"
   │   └── augmentUserMessage() 把改造后的文本替换回去
   │
   └── [order=0] MyLogAdvisor.adviseCall()
       └── log.info("request: {}", request)   // 你日志里看到的那一大段

4. 调用 ChatModel
   │
   └── DashScopeChatModel.call(prompt)
       └── HTTP → 阿里百炼 API → deepseek-v3.2 处理
           返回: ChatResponse[AssistantMessage("很高兴见到你，持久化测试员！...")]

5. Advisor 链 after 阶段（逆序执行）
   │
   ├── MyLogAdvisor.adviseCall() 返回
   │   └── log.info("response: {}", response)
   │
   ├── ReReadingAdvisor.after()
   │   └── 原样返回，不做修改
   │
   └── MessageChatMemoryAdvisor.after()
       ├── chatMemory.add("test-001", [UserMessage, AssistantMessage])
       │   ├── FileChatMemoryRepository.findByConversationId("test-001")  // 读现有
       │   ├── 追加新消息
       │   ├── 超过 20 条 → 裁掉最早的
       │   └── FileChatMemoryRepository.saveAll("test-001", 最后20条)
       │       ├── Message → MessageWrapper 防腐转换
       │       ├── Jackson 序列化为 JSON
       │       └── 覆写 chat-memory/test-001.json
       └── 完成

6. 返回 ChatResponse 给调用者
   → doChat() 拿到 response → 取 text → log.info("content: {}")
```

---

## 记忆的读取和写入

### 写流程

```
MessageChatMemoryAdvisor.after()
  → chatMemory.add(conversationId, messages)
    → MessageWindowChatMemory.add():
        │  1. repository.findByConversationId()  读文件中现有消息
        │  2. 追加新消息
        │  3. 超过 maxMessages → 裁掉最早的
        │  4. repository.saveAll()                覆写整个文件
        └→ FileChatMemoryRepository.saveAll()
            ├── messages → MessageWrapper 列表
            │    Message("我叫...", USER) → {"messageType":"user","text":"我叫..."}
            │    Message("你好...", ASSISTANT) → {"messageType":"assistant","text":"你好..."}
            ├── synchronized(分段锁) 保证同一会话串行写入
            └── Jackson.writeValue() 覆写 .json 文件
```

### 读流程

```
MessageChatMemoryAdvisor.before()
  → chatMemory.get(conversationId)
    → MessageWindowChatMemory.get():
        │  1. repository.findByConversationId()  读文件
        │  2. 返回全部消息（文件里就 ≤ 20 条，策略保证的）
        └→ FileChatMemoryRepository.findByConversationId()
            ├── 文件不存在 → return 空列表（新会话）
            ├── synchronized(分段锁) 读保护
            ├── Jackson.readValue() 反序列化 JSON
            ├── MessageWrapper → Message 重建
            └── 返回历史消息列表
```

### 第 N 次对话的窗口变化

```
第 1 次对话 → 文件: [msg1, msg2]                                  (2条)
第 2 次对话 → 文件: [msg1, msg2, msg3, msg4]                       (4条)
...
第 10 次对话 → 文件: [...20条]                                    (刚好满)
第 11 次对话 → 文件: [msg3, msg4, ..., msg21, msg22]              (旧的2条被裁)
第 100 次对话 → 文件: 还是最近 20 条                                (前 80 轮早丢了)
```

---

## 对比

### ChatClient vs ChatModel

| | ChatModel | ChatClient |
|---|---|---|
| 职责 | 调 LLM | 编排整个对话流程 |
| Advisor | 无，自己手动 | 声明式配置，自动执行 |
| 记忆管理 | 自己写 | Advisor 自动 |
| 默认系统消息 | 无 | defaultSystem() |
| 流式 | 自己处理 Flux | 内置流式 API |
| 类比 | JDBC Statement | JdbcClient |
| 你项目用了 | 没直接用 | LoveApp 里用 |

### create() vs builder()

| | create(chatModel) | builder(chatModel)...build() |
|---|---|---|
| 默认系统消息 | ❌ | ✅ defaultSystem() |
| 默认 Advisor | ❌ | ✅ defaultAdvisors() |
| 适用场景 | 一次性简单请求 | 需要长期复用 + 记忆 |
| 项目谁用了 | 无 | LoveApp |
| 文档示例 | 演示发图 | — |

### 手动记忆 vs Advisor 自动记忆

| | 手写 | Advisor |
|---|---|---|
| 代码量 | 每次 5-6 行 | 0 行（声明一次） |
| 遗漏风险 | 高 | 无 |
| 窗口淘汰 | 自己实现 | MessageWindowChatMemory 内置 |
