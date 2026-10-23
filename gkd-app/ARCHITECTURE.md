# gkd-app 模块架构

`gkd-app` 采用“功能纵向切片 + 明确的数据和平台边界”。依赖方向如下：

```text
App / MainActivity
        │
        ▼
feature ─────► domain
   │             │
   ├──────────► data
   │
   └──────────► platform

service / receiver ─► data、domain、platform
```

## 目录职责

- `app/`：应用进程与 Activity 生命周期入口。进程级唯一组件直接声明为 `object`，生产实例由各自业务包负责，不设置统一容器。
- `core/`：跨层共享且不依赖 Android UI 的基础状态和值类型。
- `feature/`：按用户功能组织 Page、Route、ViewModel 和功能内组件。当前包含 `log`、`snapshot`、`subscription`、`settings`。
- `domain/`：可独立验证的业务规则与值对象，例如规则组启用策略。不得依赖 Compose、Activity、Service 或 DAO。
- `data/`：持久化、网络、文件及跨数据源一致性边界。单表访问直接依赖 Room DAO；只有需要协调状态、并发、文件或多个数据源时才引入 Repository、Manager 或 Service。
- `platform/`：Android 平台能力的统一入口，例如 Service 启停控制；不得依赖页面 ViewModel。
- `ui/component`、`ui/share`、`ui/style`：跨功能复用的无业务写入 UI 基础设施。
- `service/`、`a11y/`、`notif/`、`priv/`：Android 已注册组件及其运行时实现。为保持系统组件类名兼容暂不改包名，但通过 `platform/` 与 UI 解耦。开机自愈由 `BootRestoreReceiver` 在 `BOOT_COMPLETED` 时写回无障碍条目，不依赖特权服务在线。

## 状态与写入边界

| 场景 | 读取 | 写入 |
| --- | --- | --- |
| 设置 | `AppStore` 暴露的只读 `StateFlow` | `AppStore.update/replace`，并将自动化开关同步到特权进程生命周期配置 |
| 订阅 | `SubscriptionRepository.snapshotFlow`、`SubscriptionState` 的派生状态、`Db.subsItemDao` 的冷 `Flow` | `SubscriptionRepository` 编排订阅用例，`SubscriptionPersistence` 保证文件与数据库补偿一致性，`SubscriptionFileStore` 负责原子文件写入，单表字段更新直接使用 DAO |
| 规则配置 | Room DAO 的冷 `Flow` | 单表写入使用 DAO；跨规则业务操作使用 `RuleGroupConfigService` |
| 日志 | 对应 Room DAO 的 Flow/PagingSource | 对应 Room DAO 的插入、删除、裁剪方法 |
| 快照 | `SnapshotRepository.snapshots()` | `SnapshotRepository` 的文件与数据库原子操作 |
| 备份 | `BackupManager` 读取各数据源 | `BackupManager` 编排导入、校验与恢复 |
| Service | Service 自身只维护运行状态 | 页面请求权限后调用 `ServiceController`；前台保活悬浮窗统一由 `KeepAliveOverlayCoordinator` 协调 |

Composable 不得直接访问 `Db`、文件或 Service 生命周期。ViewModel 可以直接使用 `Db` 提供的职责单一 DAO，并负责聚合页面一致性所需状态；不要为了形式上的依赖注入把全局唯一 DAO 塞入 ViewModel 构造器。跨数据源写入和具有业务规则的操作交给 Repository、Manager 或 Service。Service、Receiver 和无障碍运行时不得引用页面 ViewModel。

## 协程、线程与并发

- 页面操作由 ViewModel 的作用域管理，默认从 Main 发起。执行阻塞文件操作的函数在内部切换到 IO，大量解析和计算在内部切换到 Default；调用方不为已经负责线程切换的函数重复指定调度器。Room 挂起 DAO 使用数据库配置的协程上下文。
- 线程调度不代替业务一致性。简单字段更新直接调用 DAO；基于旧值的规则配置修改由 `RuleGroupConfigService` 接收目标和变更，通过 `SubscriptionConfigStore` 在同一写事务中读取最新值并更新。文本编辑以开始编辑时的排除配置检测冲突，同时保留其他字段的新值。
- `A11yState` 在私有锁内更新前台信息和规则选择，并通过一个 `ActivityRule` 快照发布。涉及特权进程或 PackageManager 的阻塞查询时，通过 `A11yState.withTopActivityLock` 将查询、判断和更新放在同一临界区，不能只锁最终赋值或换成调用方自身的锁。`topActivityFlow` 只是该快照的展示投影；`currentTopActivity` 无锁读取已发布快照，需要等待更新临界区完成时使用 `A11yState.currentRule`。规则执行过程中的计数、延迟任务等运行状态仍由无障碍引擎管理。
- 设置的 `update/replace` 返回表示内存更新和持久化请求已被接受；`awaitPersistence` 才表示调用时已接受的请求完成落盘。转换函数必须无副作用，备份恢复期间可能对恢复状态和回滚状态各计算一次。
- 备份读取和解析阶段可以取消；获得订阅写锁后，恢复提交和必要补偿完成后才释放锁。数据库导入由一个写事务回滚，不用历史整库快照覆盖其他写入；订阅文件的保存、补偿与普通订阅修改互斥。设置回滚保留恢复期间收到的新命令。
- 普通操作跟随调用方生命周期。应用级作用域只承接明确需要跨页面存活的工作，并提供完成或失败语义；`NonCancellable` 只覆盖已接受的提交和补偿区间。
- `A11yRuntime` 统一选择自动化或无障碍服务，并提供根节点、窗口、截图和动作入口。每个服务保留独立的 `A11yRuleEngine`，事件、缓存和延迟任务跟随服务生命周期；`A11yContext` 只接收根节点读取回调，不依赖引擎。一次查询固定使用选中的服务，根节点读取仍更新对应引擎的缓存。

## 新代码放置

1. 新页面优先放入对应 `feature/<name>`；只有两个以上功能使用的纯 UI 才进入 `ui/component` 或 `ui/share`。
2. 跨页面业务判断进入 `domain`，并优先写纯函数行为测试。
3. 单表查询或写入直接使用对应 DAO，不新增一对一转发层；网络、文件、缓存、并发控制和跨表一致性操作进入 `data/<name>` 下职责明确的 Repository、Manager 或 Service。
4. Android 权限、Service、通知及系统 API 适配进入 `platform` 或保留在已注册组件包中，通过窄接口向上提供能力。
5. 进程级唯一且依赖固定的组件直接声明为 `object`；需要独立构造的可测实现保留在自己的业务包中。DAO 直接由 `Db` 提供，不通过容器重复暴露。

## 迁移中的兼容边界

- `service` 包名承载 Manifest、无障碍服务和快捷设置组件身份，重命名会使系统授权或磁贴失效，因此只迁移调用边界，不修改组件类名。
- `RawSubscription` 和数据库配置实体仍是历史共享模型；规则汇总已经下沉到 `domain/rule/RuleSummaryBuilder`，新逻辑不要再回填到应用级状态容器。
