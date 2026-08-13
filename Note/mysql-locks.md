# MySQL 锁机制

## 总览：两层体系

MySQL 的锁分两个层面，各自独立但会互相影响：

```
Server 层（引擎无关）
├── 全局读锁
├── 显式表锁（LOCK TABLES）
└── MDL 元数据锁

InnoDB 引擎层
├── 意向锁（IS / IX）
├── AUTO-INC 锁
└── 行锁
    ├── Record Lock（记录锁）
    ├── Gap Lock（间隙锁）
    ├── Next-Key Lock（临键锁）
    ├── Insert Intention Lock（插入意向锁）
    └── 隐式锁
```

**核心比喻**：全局锁 = 商场关门，表锁 = 封某个店铺，行锁 = 只锁试衣间。粒度越大，并发能力越差。

---

## 一、全局锁

### 是什么

`FLUSH TABLES WITH READ LOCK`（FTWRL），让整个数据库实例变成只读，所有表的写操作全部阻塞。

### 什么时候用

做**全库逻辑备份**时，保证备份期间数据一致性。不加这把锁，备份过程中可能有数据被修改，导致跨表数据对不上（比如订单表和订单详情表同一笔订单的数据版本不一致）。

```sql
FLUSH TABLES WITH READ LOCK;
-- 做备份
mysqldump -u root -p --all-databases > backup.sql
UNLOCK TABLES;
```

### 代价

锁住期间整个库只读，业务基本停摆。

### 替代方案

InnoDB 下用 `mysqldump --single-transaction`，靠 MVCC 快照读代替全局锁。开启一个 REPEATABLE READ 事务，备份期间读写正常进行。

```
FTWRL：物理锁死所有写 → 业务停摆
--single-transaction：走 MVCC 快照 → 备份期间正常读写
```

**生产备份必须开 `--single-transaction`。**

---

## 二、表级锁

### 2.1 显式表锁

手动加的，`LOCK TABLES table_name READ/WRITE`。

**读锁**：自己可读不可写，其他会话可读不可写。

**写锁**：自己可读写，其他会话什么都干不了。

```sql
LOCK TABLES users READ;   -- 别人能读不能写
LOCK TABLES users WRITE;  -- 别人读写全拦
UNLOCK TABLES;
```

MyISAM 引擎默认就用这个，写操作时整表阻塞，并发能力很差。**日常业务不该用，会通过意向锁的互斥机制间接让所有行锁排队，摧毁 InnoDB 的行锁并发优势。**

### 2.2 MDL 元数据锁

**隐式加锁**，不需要手动操作：

- CRUD 语句自动加 **MDL 读锁**
- DDL 语句（ALTER TABLE 等）自动加 **MDL 写锁**

互斥规则：读读共享，读写互斥，且**写锁优先级更高**。

#### 经典线上事故

```
事务A：SELECT * FROM big_table;  -- 慢查询，没提交，一直持有 MDL 读锁
事务B：ALTER TABLE big_table ...; -- 申请 MDL 写锁，被 A 的读锁阻塞
事务C：SELECT * FROM big_table;   -- 申请 MDL 读锁，但 B 的写锁优先级更高，被 B 阻塞
事务D、E、F...                    -- 全部排在 C 后面
→ 整个表的读写全部卡死，连接池瞬间打满
```

**排查**：

```sql
-- MySQL 8.0
SELECT * FROM performance_schema.metadata_locks;

-- 通用
SHOW PROCESSLIST;  -- 找 "Waiting for table metadata lock"
```

### 2.3 意向锁（IS / IX）

#### 解决问题

InnoDB 支持行锁，同一张表里可能有多行分别被不同事务锁住。当有人想对整个表加锁（`LOCK TABLES ... WRITE`），怎么判断表里有没有行锁？

没有意向锁：逐行扫描全表 → 极其低效。
有意向锁：看一眼表上的标记就够了 → O(1)。

**比喻**：停车场（表）里每个车位（行）可能被占了。意向锁 = 入口电子屏显示"本场有车位被占"。管理员封场时看一眼屏幕就行，不用跑遍所有车位。

#### 两种意向锁

| 类型 | 含义 | 触发 SQL |
|------|------|---------|
| IS（意向共享锁） | "我打算给某些行加 S 锁" | `SELECT ... FOR SHARE` |
| IX（意向排他锁） | "我打算给某些行加 X 锁" | `SELECT ... FOR UPDATE` / INSERT / UPDATE / DELETE |

**全是 InnoDB 自动加的**，你永远不需要、也没办法手动加。

#### 加锁流程

当执行 `SELECT ... FOR UPDATE` 时：

```
步骤1：对表加 IX 锁
步骤2：对目标行加 X 锁
步骤3：事务提交时，表级 IX 和行级 X 一起释放
```

#### 互斥规则

> 意向锁之间永远兼容。意向锁只和显式表写锁冲突。

|  | IS | IX | 表写锁 |
|--|:--:|:--:|:--:|
| **IS** | ✅ | ✅ | ❌ |
| **IX** | ✅ | ✅ | ❌ |
| **表写锁** | ❌ | ❌ | ❌ |

- 多个事务同时加 IS/IX → 兼容，各行锁各行
- 有人持 IX（正在更新某行）时，`LOCK TABLES ... WRITE` → 被阻塞
- 意向锁**不阻塞普通 CRUD**

#### IS 和 IX 的区别

它们自己永远不冲突。区别在于**预告之后对行做了什么**：

```
事务A：SELECT ... FOR SHARE → 表 IS + 行 S
事务B：SELECT ... FOR UPDATE → 表 IX + 想加行 X
       → IS 和 IX 兼容 ✅，但 S 和 X 互斥 ❌ → 被阻塞！
```

> IS 和 IX 自己从不打架，但包装的 S 和 X 会在行级打起来。IS/IX 只是表上的标签，真正的战场在行锁。

### 2.4 AUTO-INC 锁

INSERT 自增主键时自动加，保证自增 ID 不重复。

```sql
INSERT INTO t (name) VALUES ('a');  -- 自动加 AUTO-INC 锁
```

由 `innodb_autoinc_lock_mode` 控制行为：

| 模式 | 行为 | 适用 |
|------|------|------|
| 0（传统） | 语句执行完才释放，批量 INSERT 串行化 | 已淘汰 |
| 1（连续） | 确定行数的 INSERT 用轻量锁；不确定行数的用传统锁 | 主从架构（默认） |
| 2（交叉） | 全部用轻量锁，最高并发，但不保证主从自增 ID 一致 | 单机 |

---

## 三、行锁

### 核心原则：行锁加在索引上，不是加在数据行上

InnoDB 的行锁对象是**索引记录**，不是物理行号。

```sql
-- name 无索引
SELECT * FROM users WHERE name = 'a' FOR UPDATE;
-- 全表扫描 → 扫到的每一行都加锁 → 整表被锁，跟表锁没区别
```

**这就是为什么 FOR UPDATE 的 WHERE 条件必须走索引——没索引行锁直接变表锁。**

二级索引加锁时会锁两处：二级索引上的记录 + 聚簇索引上对应的主键记录。不锁主键的话，别人绕路 `WHERE id=5 FOR UPDATE` 就能跳过二级索引上的锁。

### 3.1 快照读 vs 当前读（锁的服务对象）

| | 快照读 | 当前读 |
|--|-------|-------|
| SQL | 普通 `SELECT` | `SELECT ... FOR UPDATE` / `FOR SHARE` / INSERT / UPDATE / DELETE |
| 机制 | MVCC 版本链 + ReadView | 加锁（S 或 X） |
| 读到的 | 事务开始时的快照 | 最新已提交版本 |
| 加锁 | 不加 | 加 |

```
SELECT * FROM t WHERE id=1;              → 快照读，MVCC，不加锁
SELECT * FROM t WHERE id=1 FOR UPDATE;   → 当前读，加 X 锁
SELECT * FROM t WHERE id=1 FOR SHARE;    → 当前读，加 S 锁
UPDATE t SET name='x' WHERE id=1;        → 当前读，加 X 锁
```

区分的目的：普通查走快照不加锁，修改操作走当前读加锁。读写互不干扰，并发最大化。

**MVCC 管"读"的一致性，锁管"写"的安全。** 事务的隔离性靠两者配合：不可重复读靠 MVCC 快照解决，幻读靠 Gap Lock 解决，写冲突靠 X 锁互斥解决。

### 3.2 Record Lock（记录锁）

锁一条索引记录。S 和 S 共享，S 和 X 互斥，X 和 X 互斥。

```sql
-- id 是主键，表里 id=1,5,10
SELECT * FROM users WHERE id = 5 FOR UPDATE;
-- 精确命中 → 只锁 id=5 这一条记录
```

|  | S | X |
|--|:--:|:--:|
| **S** | ✅ | ❌ |
| **X** | ❌ | ❌ |

S 只排斥 X，X 排斥一切。名字已经把规则说完了——共享锁共享，排他锁排他。

### 3.3 Gap Lock（间隙锁）

锁索引记录之间的**空隙**，禁止在此间隙 INSERT。**仅在 RR 隔离级别下生效**。

```sql
-- age 普通索引，表里 age=10, 20, 30
SELECT * FROM users WHERE age = 25 FOR UPDATE;
-- age=25 不存在 → 锁住间隙 (20, 30)
-- 别人 INSERT age=22,25,28 → 全部被阻塞
```

**目的**：防止幻读。MVCC 快照只能保证已存在数据版本一致，管不了新插入的数据。Gap Lock 封死插入入口，从源头解决。

**三个反直觉特点**：

1. **间隙锁之间不互斥** — 多个事务可以同时锁同一个间隙。它的存在意义是防 INSERT，不是防读
2. **只在 RR 级别生效** — READ COMMITTED 下没有 Gap Lock
3. **唯一索引等值查询命中时退化** — 不需要间隙锁，退化为 Record Lock

### 3.4 Next-Key Lock（临键锁）

**Next-Key Lock = Record Lock + Gap Lock**。InnoDB 在 RR 下的默认行锁行为。

锁住一个**左开右闭**区间 `(前一个值, 当前值]`：

```sql
-- age 普通索引，表里 age=10, 20, 30
SELECT * FROM users WHERE age = 20 FOR UPDATE;
-- → Next-Key Lock on (10, 20]
```

**为什么左开右闭？** 右闭锁住记录本身（防不可重复读），左开锁住到前一个值的间隙（防幻读）。一条锁同时解决两个问题。

```sql
-- 锁住后：
UPDATE users SET name='x' WHERE age=20;  -- ❌ Record 部分挡住
INSERT INTO users VALUES (15, 'x');       -- ❌ Gap 部分挡住（15 在 (10,20) 里）
INSERT INTO users VALUES (25, 'x');       -- ✅ 25 在 (20,30) 里，没被锁
```

### 3.5 三种行锁的使用规则（谁决定用哪种？）

不是你想选就选，InnoDB 根据**索引类型 + 查询方式 + 是否命中**自动决定：

| 索引类型 | 查询方式 | 命中？ | 加什么锁 | 示例 |
|---------|---------|:--:|---------|------|
| 唯一索引 | 等值 | ✅ | Record Lock | `WHERE id=5`（5 存在） |
| 唯一索引 | 等值 | ❌ | Gap Lock | `WHERE id=7`（7 不存在） |
| 唯一索引 | 范围 | — | Next-Key Lock，右边界退化为 Gap | `WHERE id > 5` |
| 普通索引 | 等值 | ✅ | Next-Key Lock | `WHERE age=20` |
| 普通索引 | 等值 | ❌ | Gap Lock | `WHERE age=25`（不存在） |
| 普通索引 | 范围 | — | Next-Key Lock | `WHERE age > 20` |
| 无索引 | 任何 | — | 全表 Next-Key Lock | `WHERE name='a'`（无索引） |

**典型案例——普通索引等值查询为什么是 Next-Key Lock？**

```sql
-- age 普通索引，表里 age=10, 20, 20, 20, 30
SELECT * FROM users WHERE age = 20 FOR UPDATE;
```

因为普通索引不唯一，可能有重复值。扫描过程：

```
找到第一个 age=20 → 加 Next-Key Lock (10, 20]
找到第二个 age=20 → 加 Next-Key Lock（实际退化为 Record Lock）
找到第三个 age=20 → 加 Next-Key Lock（实际退化为 Record Lock）
继续往后 → age=30 不是 20 → 加 Gap Lock (20, 30) 封住右边界
```

最终 `INSERT age=19` → ❌ 被 `(10,20]` 挡住；`INSERT age=25` → ❌ 被 `(20,30)` 挡住。

### 3.6 Insert Intention Lock（插入意向锁）

INSERT 操作在目标间隙上加的锁，表示"我想往这里插入，等 Gap Lock 释放"。

**关键规则**：多个插入意向锁之间不互斥（不同行可以同时插入同一间隙），但和 Gap Lock **互斥**。

```sql
-- 事务A 持有 (10, 20) 的 Gap Lock
-- 事务B：INSERT age=15 → 加 Insert Intention Lock → ❌ 被 Gap Lock 阻塞
-- 事务C：INSERT age=17 → 加 Insert Intention Lock → ❌ 也被 Gap Lock 阻塞
-- 事务A 提交，Gap Lock 释放 → B 和 C 都能插入（不同行不冲突）
```

### 3.7 隐式锁

INSERT 新插入但未提交的行，不会立即加物理锁，而是**延迟加锁**。靠记录的 `trx_id` 判断归属，等有并发操作时才升级为显式 Record Lock。纯优化机制，减少未提交 INSERT 时的锁开销。

---

## 四、死锁

### 典型场景

```sql
-- 事务A：先锁 id=1，再锁 id=2
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
UPDATE accounts SET balance = balance + 100 WHERE id = 2;

-- 事务B：先锁 id=2，再锁 id=1（顺序相反，同时执行）
UPDATE accounts SET balance = balance - 50 WHERE id = 2;
UPDATE accounts SET balance = balance + 50 WHERE id = 1;

-- → 死锁！InnoDB 检测到后自动回滚代价小的那个事务
```

**解决**：保证所有事务对资源的加锁顺序一致。死锁没有"彻底避免"的办法，只能通过重试机制兜底。

---

## 五、常见线上问题

| 现象 | 根因 | 排查 |
|------|------|------|
| 某表突然所有操作超时 | MDL 写锁等待链 | `SHOW PROCESSLIST` 找 `Waiting for table metadata lock`，kill 源头 |
| 偶发 `Deadlock found` | 不同事务不同顺序锁资源 | `SHOW ENGINE INNODB STATUS` → `LATEST DETECTED DEADLOCK` |
| `Lock wait timeout exceeded` | 行锁等待超时 | `performance_schema.data_locks` + `data_lock_waits` 找阻塞者 |
| 主从延迟增大 | `innodb_autoinc_lock_mode=0` | 改为 1 或 2 |

---

## 六、关键参数

| 参数 | 默认值 | 说明 | 生产建议 |
|------|--------|------|---------|
| `innodb_lock_wait_timeout` | 50s | 行锁等待超时 | 10-20s |
| `innodb_autoinc_lock_mode` | 1 | 自增锁模式 | 主从用 1，单机用 2 |
| `innodb_deadlock_detect` | ON | 死锁检测 | 高并发可关，配合 lock_wait_timeout 兜底 |
| `transaction_isolation` | REPEATABLE READ | 决定 Gap Lock 是否生效 | RC 无 Gap Lock 并发更好，但可能有幻读 |
