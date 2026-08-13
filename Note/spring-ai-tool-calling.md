# Spring AI Tool Calling（工具调用）完整笔记

> 官方文档：https://docs.spring.io/spring-ai/reference/api/tools.html
> 项目实战：LoveApp + FileOperationTool / WebSearchTool / PDFGenerationTool 等 6 个工具
> 版本差异：1.0.0-M6 vs 1.1.2 API 变化对比

---

## 1. 概念：什么是 Tool Calling

### 1.1 LLM 的能力边界

LLM 只能生成文本，做不了这些：

| 做不到的事 | 原因 |
|---|---|
| 查实时天气/搜索网页 | 训练数据有截止日期 |
| 读写文件、执行命令 | 没有操作系统权限 |
| 调你的业务接口 | 没有网络访问能力 |
| 精确计算 | 概率模型，不是计算器 |

### 1.2 解决思路：把函数当"外挂"给模型

Tool Calling 的核心：**你定义函数 → 告诉模型"我有这些能力" → 模型决定要不要调 → 你来执行 → 结果喂回模型**。

```
无 Tool Calling：
  用户："北京天气？"
    → 模型："北京此时大概是晴天"（编的）

有 Tool Calling：
  用户："北京天气？"
    → 模型：我要调 searchWeb("北京天气")  ← 模型主动决定
    → 你的代码执行 searchWeb → Tavily API → 返回真实结果
    → 模型：基于真实结果生成回答
```

**类比**：你给指挥官配兵。指挥官（模型）负责决策，兵（Tool）负责执行。指挥官不需要自己打仗。

### 1.3 和 Advisors 的区别

| | Advisors | Tool Calling |
|---|---|---|
| 谁控制执行 | 你的代码 `before()` / `after()` | **模型决定** |
| 执行时机 | 每次请求必执行（拦截器链） | 模型按需触发 |
| 参数谁决定 | 你硬编码 | 模型从用户消息提取 |
| 典型用途 | 日志、RAG 检索、查询改写 | 搜索、文件操作、命令执行 |

```java
// Advisor：你的代码控制，每次必走
new MyLogAdvisor().withOrder(3);

// Tool：模型控制，按需调用，参数动态
// 用户说"北京天气怎样" → 模型提取 city="北京" → 决定调 searchWeb("北京天气")
```

---

## 2. 定义工具：@Tool 注解

### 2.1 基本用法

```java
@Component
public class WebSearchTool {

    @Tool(description = "Search the web for real-time information")
    public String searchWeb(
            @ToolParam(description = "Search query keyword") String query) {
        // 调用 Tavily API 搜索
        return results;
    }
}
```

用 `@Tool` 注解任意方法即可定义为工具。Spring AI 会自动生成 JSON Schema。

### 2.2 @Tool 属性

| 属性 | 说明 | 默认值 |
|---|---|---|
| `name` | 工具名，在一个请求中必须**唯一** | 方法名 |
| `description` | **关键**。模型靠它理解工具用途，决定是否调用 | — |
| `returnDirect` | true 则结果直接返回给调用方，不再喂给模型 | false |
| `resultConverter` | 自定义结果序列化器 | DefaultToolCallResultConverter |

### 2.3 @ToolParam 属性

| 属性 | 说明 | 默认值 |
|---|---|---|
| `description` | 参数含义，帮助模型从用户消息提取值 | — |
| `required` | 是否必填 | true |

```java
@Tool(description = "Save content to a file")
public String saveFile(
    @ToolParam(description = "File name, e.g. draft.txt") String fileName,
    @ToolParam(description = "Text content to write") String content
) { ... }
```

**optional 参数**需要显式标记：`@Nullable`、`@ToolParam(required = false)`、或 `@JsonProperty(required = false)`。标记错了会导致模型编造参数值（幻觉）。

### 2.4 方法约束

- 支持 static / instance 方法，任意访问修饰符
- 支持零个或多个参数，基本类型、POJO、枚举、List、Map 等
- 返回值必须可序列化（结果以 JSON 喂回模型）
- **不支持**：`Optional`、`CompletableFuture`、`Mono`/`Flux`、`Function`/`Supplier`/`Consumer`（这些用 FunctionToolCallback）

---

## 3. 注册工具：三种方式

### 3.1 方式一：声明式 — ToolCallbacks.from()（推荐）

将带 `@Tool` 注解的对象直接转为 `ToolCallback[]`：

```java
ToolCallback[] callbacks = ToolCallbacks.from(
    new FileOperationTool(),
    new WebSearchTool(apiKey),
    new PDFGenerationTool()
);
```

**1.0.0-M6 → 1.1.2 包路径变化**（踩坑记录）：

| 版本 | 包路径 |
|---|---|
| 1.0.0-M6 | `org.springframework.ai.tool.ToolCallbacks` |
| 1.1.2 | `org.springframework.ai.support.ToolCallbacks` |

在 1.1.2 中，`ToolCallbacks` 及相关类移到了 `spring-ai-model` 模块。

### 3.2 方式二：编程式 — MethodToolCallback.builder()

逐个构建 ToolCallback，需要手动提供 ToolDefinition：

```java
Method method = ReflectionUtils.findMethod(MyTool.class, "doSomething");

ToolDefinition definition = ToolDefinitions.builder(method)
    .description("Do something useful")
    .build();

ToolCallback callback = MethodToolCallback.builder()
    .toolDefinition(definition)
    .toolMethod(method)
    .toolObject(new MyTool())
    .build();
```

**1.0.0-M6 注意**：builder 不会自动从 `@Tool` 注解推断 ToolDefinition，必须显式调用 `.toolDefinition()`，否则报 `toolDefinition cannot be null`。1.1.2 无此问题。

### 3.3 方式三：编程式 — FunctionToolCallback（java.util.function 类型）

当工具是 `Function`/`Supplier`/`Consumer`/`BiFunction` 时使用：

```java
public class WeatherService implements Function<WeatherRequest, WeatherResponse> {
    public WeatherResponse apply(WeatherRequest request) { ... }
}

ToolCallback callback = FunctionToolCallback
    .builder("currentWeather", new WeatherService())
    .description("Get the weather in location")
    .inputType(WeatherRequest.class)
    .build();
```

### 3.4 方式四：Spring Bean 自动扫描

工具类标注 `@Component`，Spring AI 自动配置会扫描所有 `@Tool` 方法并注册。但**这不会自动把工具加到请求里**——还需要在 ChatClient 中显式引用（见下节）。

### 3.5 本项目实际采用的方案

经历了多次试错后，最终采用的方式：

1. 每个工具类加 `@Component`（Spring 管理生命周期）
2. ToolConfig 用 `ToolCallbacks.from()` 组装成 `ToolCallback[]` Bean（后被废弃）
3. 最终简化：LoveApp 直接 `@Autowired` 注入工具对象，在 `doChatWithTools` 中通过 `.tools(...)` 传给 ChatClient

**踩坑汇总**：

| 问题 | 原因 | 解决 |
|---|---|---|
| `ToolCallbacks` import 报红 | 1.1.2 包路径变为 `org.springframework.ai.support` | 用新路径 |
| `toolDefinition cannot be null` | 1.0.0-M6 的 builder 不自动推断 | 升级到 1.1.2 或手动构建 definition |
| `Multiple tools with the same name` | `.defaultTools()` 和 `.tools()` 同时用了，工具被注册两次 | 只在一处注册 |
| 工具不调用（`toolCalls=[]`） | 没有显式传 `.tools()`，自动扫描不会自动加入请求 | 在 prompt 调用链上加 `.tools(...)` |

---

## 4. 在 ChatClient 中使用工具

### 4.1 每请求指定工具

```java
// 方式 A：传 @Tool 注解的原始对象 → 内部调 ToolCallbacks.from()
String response = chatClient.prompt()
    .user("What day is tomorrow?")
    .tools(new DateTimeTools())
    .call()
    .content();

// 方式 B：传已构建的 ToolCallback 实例
String response = chatClient.prompt()
    .user("What's the weather in Copenhagen?")
    .toolCallbacks(toolCallback)    // 注意：是 toolCallbacks() 不是 tools()
    .call()
    .content();

// 方式 C：按名字动态解析（配合 @Bean + ToolCallbackResolver）
String response = chatClient.prompt()
    .user("What's the weather in Copenhagen?")
    .toolNames("currentWeather")
    .call()
    .content();
```

**关键区分**：
- `.tools(Object...)` — 接收原始对象（有 `@Tool` 注解），内部调 `ToolCallbacks.from()`
- `.toolCallbacks(ToolCallback...)` — 接收已构建好的 ToolCallback
- `.toolNames(String...)` — 按名字从 ToolCallbackResolver 查找

### 4.2 默认工具（全局绑定）

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultTools(new DateTimeTools())        // @Tool 原始对象
    .defaultToolCallbacks(toolCallback)       // ToolCallback 实例
    .defaultToolNames("currentWeather")       // 按名解析
    .build();
```

**覆盖规则**：如果同时设了 default 和 per-request，per-request **完全覆盖** default。

### 4.3 本项目用法

```java
// LoveApp.java — 只在需要工具的方法中显式传入
public ChatClientResponse doChatWithTools(String message, String chatId) {
    return this.chatClient
        .prompt()
        .user(message)
        .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
        .tools(fileOperationTool, webSearchTool, webScrapingTool,
               terminalTool, resourceDownloadTool, pdfGenerationTool)
        .call()
        .chatClientResponse();
}
```

---

## 5. 在 ChatModel 中使用（底层 API）

```java
ToolCallback[] tools = ToolCallbacks.from(new DateTimeTools());

ChatOptions options = ToolCallingChatOptions.builder()
    .toolCallbacks(tools)
    .build();

Prompt prompt = new Prompt("What day is tomorrow?", options);
ChatResponse response = chatModel.call(prompt);
```

---

## 6. 核心接口架构

### 6.1 ToolCallback（中心接口）

```java
public interface ToolCallback {
    ToolDefinition getToolDefinition();   // name + description + input schema
    ToolMetadata getToolMetadata();       // returnDirect 等元数据
    String call(String input);            // 执行工具，接收原始 JSON 参数
    String call(String input, ToolContext context); // 带上下文
}
```

内置实现：`MethodToolCallback`（@Tool 方法）、`FunctionToolCallback`（Function 接口）。

### 6.2 ToolDefinition（工具的"说明书"）

```java
public interface ToolDefinition {
    String name();            // 工具唯一名
    String description();     // 告诉模型这工具干啥
    String inputSchema();     // JSON Schema，告诉模型需要哪些参数
}
```

手动构建：

```java
ToolDefinition def = DefaultToolDefinition.builder()
    .name("searchWeb")
    .description("Search the web for information")
    .inputSchema("""
        {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
        """)
    .build();
```

从方法自动生成（1.1.2）：

```java
ToolDefinition def = ToolDefinitions.builder(method)
    .description("Custom description")
    .build();
```

### 6.3 ToolCallingManager（执行中枢）

```java
public interface ToolCallingManager {
    List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options);
    ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response);
}
```

默认实现 `DefaultToolCallingManager`，由 Spring Boot starter 自动配置。

### 6.4 ToolCallbackResolver（按名查找）

```java
public interface ToolCallbackResolver {
    ToolCallback resolve(String toolName);
}
```

默认 `DelegatingToolCallbackResolver` 串联两个解析器：
1. `SpringBeanToolCallbackResolver` — 查找 `@Bean` 注册的 Function
2. `StaticToolCallbackResolver` — 查找 ToolCallback Bean 列表

---

## 7. Tool Calling 完整执行流程

```
1. Client 发送请求
   ├── user 消息 + tools 列表（name + description + JSON Schema）
   └── 每个 tool 的 Schema 是"说明书"

2. 模型决策
   ├── 理解用户意图
   ├── 遍历 tools 列表
   ├── 匹配 description → 决定调哪个
   ├── 读 inputSchema → 提取参数值
   └── 返回 ToolCall(name="searchWeb", arguments='{"query":"天气"}')

3. DefaultToolCallingManager 拦截 ToolCall
   ├── 按 name 找到 MethodToolCallback
   ├── arguments JSON → Map → 按方法参数顺序排列
   └── method.invoke(toolObject, args) → 反射调用

4. 结果序列化
   ├── void 方法 → "Done"
   └── 有返回值 → JSON 序列化

5. 结果拼回对话历史
   ├── assistant: tool_call(name="searchWeb", arguments={...})
   └── tool: "搜索结果：..."

6. 模型基于工具结果生成最终回复
```

### 反射调用核心：Method.invoke()

```java
// MethodToolCallback 核心逻辑
public String call(String toolInput) {
    // toolInput = '{"query":"上海天气"}'  ← 模型返回的 JSON

    // Step 1: JSON → Map
    Map<String, Object> args = JsonParser.fromJson(toolInput);
    // → { query: "上海天气" }

    // Step 2: 按方法参数顺序排列
    Object[] methodArgs = new Object[]{"上海天气"};

    // Step 3: 反射调用
    Object result = toolMethod.invoke(toolObject, methodArgs);
    // ↑ 等效于 webSearchTool.searchWeb("上海天气")

    // Step 4: 返回值转字符串
    return toolCallResultConverter.convert(result, returnType);
}
```

---

## 8. ToolCallAdvisor（Advisor 模式）

除了内部的 DefaultToolCallingManager，也可以用 Advisor 来控制工具调用：

```java
ToolCallAdvisor advisor = ToolCallAdvisor.builder()
    .toolCallingManager(toolCallingManager)
    .advisorOrder(BaseAdvisor.HIGHEST_PRECEDENCE + 300)
    .build();

ChatClient client = ChatClient.builder(chatModel)
    .defaultAdvisors(advisor)
    .build();
```

优点：位于 Advisor 链中，可以和其他 Advisor（记忆、日志等）协同，更方便做可观测性。

### 用户手动控制循环

如果不想让框架自动循环，可以自己控制：

```java
ChatOptions options = ToolCallingChatOptions.builder()
    .toolCallbacks(new MyTools())
    .internalToolExecutionEnabled(false)   // 关闭内部自动执行
    .build();

ChatResponse response = chatModel.call(new Prompt("...", options));

while (response.hasToolCalls()) {
    ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
    prompt = new Prompt(result.conversationHistory(), options);
    response = chatModel.call(prompt);
}
```

---

## 9. 其他重要特性

### 9.1 ToolContext（传递上下文）

给工具传递模型看不到的上下文数据（如租户 ID、用户会话）：

```java
@Tool(description = "Retrieve customer info")
Customer getCustomer(Long id, ToolContext ctx) {
    String tenantId = ctx.getContext().get("tenantId");
    return repo.findById(id, tenantId);
}

// 调用时提供 context
chatClient.prompt("...")
    .tools(new CustomerTools())
    .toolContext(Map.of("tenantId", "acme"))
    .call()
    .content();
```

### 9.2 异常处理

工具抛异常时，`ToolExecutionExceptionProcessor` 决定行为：

```java
@FunctionalInterface
public interface ToolExecutionExceptionProcessor {
    String process(ToolExecutionException exception);
}
```

默认行为：RuntimeException → 错误消息喂回模型；受检异常 → 直接 throw。

配置属性：`spring.ai.tools.throw-exception-on-error`（默认 false）

### 9.3 Return Direct

工具结果直接返回给调用方，不喂给模型：

```java
@Tool(description = "Get customer info", returnDirect = true)
Customer getCustomer(Long id) { ... }

// 编程式
MethodToolCallback.builder()
    .toolMetadata(ToolMetadata.builder().returnDirect(true).build())
    .build();
```

### 9.4 结果转换

默认 `DefaultToolCallResultConverter` 用 Jackson 序列化为 JSON。可自定义：

```java
@Tool(description = "...", resultConverter = MyConverter.class)
MyResult doSomething() { ... }
```

---

## 10. 版本差异速查

| 特性 | 1.0.0-M6 | 1.1.2 |
|---|---|---|
| ToolCallbacks 包路径 | `o.s.ai.tool.ToolCallbacks` | `o.s.ai.support.ToolCallbacks` |
| 所在模块 | spring-ai-core | spring-ai-model |
| builder 自动推断 ToolDefinition | ❌ 必须手动 | ✅ 自动 |
| `.tools()` 参数类型 | ToolCallback[] | 原始对象（@Tool 注解） |
| `.toolCallbacks()` | 无 | 接收 ToolCallback[] |
| MethodToolCallbackProvider | 有 | 有（但需显式传 toolObjects） |

---

## 11. 项目实战总结

### 工具定义 → 注册 → 调用全链路

```
① 定义工具
   @Component + @Tool 方法
   FileOperationTool / WebSearchTool / PDFGenerationTool ...

② 注入 LoveApp
   @Autowired
   private FileOperationTool fileOperationTool;
   ...

③ 在请求中绑定
   chatClient.prompt()
       .tools(fileOperationTool, webSearchTool, ...)
       .call()

④ 模型决策 → 执行
   - 用户说"搜索上海打卡地" → 模型调 searchWeb("上海打卡地")
   - 用户说"保存文件"       → 模型调 saveFile("档案.txt", content)
   - 用户说"生成PDF"        → 模型调 generatePDF("计划.pdf", content)
```

### 关键教训

- **DashScope deepseek-v4-pro 工具调用可行，但 `MyLogAdvisor` 看不到中间过程**。想调试工具调用，给每个 `@Tool` 方法加 `System.out.println`。
- **不要同时用 `.defaultTools()` 和 `.tools()`**：会重复注册，报 `Multiple tools with the same name`。
- **工具不加 `.tools()` 不会被发送**：Spring AI 自动扫描只是"知道"有这些工具，不意味着每次请求都带上。
- **System Prompt 影响工具调用**：项目 System Prompt 强调"你是恋爱助手"，所以模型对于下载图片、执行命令等请求会先推辞。想测试工具，Prompt 里要减少角色约束。

---

## 12. MCP 协议集成：ToolCallbackProvider + 独立 MCP Server

### 12.1 架构全景

项目同时使用了**本地工具**和**MCP 远程工具**。看 `InterviewApp.java:110-122` 的 `doChat()` 方法：

```java
return clients.get(strategy)
    .prompt()
    .user(message)
    .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId).param("userId", userId))
    .tools(fileOperationTool, webSearchTool, webScrapingTool,     // 6 个本地工具
           terminalTool, resourceDownloadTool, pdfGenerationTool)
    .toolCallbacks(toolCallbackProvider)                          // MCP 外部工具
    .stream()
    .content();
```

**本地工具（`.tools()`）**：直接注入为 Spring Bean，和主应用在同一 JVM 进程。适合高频、低延迟、不需要独立部署的工具。

**MCP 工具（`.toolCallbacks()`）**：通过 `ToolCallbackProvider` 注入。每个 MCP Server 是独立 Java 进程，通过 stdio 通信。适合需要独立环境、可复用、需要独立扩展的工具。

### 12.2 MCP Server 注册机制

`mcp-servers.json` 配置了 MCP 客户端如何启动外部 Server：

```json
{
  "mcpServers": {
    "mcp-server-send-email": {
      "command": "java",
      "args": ["-jar", "mcp-server-send-email/target/mcp-server-send-email-0.0.1-SNAPSHOT.jar"],
      "env": {}
    }
  }
}
```

Spring AI MCP Client 在启动时的工作流程：

```
1. 读取 mcp-servers.json
2. 为每个 Server 启动独立 Java 进程（java -jar ...）
3. 通过 stdio（标准输入/输出）建立 MCP 协议通信（JSON-RPC）
4. MCP Server 启动后通过协议暴露自己的工具列表（tools/list）
5. Client 端将工具注册到 ToolCallbackProvider
6. InterviewApp 通过 @Autowired(required = false) 注入
```

**为什么用 stdio 而不是 HTTP？** stdio 不需要独立端口，不需要网络配置，启动简单——适合本地开发和单机部署。生产环境可换 HTTP/SSE 传输（只需改 `mcp-servers.json` 的 `type` 字段）。

### 12.3 EmailTool 实现与 LLM 触发机制

`EmailTool.java:26-48`（在独立的 `mcp-server-send-email` 模块中）：

```java
@McpTool(description = "发送面试报告邮件到HR邮箱。当求职者面试评分优秀" +
    "（如简历分析>90分且模拟面试>90分）时调用，将面试评估结果和求职者信息推送给HR邮箱")
public String sendEmail(
    @McpToolParam(description = "收件人邮箱地址，如 hr@company.com") String to,
    @McpToolParam(description = "邮件标题") String subject,
    @McpToolParam(description = "邮件正文，支持 HTML 格式") String content) {

    MimeMessage message = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
    helper.setFrom(from);
    helper.setTo(to);
    helper.setSubject(subject);
    helper.setText(content, true);   // true = 支持 HTML
    mailSender.send(message);
}
```

**核心设计：`@McpTool` 的 `description` 决定了 LLM 什么时候调这个工具，而不是硬编码的 `if (score > 90)`。**

- `description` 是给 LLM 看的 Tool Definition，LLM 读到"简历分析>90分且模拟面试>90分时调用"后，在对话中自主判断是否满足触发条件
- 改触发条件只需改 `description` 字符串，不用改任何 Java 逻辑
- 比硬编码灵活——"优秀候选人"的标准可以随时用自然语言调整

**完整触发链路**：

```
用户："这份简历分析完了，候选人张三的综合评分 92 分"

LLM 推理过程：
  1. 查看所有 Tool Definitions：
     - sendEmail: "当求职者面试评分优秀（简历分析>90分...）时调用"
     - 本地 6 个工具...

  2. 判断：92 分 > 90 分 → 满足触发条件
  3. LLM 输出 function call：调 sendEmail(to="hr@company.com",
     subject="面试报告：张三（综合评分 92）", content="<html>...详细报告...</html>")

  4. Spring AI MCP Client 拦截 function call
     → 通过 stdio 发 JSON-RPC 请求到 mcp-server-send-email 进程
     → Tools/Call: {name: "sendEmail", arguments: {to: "...", subject: "...", content: "..."}}

  5. EmailTool.sendEmail() 执行 → JavaMailSender 发送邮件
  6. 结果通过 MCP 协议返回 → Spring AI 喂回 LLM
  7. LLM 生成最终回复："邮件已发送给 HR，张三的面试报告已推送"
```

### 12.4 stdio JSON-RPC 2.0：传输层与消息格式详解

#### 12.4.1 什么是 stdin / stdout / stderr

每个程序启动时，操作系统自动给它接上三根管道：

```
操作系统的角度看一个进程：
                    ┌─────────────┐
        stdin  ────→│   你的程序   │────→  stdout
      （标准输入）   │    (进程)    │     （标准输出）
                    │             │────→  stderr
                    └─────────────┘     （标准错误）
```

- **stdin（编号 0）**：数据流进来，程序从这读
- **stdout（编号 1）**：数据流出去，程序往这写
- **stderr（编号 2）**：错误信息流出去，和 stdout 分开

这三根管子在操作系统层面是文件描述符，用法和文件一模一样：读、写。

**日常例子 — 管道 `|`**：

```bash
echo "hello" | grep "he"
# PowerShell 等价：echo "hello" | Select-String "he"
```

```
echo 进程                    grep 进程
┌──────────┐    stdout   ┌──────────┐
│  echo    │─────────────→│  grep    │
│          │    stdin     │          │
└──────────┘             └──────────┘

1. echo 往自己的 stdout 写 "hello\n"
2. 管道 | 把 echo 的 stdout 直接接到 grep 的 stdin
3. grep 从自己的 stdin 读到 "hello\n"，匹配后输出
```

管道本质上就是把进程 A 的 stdout 和进程 B 的 stdin 对接在一起。

#### 12.4.2 MCP 中 stdio 怎么用的

项目配置 `mcp-servers.json`：

```json
{
  "mcpServers": {
    "mcp-server-send-email": {
      "command": "java",
      "args": ["-jar", "mcp-server-send-email/target/xxx.jar"]
    }
  }
}
```

主应用启动 MCP Server 时，底层等价于：

```java
// 启动子进程，同时拿到它的 stdin/stdout 管道
ProcessBuilder pb = new ProcessBuilder("java", "-jar", "mcp-server-send-email.jar");
Process mcpProcess = pb.start();

// 拿到三根管道：
OutputStream mcpStdin  = mcpProcess.getOutputStream();   // 主应用往这写 → MCP Server 的 stdin
InputStream  mcpStdout = mcpProcess.getInputStream();    // 主应用从这读 ← MCP Server 的 stdout
InputStream  mcpStderr = mcpProcess.getErrorStream();    // 错误日志
```

MCP Server 那边反过来：

```java
// MCP Server 从自己的 stdin（System.in）读请求
Scanner sc = new Scanner(System.in);
String requestJson = sc.nextLine();

// 执行 EmailTool.sendEmail() 后，往 stdout（System.out）写响应
System.out.println("{\"jsonrpc\":\"2.0\",\"result\":{...}}");
```

**通信就是在这两根管道上读写 JSON 数据。**

#### 12.4.3 JSON-RPC 2.0：消息格式是协议强制规定

MCP 协议由 Anthropic 发布，规范明确写死了消息格式必须是 JSON-RPC 2.0。不由双方商量，和 HTTP 协议规定请求格式一样——强制遵守。

```
┌─────────────────────────────────────────┐
│  传输层（怎么连？）                       │
│  stdio / HTTP+SSE / Streamable HTTP     │ ← 可以选，项目选 stdio
├─────────────────────────────────────────┤
│  消息格式（传什么格式？）                  │
│  JSON-RPC 2.0                           │ ← 固定的，MCP 协议写死的
├─────────────────────────────────────────┤
│  业务语义（调的是哪个工具？）              │
│  initialize, tools/list, tools/call ... │ ← 固定的，MCP 协议定义
├─────────────────────────────────────────┤
│  工具定义（工具叫什么、什么参数）           │
│  sendEmail, to, subject, content        │ ← 你通过 @McpTool 定义
└─────────────────────────────────────────┘
```

#### 12.4.4 JSON-RPC 2.0 固定格式

**请求**（四个必填字段）：

```json
{
  "jsonrpc": "2.0",      // ← 固定值，永远是 "2.0"
  "id": 1,               // ← 请求编号，响应时原样返回，用于匹配
  "method": "...",       // ← 方法名，由 MCP 协议定义（不能自己发明）
  "params": {...}        // ← 参数，格式由 method 决定
}
```

**响应**（成功 / 失败两个分支）：

```json
// 成功：
{"jsonrpc": "2.0", "id": 1, "result": {...}}

// 失败：
{"jsonrpc": "2.0", "id": 1, "error": {"code": -32600, "message": "Invalid Request"}}
```

**`method` 的合法取值也是 MCP 协议规定的，不能自己发明**：

| method | 方向 | 作用 |
|--------|------|------|
| `initialize` | 客户端→服务端 | 握手，交换协议版本和能力 |
| `tools/list` | 客户端→服务端 | 查询有哪些工具可用 |
| `tools/call` | 客户端→服务端 | 调用指定工具 |
| `resources/list` | 客户端→服务端 | 查询资源列表 |
| `resources/read` | 客户端→服务端 | 读取资源内容 |
| `notifications/...` | 双向 | 通知（不需要响应） |

#### 12.4.5 项目的实际消息序列

主应用启动 MCP Server 后，在 stdio 管道上发送的 JSON-RPC 消息序列：

```
第一步：握手（initialize）
→ {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",...}}
← {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","serverInfo":{"name":"mcp-server-send-email",...}}}

第二步：询问工具列表（tools/list）
→ {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
← {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"sendEmail","description":"发送面试报告邮件...","inputSchema":{...}}]}}

第三步：调用工具（tools/call）
→ {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sendEmail","arguments":{...}}}
← {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"邮件发送成功"}]}}
```

#### 12.4.6 各层的灵活性

| 层 | 能改吗？ | 谁决定的 |
|----|---------|---------|
| 传输层（stdio / HTTP / SSE） | ✅ 可以选 | 部署时配 `mcp-servers.json` 的 type 字段 |
| 消息格式（JSON-RPC 2.0） | ❌ 不能改 | MCP 协议规范 |
| 方法名（`tools/call` 等） | ❌ 不能改 | MCP 协议规范 |
| 工具名（`sendEmail`） | ✅ 你定义 | `@McpTool` 的方法名 |
| 工具描述 | ✅ 你写 | `@McpTool(description="...")` |
| 工具参数 | ✅ 你定义 | `@McpToolParam` 注解 |

**一句话**：底层通信格式（JSON-RPC 2.0）和协议方法（`tools/call`）是 MCP 规范强制规定的，和 HTTP 协议规定请求格式一样。你只能在工具定义层自由发挥——工具叫什么名字、什么参数、什么描述。下层全由 `spring-ai-starter-mcp-client` 按照 MCP 规范自动处理，代码里不需要手写任何 JSON-RPC 消息。

#### 12.4.7 stdio vs HTTP 传输对比

```
stdio 方式（项目当前）：
  mcp-servers.json 里写：command: "java", args: ["-jar", "xxx.jar"]
  → 主应用启动子进程，走标准输入输出管道
  → 零网络配置
  → MCP Server 必须和主应用在同一台机器

HTTP 方式（生产环境可选）：
  mcp-servers.json 里写：url: "http://email-service:8128/mcp"
  → MCP Server 可部署在独立机器上，独立扩缩容
  → 需要配端口、网络、服务发现
```

无论选 stdio 还是 HTTP，上层的 JSON-RPC 2.0 消息格式和 MCP 协议方法（`tools/list`、`tools/call`）完全一样——只换传输层，不换协议层。

### 12.5 MCP 协议通信流程

```yaml
# mcp-server-send-email/src/main/resources/application.yml
spring:
  mail:
    host: smtp.qq.com
    port: 587
    username: ${MAIL_USERNAME:}     # 从环境变量注入，不硬编码
    password: ${MAIL_PASSWORD:}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
            required: true
```

**为什么邮件服务独立为一个 MCP Server 而不是直接在主应用里写一个 `@Tool`？**

| 维度 | 直接在主应用中写 @Tool | 独立 MCP Server |
|------|---------------------|----------------|
| 配置隔离 | 邮件配置混在主应用 yml 中 | 独立配置文件，互不干扰 |
| 依赖隔离 | spring-mail 依赖全部打入主应用 | 独立依赖，独立 classpath |
| 复用性 | 仅本应用可用 | 其他项目直接配 `mcp-servers.json` 即可复用 |
| 容错 | 邮件服务崩溃 → 主应用崩溃 | 邮件服务崩溃 → 主应用降级运行 |

### 12.6 容错设计

```java
// InterviewApp.java:54-55
@Autowired(required = false)
private ToolCallbackProvider toolCallbackProvider;
```

```java
// InterviewApp.java:57-69
@PostConstruct
public void init() {
    if (toolCallbackProvider != null) {
        ToolCallback[] tools = toolCallbackProvider.getToolCallbacks();
        log.info(">>> [MCP] 工具总数: {}", tools.length);
    } else {
        log.warn(">>> [MCP] ToolCallbackProvider 未注入!");
    }
    log.info(">>> [Tool Calling] 本地工具: ... (共6个)");
}
```

**`required = false` 的含义**：MCP Server 的 jar 包可能还没编译、或者进程启动失败。`required = false` 保证主应用不会因为 MCP 工具不可用而启动失败——降级运行，本地 6 个工具仍然可用。

同样，`@PostConstruct` 里的 null check 保证即使 `toolCallbackProvider` 为 null，也不会 NPE，只是打一个 warn 日志。

### 12.7 @McpTool vs @Tool 对比

| | @Tool（本地工具） | @McpTool（MCP 工具） |
|---|---|---|
| 注解来源 | `org.springframework.ai.tool.annotation.Tool` | `org.springaicommunity.mcp.annotation.McpTool` |
| 运行位置 | 主应用 JVM | 独立 MCP Server 进程 |
| 通信方式 | 直接方法调用 | MCP 协议（JSON-RPC over stdio） |
| 注册方式 | `ToolCallbacks.from()` 或 `.tools()` | `ToolCallbackProvider` 自动发现 |
| 适用工具 | 高频、低延迟、不需隔离 | 需独立环境、可复用、需隔离 |

**两者可以共存**——项目就是 `.tools(local1, local2, ...)` + `.toolCallbacks(mcpProvider)` 同时使用，LLM 统一决策调哪个。

### 12.8 面试回答模板

> "MCP 工具和本地工具的分工是：本地 6 个工具直接注入为 Spring Bean，和主应用同一 JVM；邮件和图片搜索通过 MCP 协议以独立进程运行，主应用通过 `ToolCallbackProvider` 自动发现和注入。这样做三个好处：第一，邮件服务需要独立的 mail 配置和 spring-mail 依赖，独立进程避免污染主应用；第二，MCP Server 可被多个项目复用——只需配 `mcp-servers.json`；第三，stdio 传输零网络配置，本地开发启动简单。
>
> 工具调用的触发不是硬编码的 `if (score > 90)` 阈值判断，而是靠 `@McpTool` 的 `description` 用自然语言描述触发条件——LLM 读到'评分>90分时调用'后自主决定。这比硬编码灵活，改条件只需改 description 字符串。容错上 `@Autowired(required = false)` 保证 MCP 不可用时主应用降级运行。"
