# Spring AI Chat Memory

## 概念定义

LLM 本身是**无状态**的——每次请求都是独立的，模型不记得上一轮对话说了什么。Chat Memory 就是给 LLM 加上"记忆"，在多轮对话中自动携带历史消息。

**为什么需要它？** 类比：你每次打电话给客服，客服都不记得你是谁、上次聊到哪，体验极差。Chat Memory 让 AI 像老熟人一样记住上下文。

---

## 核心对象拆解

### 两层抽象：Memory（策略） + Repository（存储）

| 接口 | 职责 | 比喻 |
|------|------|------|
| `ChatMemory` | 决定**保留哪些**消息（策略层） | 大脑的记忆筛选机制 |
| `ChatMemoryRepository` | 决定**存到哪**（持久层） | 笔记本/硬盘 |

**为什么分两层？** 策略和存储解耦。你可以用"只保留最近 20 条"的策略 + 存内存，也可以同一策略 + 存数据库。换存储不换策略。

### ChatMemory 核心方法

```java
void add(String conversationId, Message message);
List<Message> get(String conversationId, int lastN);
void clear(String conversationId);
```

- `add`：存入一条消息（用户消息 or AI 回复）
- `get`：按 conversationId 取出历史，支持取最近 N 条
- `clear`：清除某个会话的所有记忆

### MessageWindowChatMemory（默认实现）

滑动窗口策略：只保留最近 N 条消息（默认 20），旧消息自动丢弃。**系统消息会被保留**，不被窗口清除。

```java
MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
        .maxMessages(10)  // 滑动窗口大小
        .chatMemoryRepository(repository)  // 可选，不设就用默认的 InMemory
        .build();
```

### MessageChatMemoryAdvisor（记忆注入器）

这是一个 Advisor（拦截器），在每次请求前自动调用 `chatMemory.get(conversationId)`，把历史消息注入到 prompt 中，也把新消息自动 `add` 回去。

**为什么用 Advisor 而不是手动调？** 手动管理需要在每次 chat 前后都写 `add` / `get`，极易遗漏。Advisor 把这件事透明化了。

---

## 项目实战代码逐段解读

### 1. LoveApp.java 中的配置

```java
public LoveApp(ChatModel chatModel) {
    ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build();
    this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem(SYSTEM_PROMPT)
            .defaultAdvisors(
                    builder(chatMemory).build(),  // MessageChatMemoryAdvisor
                    new MyLogAdvisor(),
                    new ReReadingAdvisor()
            )
            .build();
}
```

- `MessageWindowChatMemory.builder().maxMessages(20)` → 滑动窗口 20 条消息
- `builder(chatMemory)` 是 `MessageChatMemoryAdvisor.builder()` 的静态导入
- Advisor 放入 `defaultAdvisors`，每次调用自动生效

### 2. doChat 中的会话隔离

```java
public String doChat(String message, String chatId) {
    ChatResponse response = this.chatClient
            .prompt()
            .user(message)
            .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
            .call()
            .chatResponse();
    ...
}
```

- `CHAT_MEMORY_CONVERSATION_ID_KEY` = `"chat_memory_conversation_id"`
- 每次传不同的 `chatId`（UUID），不同用户/会话之间记忆互不干扰
- **为什么用 `advisors(spec -> spec.param(...))` 而不是全局配置？** conversationId 是运行时才知道的（每个用户不同），必须动态传入

### 3. 请求流程

```
用户发消息 → MessageChatMemoryAdvisor 拦截
  → chatMemory.get(conversationId) 取出历史
  → 历史 + 新消息拼成完整 prompt 发给 LLM
  → LLM 返回结果
  → chatMemory.add(conversationId, 新消息 + LLM 回复) 存回记忆
```

### 4. AiConfig.java（全局 ChatClient Bean）

```java
@Bean
public ChatClient chatClient(ChatModel chatModel) {
    return ChatClient.builder(chatModel)
            .defaultSystem("你是一个有用的AI助手")
            .build();
}
```

和 LoveApp 的区别：LoveApp 自己 new 了一个带 Memory 的 ChatClient，没有用全局 Bean。

---

## 对比表

### 三种 Memory Advisor 对比

| Advisor | 记忆注入方式 | 适用场景 |
|---------|-------------|---------|
| `MessageChatMemoryAdvisor` | 以**消息集合**形式追加到 prompt | 最常用，结构清晰 |
| `PromptChatMemoryAdvisor` | 以**纯文本**拼入 system prompt | 需要自定义 prompt 模板时 |
| `VectorStoreChatMemoryAdvisor` | 从**向量库**检索相关记忆 | 长期记忆/语义搜索 |

### 存储后端对比

| 存储 | 持久化 | 适用场景 |
|------|--------|---------|
| `InMemoryChatMemoryRepository` | 否（重启丢失） | 开发/测试 |
| `JdbcChatMemoryRepository` | 是（关系型 DB） | 生产环境通用 |
| `CassandraChatMemoryRepository` | 是 | 高并发/海量消息/需要 TTL |
| `MongoChatMemoryRepository` | 是 | 文档型存储 |

### ChatMemory vs ChatHistory

| 概念 | 作用 | 用什么存 |
|------|------|---------|
| Chat Memory | 保持**上下文感知**，让 LLM 记住正在聊什么 | `ChatMemory` |
| Chat History | **完整对话记录**，审计/回溯用 | Spring Data / 日志系统 |

---

## 使用方式

### 方式一：自动配置（最简单）

引入 starter，什么都不写，Spring AI 自动创建 `InMemoryChatMemoryRepository` + `MessageWindowChatMemory`。

```java
@Autowired
ChatMemory chatMemory;  // 直接用
```

### 方式二：手动构建（项目当前方式）

```java
ChatMemory chatMemory = MessageWindowChatMemory.builder()
        .maxMessages(20)
        .build();
ChatClient chatClient = ChatClient.builder(chatModel)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .build();
```

### 方式三：持久化（生产环境）

加依赖 → 自动切换存储后端，代码不用改：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
</dependency>
```

### 方式四：不用 ChatClient，手动管理

```java
// 存用户消息
chatMemory.add(conversationId, new UserMessage("我叫小明"));
// 取历史发给 LLM
ChatResponse response = chatModel.call(new Prompt(chatMemory.get(conversationId)));
// 存 AI 回复
chatMemory.add(conversationId, response.getResult().getOutput());
```

**为什么不推荐？** 手动管理容易忘记 add，且 history 里不包括 tool call 的中间消息（Advisor 也有这限制，但至少自动 add/get）。

---

## 直接实现 ChatMemory（不通过 Repository）

### 什么时候需要？

`MessageWindowChatMemory` 只有滑动窗口一种淘汰策略。当需要自定义淘汰逻辑时——比如按时间过期、按消息重要性评分、混合短期+长期记忆——才需要直接实现 `ChatMemory`。

**90% 的场景用 MessageWindowChatMemory + 自定义 Repository 就够了。** 只有策略层需要定制时才往下走。

### 接口签名（Spring AI 1.1.2）

```java
public interface ChatMemory {
    default void add(String conversationId, Message message);    // 委托给下面的 List 版本
    void add(String conversationId, List<Message> messages);     // 批量添加
    List<Message> get(String conversationId);                     // 取全部（无 lastN 参数）
    void clear(String conversationId);                            // 清除
}
```

### 示例：基于 ConcurrenHashMap 的简单实现

```java
public class SimpleChatMemory implements ChatMemory {

    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();
    private final int maxMessages;

    public SimpleChatMemory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        store.merge(conversationId, messages, (existing, added) -> {
            List<Message> merged = new ArrayList<>(existing);
            merged.addAll(added);
            // 窗口淘汰：只保留最近 maxMessages 条
            if (merged.size() > maxMessages) {
                return merged.subList(merged.size() - maxMessages, merged.size());
            }
            return merged;
        });
    }

    @Override
    public List<Message> get(String conversationId) {
        return store.getOrDefault(conversationId, List.of());
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}
```

**为什么不持久化？** 这只是演示策略层的写法。要持久化，在 `add` 和 `get` 里嵌入文件/Redis/DB 读写即可——但那就等于把策略和存储混在一起了，推荐走架构2。

### 两种架构对比

| | 直接实现 ChatMemory | 实现 ChatMemoryRepository |
|---|---|---|
| 你要写的 | 策略 + 存储（混在一起） | 只有存储（纯 I/O） |
| 窗口淘汰 | 自己写 | `MessageWindowChatMemory` 内置 |
| 策略复用 | 每个实现一套淘汰逻辑 | 同一套策略，换存储不改代码 |
| 复杂度 | 高 | 低 |
| 适用场景 | 自定义淘汰策略（按时间、按评分） | 换存储后端（文件 → Redis → DB） |
| 项目为什么没选 | 不需要自定义策略 | 策略用现成的，只换存储就行了 |

### 架构1：实现 ChatMemory（策略 + 存储混在一起）

```
SimpleChatMemory implements ChatMemory
  ├── add() → ConcurrentHashMap.put() + 窗口裁减
  ├── get() → ConcurrentHashMap.get()
  └── clear() → ConcurrentHashMap.remove()
```

### 架构2：实现 ChatMemoryRepository（项目当前方式）

```
MessageWindowChatMemory (策略：保留最近 20 条)      ← 现成的
    └── FileChatMemoryRepository (存储：JSON 文件)  ← 自己实现
    └── RedisChatMemoryRepository (存储：Redis List) ← 自己实现
```

**项目选择架构2的原因：** 窗口淘汰逻辑已经够用，需要定制的是存储层。让策略和存储各司其职——改存储方案不改策略代码，改策略方案不改存储代码。

---

## 自定义 ChatMemoryRepository：文件持久化实战

### 为什么自己实现？

项目用的是阿里云 DashScope，没有引入 JDBC/Cassandra 等持久化 starter。但 `InMemoryChatMemoryRepository` 重启就丢，所以自己做一个文件存储版本——**不改策略，只换存储层**。

### 架构：策略不动，存储替换

```
MessageWindowChatMemory（策略：保留最近 20 条）  ← 不动
    └── FileChatMemoryRepository（存储：每个会话一个 .json 文件）  ← 替换 InMemory
```

**为什么不是直接实现 ChatMemory？** 实现 `ChatMemoryRepository` 只需要管"怎么存"，`MessageWindowChatMemory` 会帮你处理窗口淘汰、系统消息保留等策略逻辑。一行 `maxMessages(20)` 省掉自己写窗口代码。

### Spring AI 1.1.2 接口签名

和最新版有差异，本项目用的是 1.1.2，核心方法：

```java
public interface ChatMemory {
    // 注意：add 参数是 List<Message>，不是单条
    void add(String conversationId, List<Message> messages);
    // 注意：get 没有 lastN 参数，取全部
    List<Message> get(String conversationId);
    void clear(String conversationId);
}

public interface ChatMemoryRepository {
    List<String> findConversationIds();
    List<Message> findByConversationId(String conversationId);
    void saveAll(String conversationId, List<Message> messages);  // 注意：方法名是 saveAll
    void deleteByConversationId(String conversationId);
}
```

和最新版的主要差异：`add` 是批量 List 而非单条；`get` 无 `lastN` 参数；`saveAll` 不是 `save`。

### 代码逐段解读

**文件结构：** 每个 conversationId 一个 `.json` 文件，放在 `chat-memory/` 目录：

```
chat-memory/
  ├── persist-test-001.json
  ├── direct-test-001.json
  └── ...
```

**存储内容格式：** 每条消息序列化为 `{"messageType":"USER","text":"你好"}`

#### （1）目录初始化

```java
public FileChatMemoryRepository(String path) {
    this.dir = Path.of(path);
    Files.createDirectories(dir);  // 目录不存在则自动创建
}
```

#### （2）存入消息 — saveAll

```java
public void saveAll(String conversationId, List<Message> messages) {
    synchronized (getLock(conversationId)) {  // 按 conversationId 加锁，不同会话不互相阻塞
        List<MessageWrapper> wrappers = messages.stream()
                .map(this::toWrapper)       // Message → MessageWrapper
                .toList();
        mapper.writeValue(file.toFile(), wrappers);
    }
}
```

**为什么需要 synchronized？** 同一个 conversationId 可能并发请求（前端连点），不加锁会导致文件被覆盖损坏。注意这里是按 conversationId 分段锁，不同会话之间不互相阻塞。

#### （3）读取消息 — findByConversationId

```java
public List<Message> findByConversationId(String conversationId) {
    // 文件不存在返回空列表
    if (!Files.exists(file)) return new ArrayList<>();
    synchronized (getLock(conversationId)) {
        List<MessageWrapper> wrappers = mapper.readValue(...);
        return wrappers.stream().map(this::toMessage).toList(); // MessageWrapper → Message
    }
}
```

**为什么文件不存在返回空 List 而不是抛异常？** 这是约定——新会话没有历史，上层代码用空列表继续正常对话，不需要额外判空。

#### （4）序列化层 — MessageWrapper + 转换

这是整个实现最关键的设计决策：

```java
record MessageWrapper(String messageType, String text) {}

// Message → MessageWrapper：提取 messageType + text
private MessageWrapper toWrapper(Message message) {
    if (message instanceof ToolResponseMessage trm) {
        // ToolResponseMessage 的 getText() 返回空，需要从 responses 里拼
        String text = trm.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::responseData)
                .reduce((a, b) -> a + "\n" + b).orElse("");
        return new MessageWrapper(message.getMessageType().name(), text);
    }
    return new MessageWrapper(message.getMessageType().name(), message.getText());
}

// MessageWrapper → Message：根据 messageType 重建对应的 Spring AI Message 对象
private Message toMessage(MessageWrapper wrapper) {
    return switch (MessageType.fromValue(wrapper.messageType())) {
        case ASSISTANT -> new AssistantMessage(text);
        case SYSTEM    -> new SystemMessage(text);
        case TOOL      -> ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage("", "", text))).build();
        default        -> new UserMessage(text);
    };
}
```

**为什么用 `record MessageWrapper` 而不是直接序列化 Message？**
- Spring AI 的 `Message` 实现类（`UserMessage`、`AssistantMessage` 等）构造函数不是 Jackson 友好的（需要特定参数、有 protected 构造器、涉及 `Resource` 类型）
- `metadata` 是 `Map<String, Object>`，反序列化时 `Object` 类型会丢失具体类型信息
- 用一个纯数据的 record 做中转，只保留 `messageType` + `text`，简单可靠
- **Message ↔ MessageWrapper 这种设计模式叫"防腐层"**——隔离了 Spring AI 内部类的复杂性和 JSON 序列化的需求

**为什么是 JSON 而不是 Kryo（项目依赖里已有）？**
- JSON 可读可调试——直接打开文件就能看对话内容
- 不怕 Spring AI 版本升级字段变更（JSON 忽略多余字段，Kryo 直接报错）
- Jackson 已随 `spring-boot-starter-web` 引入，零额外依赖

#### （5）分段锁

```java
private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
private Object getLock(String conversationId) {
    return locks.computeIfAbsent(conversationId, k -> new Object());
}
```

**为什么不用全局一把锁？** 全局锁会让所有会话排队等，A 用户发消息也要等 B 用户的文件写完。分段锁让不同会话并行读写，只有同一 conversationId 才串行。

**为什么用 `ConcurrentHashMap.computeIfAbsent` 而不是 `synchronized(this)`？** `computeIfAbsent` 保证同一个 key 只会创建一个 lock 对象（原子操作），且锁粒度为 conversationId 级别。

### LoveApp 集成

```java
public LoveApp(ChatModel chatModel) {
    ChatMemoryRepository repository = new FileChatMemoryRepository("chat-memory");
    ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)   // 注入文件存储
            .maxMessages(20)                     // 窗口策略不变
            .build();
    // ... 其余不变
}
```

**关键：** 只改了 ChatMemory 的构建方式，ChatClient 和 Advisor 的配置完全不变。这就是策略/存储分层的好处——换存储对上层透明。

### 测试验证

测试三个维度（`demo/invoke/MemoryTest.java`）：

| 测试 | 验证点 |
|------|--------|
| 通过 LoveApp 对话 | 文件是否生成、第二轮 AI 是否"记得" |
| 直接操作 Repository | 存取后读回，验证 JSON 内容正确 |
| 删除会话 | `deleteByConversationId` 后文件消失 |

启动程序后看 `chat-memory/` 目录下的 `.json` 文件即可验证持久化是否生效。

### 该方案的局限

- **不适合生产：** 文件 IO 性能远不如数据库，高并发下会成为瓶颈。生产环境应替换为 JDBC/Cassandra 等持久化方案。
- **metadata 丢失：** 只保存了 text，没有保留 metadata（大多数场景不需要）
- **ToolCall 信息丢失：** `AssistantMessage` 里的 tool call 细节没有保存（等 Spring AI 2.0 的原生持久化支持）
