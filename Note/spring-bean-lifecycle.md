# Spring Bean 生命周期详解

> 以项目实际代码逐行追溯 Bean 从创建到销毁的完整链路。
> 项目代码：`InterviewApp.java`、`ResumeAnalysisService.java`、`RagConfig.java`、`ChatClientFactory.java`、`KnowledgeBaseInitializerConfig.java`

---

## 1. 全景图

```
┌─────────────────────────────────────────────────────────────────┐
│                    Bean 生命周期（单例）                          │
├─────────────────────────────────────────────────────────────────┤
│  ① 实例化      构造器 / @Bean 工厂方法（反射调用）                │
│  ② 属性填充    @Autowired 字段注入                               │
│  ③ BeanPostProcessor.postProcessBeforeInitialization()          │
│  ④ @PostConstruct 方法                                          │
│  ⑤ InitializingBean.afterPropertiesSet()                        │
│  ⑥ BeanPostProcessor.postProcessAfterInitialization() ← AOP 代理│
│  ⑦ 就绪 → 放入单例池 singletonObjects                            │
│  ... 使用中 ...                                                  │
│  ⑧ @PreDestroy 方法                                             │
│  ⑨ DisposableBean.destroy()                                     │
│  ⑩ 销毁                                                         │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 阶段 ①：实例化 — 对象怎么被"造"出来的

项目里有两条路径：

### 路径 A：`@Component` 类 → 反射调构造器

```java
// InterviewApp.java:71-78 — 构造器注入
public InterviewApp(ChatClientFactory factory, ChatModel chatModel,
                    @Value("classpath:/prompts/interview-system-prompt.st") Resource systemResource) {
    this.systemPrompt = loadSystemPrompt(systemResource);
    this.clients = factory.buildAll(systemPrompt);
    this.classifier = ChatClient.builder(chatModel)
            .defaultSystem("你是一个查询分类器。只输出以下4个词之一，不要解释: FACT FOLLOW_UP COMPLEX CHAT")
            .build();
}
```

Spring 内部做的事（伪代码还原）：

```java
// 1. 读构造器参数类型: [ChatClientFactory, ChatModel, Resource]
// 2. 嵌套调 getBean() 拿依赖
ChatClientFactory f  = beanFactory.getBean(ChatClientFactory.class);
ChatModel m          = beanFactory.getBean(ChatModel.class);
Resource r           = 加载 classpath 下的文件; // @Value 处理
// 3. 反射调构造器
InterviewApp bean = ctor.newInstance(f, m, r);
```

### 路径 B：`@Configuration` + `@Bean` → 反射调工厂方法

```java
// RagConfig.java:16-18
@Bean
public DashScopeApi dashScopeApi(@Value("${spring.ai.dashscope.api-key}") String apiKey) {
    return DashScopeApi.builder().apiKey(apiKey).build();
}
```

Spring 做的事：

```java
// RagConfig 的实例已经从容器拿到（且有 CGLIB 代理，详见第 4 节）
Object ragConfig = beanFactory.getBean("ragConfig");
// 反射调工厂方法
DashScopeApi api = ragConfig.getClass()
    .getMethod("dashScopeApi", String.class)
    .invoke(ragConfig, "sk-xxxx");
```

关键区别：`@Component` 类走构造器，`@Bean` 方法走工厂方法。最终都是反射调用。

---

## 3. 为什么反射调构造器造出来的还是"毛坯"

核心：**构造器只保证构造器参数和构造器体内显式赋值的字段被填了。** `@Autowired` 字段注入的依赖在 ① 时还是 null。

### 项目实际例子

`InterviewApp.java` 混用了两种注入方式：

```java
@Component
public class InterviewApp {

    // ═══════ 构造器注入（① 就填好了）═══════
    private final Map<RagStrategy, ChatClient> clients;   // ✅
    private final String systemPrompt;                     // ✅
    private final ChatClient classifier;                   // ✅

    // ═══════ @Autowired 字段注入（① 时是 null）═══════
    @Autowired private FileOperationTool fileOperationTool;          // null
    @Autowired private WebSearchTool webSearchTool;                  // null
    @Autowired private WebScrapingTool webScrapingTool;              // null
    @Autowired private TerminalTool terminalTool;                    // null
    @Autowired private ResourceDownloadTool resourceDownloadTool;    // null
    @Autowired private PDFGenerationTool pdfGenerationTool;          // null
    @Autowired(required = false)
    private ToolCallbackProvider toolCallbackProvider;               // null
}
```

**① 结束后对象状态**：

```
InterviewApp@9012
  ├─ clients: ✅           ← 构造器填的
  ├─ systemPrompt: ✅      ← 构造器填的
  ├─ classifier: ✅        ← 构造器填的
  ├─ fileOperationTool: null     ← 构造器没管
  ├─ webSearchTool: null         ← 构造器没管
  └─ ... (其他 5 个也是 null)
```

**为什么不全部塞进构造器？** 如果 7 个工具也走构造器注入，参数列表爆炸（10 个参数）。折中：核心依赖走构造器（保证 `final`），工具类走 `@Autowired` 字段注入（灵活解耦）。代价就是构造器执行完那一刻，`@Autowired` 字段还是 null。

### ① → ② 的精确过渡

```java
// Spring 源码：populateBean()
for (Field field : InterviewApp.class.getDeclaredFields()) {
    Autowired anno = field.getAnnotation(Autowired.class);
    if (anno != null) {
        Object value = beanFactory.getBean(field.getType());
        // field == fileOperationTool → getBean(FileOperationTool.class)
        field.set(bean, value);  // 反射设进去
    }
}
// 执行完，所有字段都有值——不再是毛坯
```

**类比**：砌房子时，构造器 = 打地基 + 砌墙 + 盖屋顶，房子能站住（结构完整），但水电煤气（`@Autowired` 字段）还没通——这就是"毛坯"。`populateBean()` = 水电工进场接通管线。

---

## 4. 阶段 ③④⑤⑥：初始化 — "毛坯"到"精装"

四个阶段在 Spring 源码里封装在 `initializeBean()` 方法中依次执行：

### ③ BeanPostProcessor.before — 拦截点 1

Spring Boot 内置了几十个 `BeanPostProcessor`，在这步执行。项目里没自定义，但比如 `CommonAnnotationBeanPostProcessor` 会在这步找到 `@PostConstruct` 方法，准备下一步执行。

### ④ @PostConstruct — 依赖注入完成后的初始化

项目里两个地方用了：

```java
// InterviewApp.java:57-69
@PostConstruct
public void init() {
    if (toolCallbackProvider != null) {
        ToolCallback[] tools = toolCallbackProvider.getToolCallbacks();
        log.info(">>> [MCP] 工具总数: {}", tools.length);
        for (ToolCallback tc : tools) {
            log.info(">>> [MCP] 工具: {}", tc.getToolDefinition());
        }
    }
}
```

```java
// ResumeAnalysisService.java:45-51
@PostConstruct
public void init() {
    File dir = new File(resumeDir);
    if (!dir.exists()) dir.mkdirs();
    consumerExecutor.submit(this::consumeLoop);  // 启动 Redis 消费者线程
}
```

**为什么需要 @PostConstruct？**

- `InterviewApp.init()` 需要 `toolCallbackProvider` 已经被 `@Autowired` 注入（② 完成了），才能遍历 MCP 工具
- `ResumeAnalysisService.init()` 需要在构造完成后启动后台线程——如果放构造器里，`@Value resumeDir` 字段还没注入

### ⑤ InitializingBean.afterPropertiesSet()

项目里没用到。与 @PostConstruct 的关系：**先执行 @PostConstruct，再执行 afterPropertiesSet()**。

### ⑥ BeanPostProcessor.after — 拦截点 2（AOP 代理在这生成）

这是最关键的一步。如果 Bean 需要 AOP 增强（`@Transactional`、`@Async`），Spring 在这步创建动态代理：

```java
// Spring 源码：AbstractAutoProxyCreator
if (需要代理) {
    Object proxy = createProxy(bean);  // CGLIB 或 JDK 动态代理
    return proxy;  // ← 返回的是代理对象，不是原对象
}
return bean;
```

这一步之后，容器里存的、别人拿到的，都是代理对象。源码不动，外面套一层代理壳。

---

## 5. 阶段 ⑦：就绪 → 入单例池

```java
// DefaultSingletonBeanRegistry.addSingleton()
singletonObjects.put("interviewApp", exposedObject);
```

本质就是放进一个 `ConcurrentHashMap<String, Object>`。

**最先被用的是 CommandLineRunner**：

```java
// KnowledgeBaseInitializerConfig.java:52
@Override
public void run(String... args) throws Exception {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    // Tika 读取 → 切分 → AI 关键词 → 分批写入 PGVector
}
```

::: tip 类比
所有房客（Bean）都入住后，物业（Spring）敲门说"可以开始使用了"。
:::

---

## 6. 阶段 ⑧⑨⑩：销毁 — 容器的优雅关闭

### ⑧ @PreDestroy — 清理资源

```java
// ResumeAnalysisService.java:53-57
@PreDestroy
public void destroy() {
    running = false;                 // ① 通知消费者循环停止
    consumerExecutor.shutdownNow();  // ② 强制关闭线程池
}
```

**为什么需要这步？** 不手动 `shutdownNow()`，JVM 不会自动杀死线程池里的线程，可能导致进程关不掉、Redis 连接没释放、正在处理的任务丢了一半。

### ⑨ DisposableBean.destroy()

项目里没用到，与 `InitializingBean` 对应。

### ⑩ 彻底销毁

单例池移除，GC 回收。

---

## 7. 项目实际时间线（启动 → 关闭）

```
SpringApplication.run()
│
├─ ① 实例化 RagConfig → 调 @Bean 方法 → DashScopeApi@1234
├─ ② 属性填充 — 项目全用构造器注入，这步基本是 no-op
├─ ③④⑤⑥ 初始化
├─ ⑦ 入单例池
│
├─ ... 同样流程创建 ChatClientFactory, InterviewApp, ResumeAnalysisService ...
│
├─ ⑦ InterviewApp 入池
│   └─ ④ @PostConstruct: InterviewApp.init() → 打印 MCP 工具列表
│
├─ ⑦ ResumeAnalysisService 入池
│   └─ ④ @PostConstruct: ResumeAnalysisService.init() → 启动 Redis 消费者线程
│
├─ 所有 Bean 就绪
│   └─ KnowledgeBaseInitializerConfig.run() → ETL Pipeline
│
├─ Web 容器启动，接收请求...
│
├─ 关闭信号
│   ├─ ⑧ @PreDestroy: ResumeAnalysisService.destroy() → 关闭线程池
│   ├─ ⑩ 单例池清空
│   └─ JVM 退出
```

---

## 8. 生命周期钩子对比与选择

| 钩子 | 时机 | 项目中用了没 | 适用场景 |
|------|------|-------------|---------|
| 构造器 | 实例化 | ✅ 全项目 | 注入依赖、初始化 `final` 字段 |
| `@PostConstruct` | 依赖注入完成后 | ✅ InterviewApp, ResumeAnalysisService | 依赖注入完成才能做的初始化 |
| `InitializingBean` | @PostConstruct 之后 | ❌ | 不推荐，侵入性强 |
| `CommandLineRunner` | 所有 Bean 就绪后 | ✅ KnowledgeBaseInitializerConfig | 启动后执行一次性任务 |
| `@PreDestroy` | 容器销毁前 | ✅ ResumeAnalysisService | 释放连接、关闭线程池、清理临时文件 |
| `DisposableBean` | @PreDestroy 之后 | ❌ | 不推荐，侵入性强 |

::: tip 一句话
90% 的场景只需要构造器 + @PostConstruct + @PreDestroy 三个钩子。
:::

---

## 9. @Configuration 类的 CGLIB 动态代理

### 9.1 从哪里看出来 RagConfig 被代理了？

**证据 1：类名带 `$$` 后缀**

如果在 `RagConfig` 的 `@PostConstruct` 里打印 `this.getClass().getName()`：

```
com.njupt.wirelesslabagent.config.RagConfig$$SpringCGLIB$$0
                                        ^^^^^^^^^^^^^^^^^^^^
```

不是你写的 `RagConfig` 本尊，是 CGLIB 在运行时动态生成的子类。

**证据 2：@Bean 方法间的单例语义**

如果在一个 `@Bean` 方法里调另一个 `@Bean` 方法：

```java
@Bean
public A a() { return new A(); }

@Bean
public B b() {
    A a1 = a();  // 第一次调
    A a2 = a();  // 第二次调
    System.out.println(a1 == a2);  // 输出 true ← 同一个对象！
}
```

按 Java 语法，每次 `new A()` 应该返回新对象。但 `==` 为 true，证明调的 `a()` 不是你自己写的 `a()`，而是 CGLIB 子类重写过的。

### 9.2 代理发生在生命周期的哪一步？

**不在 ①~⑩ 里，而是在那之前！**

```
AbstractApplicationContext.refresh()
  ├─ ① invokeBeanFactoryPostProcessors()     ← CGLIB 增强在这！！
  ├─ ② registerBeanPostProcessors()
  ├─ ③ finishBeanFactoryInitialization()
  └─ ④ finishRefresh()                       ← Bean 生命周期 ①~⑩ 在这之后
```

**CGLIB 增强在 BeanFactoryPostProcessor 阶段，Bean 还没创建。**

### 9.3 代理过程（三步走）

**步骤 1：`ConfigurationClassPostProcessor` 发现 `@Configuration` 类**

```java
// Spring 源码：ConfigurationClassPostProcessor
for (String beanName : registry.getBeanDefinitionNames()) {
    BeanDefinition bd = registry.getBeanDefinition(beanName);
    if (bd.getBeanClassName() 上的类有 @Configuration 注解) {
        // → 标记为 "full" 配置类（需要 CGLIB 增强）
    }
}
```

以项目为例：

| Bean 名 | 类 | 标记 |
|---------|---|------|
| `ragConfig` | `RagConfig` | `full` — 需要 CGLIB 增强 |
| `pgVectorConfig` | `PgVectorConfig` | `full` — 需要 CGLIB 增强 |
| `webMvcConfig` | `WebMvcConfig` | `lite` — 无 @Bean 方法，不走代理 |

**步骤 2：`ConfigurationClassEnhancer` 用 CGLIB 生成子类**

```java
// Spring 源码：ConfigurationClassEnhancer
Enhancer enhancer = new Enhancer();
enhancer.setSuperclass(RagConfig.class);    // 继承 RagConfig
enhancer.setCallbacks(new Callback[]{
    BeanMethodInterceptor.INSTANCE,          // 拦截 @Bean 方法
    BeanFactoryAwareMethodInterceptor.INSTANCE,
    NoOp.INSTANCE
});
return enhancer.createClass();  // 生成字节码 → 加载到 JVM 内存
```

CGLIB 底层在内存中生成类似这样的类：

```java
// 在内存中动态生成的子类（伪代码还原）
public class RagConfig$$SpringCGLIB$$0 extends RagConfig {

    private BeanFactory $$beanFactory;

    @Override
    public DashScopeApi dashScopeApi(String apiKey) {
        // 1. 先查容器：有没有已经创建好的？
        if ($$beanFactory.containsSingleton("dashScopeApi")) {
            return (DashScopeApi) $$beanFactory.getBean("dashScopeApi");
        }
        // 2. 第一次调用 → 调父类（你写的代码）
        DashScopeApi instance = super.dashScopeApi(apiKey);
        // 3. 注册到容器
        $$beanFactory.registerSingleton("dashScopeApi", instance);
        return instance;
    }

    @Override
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        // 同样的拦截逻辑
    }
}
```

**关键**：重写的方法名、参数、返回值必须与父类完全一致。这就是为什么 `@Bean` 方法不能是 `private` 或 `final`——CGLIB 通过继承重写，`final` 没法重写，`private` 子类不可见。

**步骤 3：替换 BeanDefinition 中的类**

```java
// 原来: com.njupt.wirelesslabagent.config.RagConfig
// 替换: com.njupt.wirelesslabagent.config.RagConfig$$SpringCGLIB$$0
bd.setBeanClassName("...RagConfig$$SpringCGLIB$$0");
```

从那以后，`beanFactory.getBean("ragConfig")` 创建的就不是你写的 `RagConfig` 了。

### 9.4 CGLIB 增强 vs AOP 动态代理

**原理相同（都是 CGLIB 生成子类），但时机和应用场景不同：**

| 对比维度 | @Configuration CGLIB 增强 | AOP 动态代理（如 @Transactional） |
|---------|--------------------------|----------------------------------|
| 发生阶段 | `BeanFactoryPostProcessor` | Bean 生命周期 **⑥** |
| 发生时间 | Bean 创建**之前** | Bean 创建**之后** |
| 作用对象 | `@Configuration` 类的 BeanDefinition | 有切面匹配的普通 Bean |
| 目的 | 保证 `@Bean` 方法间的单例语义 | 在方法调用前后织入横切逻辑 |
| 项目中体现 | `RagConfig`、`PgVectorConfig` | 项目没用 AOP |

时序关系：

```
BeanFactoryPostProcessor 阶段
  └─ RagConfig 的 BeanDefinition 被替换为 CGLIB 子类
       ↓
Bean 生命周期 ① 实例化
  └─ 创建的已经是 RagConfig$$SpringCGLIB$$0 的实例
       ↓
Bean 生命周期 ⑥ postProcessAfterInitialization
  └─ 如果 InterviewApp 加了 @Transactional，在这里创建 AOP 代理
       ↓
Bean 生命周期 ⑦ 入池
```

**RagConfig 的代理比 InterviewApp 的 AOP 代理早了整整 6 个阶段。**

---

## 10. 完整源码对照

入口：`AbstractAutowireCapableBeanFactory.doCreateBean()`

```java
@Override
protected Object doCreateBean(String beanName, RootBeanDefinition mbd, Object[] args) {

    // ① 实例化：反射调构造器（或 @Bean 工厂方法）
    BeanWrapper instanceWrapper = createBeanInstance(beanName, mbd, args);
    Object bean = instanceWrapper.getWrappedInstance();  // "毛坯"

    // ② 属性填充：@Autowired/@Value 字段注入
    populateBean(beanName, mbd, instanceWrapper);

    // ③④⑤⑥ 初始化（四步合一）
    Object exposedObject = initializeBean(beanName, bean, mbd);
    //   ↓ 展开：
    //   ③ applyBeanPostProcessorsBeforeInitialization(bean, beanName);
    //   ④ invokeInitMethods(beanName, bean, mbd);  // @PostConstruct
    //   ⑤ afterPropertiesSet()                      // InitializingBean
    //   ⑥ applyBeanPostProcessorsAfterInitialization(bean, beanName);
    //     ↑ AOP 代理在这里生成

    // ⑦ 放入单例池
    addSingleton(beanName, exposedObject);
    // singletonObjects.put(beanName, exposedObject);

    return exposedObject;
}
```

---

## 11. 常见面试追问

**Q: 构造器注入 vs 字段注入？**

构造器注入：依赖以 `final` 字段存在，对象创建完就完整可用，单元测试可以直接 `new InterviewApp(mockX, mockY, mockZ)` 不需要启动 Spring。项目里 `InterviewApp` 核心依赖走构造器、6 个工具走 `@Autowired` 是平衡参数数量和 `final` 保证的折中方案。

**Q: @PostConstruct 和 InitializingBean 的执行顺序？**

先 @PostConstruct，后 InitializingBean.afterPropertiesSet()。@PostConstruct 是 JSR-250 标准注解，InitializingBean 是 Spring 专有接口。优先用 @PostConstruct（不侵入代码）。

**Q: 如果有两个 VectorStore 实现，Spring 怎么选？**

会报错 `NoUniqueBeanDefinitionException`，需要 `@Qualifier("pgVectorStore")` 或 `@Primary` 消除歧义。
