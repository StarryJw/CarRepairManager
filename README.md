# 修车记录

面向店主自用的 Android 本地维修档案应用。需求以内部产品与技术设计文档为准，当前实现 Milestone 6：车牌 OCR，沿用已有本地档案、图片及完整 ZIP / WebDAV 备份流程。

## 当前功能

- Kotlin、Jetpack Compose、Material 3 单 Activity 工程。
- Navigation Compose：首页、车辆、设置三个顶层入口；重复点击不累积页面，切换保留导航状态。
- 首页按钮直接返回已有首页，不恢复其他标签栈；页面切换及返回不使用淡入淡出，避免新旧文字重叠。
- 页面切换首帧只摆放当前目的地；退场页保留状态但不绘制，避免 NavHost 短暂保留两页时透出旧文字。底栏选中底纹同步切换，避免上一标签高亮残留。
- 启动规则：完全退出后重新打开默认首页；锁屏、后台返回或系统重建已有任务时保留当前页面。导航状态使用 ViewModel + SavedStateHandle 和 NavController 保存，不跨任务读取永久标签偏好；返回键仍先回首页。
- 中文应用名、蓝色主色、浅灰背景、跟随系统的深色模式和空状态。
- 桌面图标采用蓝色背景、白色车头轮廓与小扳手；Android 8+ 使用矢量自适应图标，支持圆形与系统单色主题，旧版使用五种密度的同款图标。
- Room + KSP：Customer、Vehicle、ServiceOrder、ServiceItem、Photo 五张表，标准化车牌唯一索引和完整外键关系。
- Repository 支持新增车辆（可原子新增客户或关联已有客户）、车辆列表订阅、按 ID / 车牌查询、新增服务单与项目、历史服务订阅和服务详情查询。
- 首页最近到店、车辆列表 / 详情、新增 / 编辑车辆、新增 / 编辑服务、服务详情与设置七个核心页面。
- 车牌 / 姓名 / 手机号模糊搜索，匹配优先级为车牌 > 姓名 > 手机号；与真实品牌、归档条件组合，支持最近到店 / 新建优先排序和按批查看更多。
- 搜索输入立即显示，非空条件稳定 250ms 后查询；新条件下隐藏旧结果，清空恢复当前列表或首页最近到店。搜索卡片展示手机号便于核对，键盘搜索收起键盘；输入与筛选支持 Activity 重建恢复。
- 车辆归档 / 恢复；已归档车辆可从详情更多操作中输入车牌确认永久删除，清理关联服务与照片，保留客户和其他车辆。服务删除仍需二次确认。
- 草稿使用 SavedStateHandle 的 Bundle 保存；已保存数据进入 Room。保存期间禁止重复提交和退出，修改后返回需确认。
- 表单可随键盘滚动；日期弹窗根据实际窗口高度和字体大小适配，横屏或大字体时使用输入模式。
- DataStore 保存车辆排序与图片压缩质量；默认最近到店优先、标准 70%，新照片使用当前质量档位。
- 服务表单支持维修前、维修后、票据、其他四类系统拍照；后台压缩、EXIF 方向矫正、相对路径保存与 Coil 缩略图。图片查看支持左右滑动、双指缩放、双击放大、单张删除和返回。
- 新照片先保存在表单草稿中；编辑移除旧照片时先保留原文件，保存后才删除。App 保存相机请求与已交付的照片草稿，任务恢复时不重复打开相机；系统相机确认前的预览由相机应用维护。
- 本地备份使用系统文档选择器，无广泛存储权限；云备份增加 INTERNET / ACCESS_NETWORK_STATE，无新增相机权限。关闭 Android 系统自动备份；应用内自动备份默认关闭，只有用户主动开启才上传。卸载会清除应用私有数据。
- 首页和车辆页“拍车牌搜索”、新增车辆表单“拍照填入车牌号”：系统拍照 → 百度车牌 OCR → 多候选 / 原始置信度 → 人工确认或修改 → 回填原搜索框或原表单的车牌字段；查车和保存继续由原页面处理。OCR 配置在代码构建时读取，App 内无需配置。

## 开发环境

保留现有 Android Studio 工程配置：

| 配置 | 当前值 |
| --- | --- |
| Application ID | `com.example.carrepairmanager` |
| Android Gradle Plugin | 9.4.1（内置 Kotlin） |
| Gradle Wrapper | 9.6.0 |
| Gradle JVM | JDK 25，见 `gradle/gradle-daemon-jvm.properties` |
| Compose Compiler 插件 | 2.2.10 |
| Compose BOM | 2026.02.01 |
| Navigation Compose | 2.9.7 |
| Room / KSP | 2.8.5 / 2.3.10 |
| DataStore Preferences | 1.2.1 |
| Coil Compose / AndroidX ExifInterface | 3.4.0 / 1.4.2 |
| WorkManager / kotlinx.serialization / OkHttp | 2.12.0 / 1.9.0 / 4.12.0 |
| 本机数据库测试 | Robolectric 4.16.1（仅测试依赖，使用 API 35） |
| minSdk / compileSdk / targetSdk | 24 / 37 / 37 |

1. 用 Android Studio 打开项目根目录并执行 Gradle Sync。
2. 安装工程要求的 Android SDK Platform 37 及构建所需 SDK 工具。
3. 本机 SDK 路径在 `local.properties` 配置，不加入 Git；在 Android Studio 的 Gradle 设置中选择自带的 JBR 25。
4. 命令行运行前将 `JAVA_HOME` 指向本机 JDK 25 根目录；以下命令假定已配置该环境变量。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。依赖版本集中在 `gradle/libs.versions.toml`；无需安装额外架构框架。

## Milestone 1 数据层

`CarRepairApplication` 按需提供应用范围的 `database`、`repository` 和 `photoFileStore`，ViewModel 从此复用；页面不访问 DAO。数据库在应用私有目录中，名称 `car_repair.db`。当前为 version 2，`MIGRATION_1_2` 保留数据并允许金额为空；schema 1 / 2 均保存在 `app/schemas/com.example.carrepairmanager.data.local.CarRepairDatabase/`，需要随源码提交。未配置 destructive migration。

| Repository 接口 | 行为 |
| --- | --- |
| `createVehicle(vehicle, newCustomer)` | 新增车辆并返回 ID；只有车牌必填，可选新客户与车辆同事务保存 |
| `observeVehicles(includeArchived = false)` | Flow 订阅车辆与客户，默认排除归档车辆 |
| `getVehicle(id)` / `findVehicleByPlate(plateNumber)` | 查询单车，包含归档记录；车牌查询共用标准化规则 |
| `createServiceOrder(vehicleId, serviceDate, items, remark, photos)` | 单事务保存服务、项目与照片元数据，项目金额合计生成总金额；照片列表默认空 |
| `observeServiceHistory(vehicleId)` | Flow 订阅该车服务，服务日期倒序、同日 ID 倒序 |
| `getServiceOrder(id)` / `observeServiceOrder(id)` | 单次服务及排序后的项目、图片元数据；详情持续观察编辑结果 |
| `updateVehicle(vehicle, customer)` | 保留车辆主键、创建时间与归档状态；联系人更新到原客户 |
| `updateServiceOrder(orderId, serviceDate, items, remark, photos)` | 事务内替换项目并重算合计；photos 为 null 时保留原照片，传完整列表时保存图片增删 |
| `setVehicleArchived(id, archived)` | 归档或恢复车辆，历史与唯一车牌保留 |
| `observeVehicleDeletionSummary(id)` | 完整服务 / 照片计数，持续观察且不受详情分页限制 |
| `deleteVehicle(id, confirmedPlate)` | 校验已归档及标准化车牌，事务删除全部关联服务 / 项目 / 照片并清理文件，保留客户 |
| `deleteServiceOrder(id)` | 删除服务及项目、照片元数据和关联文件，保留车辆 |
| `deletePhoto(id)` | 单张删除元数据及本地文件，不修改服务项目和金额 |
| `observeVehicleList(...)` / `observeServiceHistoryEntries(...)` | 按当前筛选及可见批次查询列表摘要，避免装载全部项目和照片 |
| `observeRecentServices(limit)` | 首页最近到店摘要，默认二十条 |

使用约定：

- `id = 0` 表示待新增实体；已有客户通过 `Vehicle.customerId` 关联，与 `newCustomer` 二选一。不按姓名或手机号自动合并客户。
- 车牌去首尾和内部空白、英文转大写；归档车牌仍占唯一索引，避免重复历史档案。重复创建 / 编辑抛出 `DuplicatePlateException`，其他输入错误抛出带中文说明的 `IllegalArgumentException`；ViewModel 保留草稿并显示失败反馈，协程取消继续传播。
- `serviceDate` 使用严格 `yyyy-MM-dd` 文本，表示店内服务的日历日期，不做时区转换；`createdAt` / `updatedAt` 使用 Unix 毫秒。分类枚举以名称存储，后续重命名需考虑数据兼容。
- 项目金额选填，空白保存为 `null`；任一项目待填时服务合计为 `null`，表单、服务详情、车辆历史和首页显示“金额待填”。明确填写 `0` 表示免费，补齐或再次清空后自动重新计算；非空输入仍严格校验非负、最多两位小数和 Long 溢出。项目名称必填，无项目服务沿用零合计。
- 新增 `ServiceItem` 的 `id` / `orderId` 保持 0，由 Repository 设置所属新单；无需且不能传入人工计算的服务总额。
- 一个客户可关联多车，编辑联系人会同步到其名下车辆；表单明确提示。清空联系人姓名 / 手机号不会删除客户或影响历史关系，不按手机号自动合并客户。
- 服务项目和照片元数据关联服务单；图片文件由 Repository 协调保存与清理，不能仅依赖数据库级联删除。Photo 只保存相对路径，无 BLOB。
- 使用挂起接口保存、Flow 观察列表；没有主线程查询权限。UI 使用生命周期内订阅，车辆和历史首批五十条，点击“加载更多”再扩大可见批次；首页仅显示最近二十条。后续可按数据规模优化分页与索引，不改动现有业务规则。

`RecordValidationTest` / `RecordPresentationTest` 验证车牌、金额文本与整数分往返、溢出和日期时区；`CarRepairRepositoryTest` 使用真实 Room/SQLite 验证唯一约束、客户多车关系、归档恢复、编辑合计与回滚、安全删除、搜索摘要及磁盘重开。`CoreUiTest` 在 Robolectric 中启动真实 Activity，通过 Compose 点击 / 输入 / 滚动完成录入和编辑，检查草稿恢复、退出确认、重复车牌、归档恢复、删除、DataStore、大字体、横屏及深色模式；使用 Parcel 重建草稿 Bundle 以验证进程恢复格式。它们均不连接手机或写入实际档案，首次测试需下载 Android 测试运行时；测试依赖不打包进应用。

页面截图输出到 `app/build/reports/milestone2-ui/`，包含测试夹具数据，使用 Native graphics 绘制本机窗口供视觉检查；不等同于真机截图。测试结果以实际运行的报告为准。

## 车辆永久删除与验收

2026-10-06：用户反馈车辆永久删除功能验收通过。

按产品设计第 32.1 节实现。日常仍优先归档；永久删除用于误建、测试或确定不再保留的档案。先归档车辆，再在车辆页勾选“显示已归档车辆”，进入详情 → 更多操作 → 永久删除车辆。确认页面显示车牌、完整服务数及照片数，说明独立滚动，输入及按钮固定可见；输入沿用去空白、英文大写规则。取消或返回回到详情，不修改档案，确认输入及详情历史滚动位置在任务内保留，确认输入随 Activity 重建恢复。

Repository 在与备份快照共用的锁及 Room 事务内重新校验当前归档状态与车牌。先暂移目标照片，再删除服务（级联项目 / 照片元数据），最后删除车辆，保留 Customer。任一暂移或数据库操作失败都会回滚并恢复原图；提交后清理失败沿用现有暂存重试机制，记录错误并在下次读取重试，不把已完成的删除误报为失败。删除期间禁止重复提交和退出，成功后返回来源页，列表自动刷新。数据库仍为 version 2，schema / Migration / ZIP 格式不变。

删除使完整快照摘要发生变化，下一次自动备份会反映删除；上传失败不撤销本地删除，也不更新已备份摘要。云端旧快照继续保留，主动恢复旧版本会恢复当时的车辆、服务和照片。

用户在手机上使用测试档案验收：

1. 先导出当前档案到应用外部。建立共享同一客户的两辆测试车，为两车添加服务和照片；未归档车辆没有永久删除入口。
2. 归档其中一车后打开删除确认，核对完整服务 / 照片数；先取消，再输入错误车牌，均不删除。正确车牌的大小写 / 空白变体可确认；旋转或切后台后确认输入保留，同时检查键盘弹出及收起时的输入和操作按钮。
3. 确认删除后返回来源页；目标车辆、全部服务 / 项目与照片消失，客户和另一辆车的服务 / 照片保持。重开 App 后仍一致，可重新使用已释放的车牌建档。
4. 导出或上传新备份，核对不再包含目标档案；上传失败后本地仍已删除，下次成功备份反映删除，旧云版本保留。只在已备份当前数据的前提下主动恢复旧测试备份，确认会恢复当时的档案。

本机测试使用真实 Room、JPEG、ZIP 和本机 TLS WebDAV 夹具，覆盖归档 / 车牌校验、共享客户保护、完整计数、失败回滚、磁盘中断恢复、备份互斥和旧版本恢复。Compose 测试覆盖取消、错误车牌、重建、失败重试、成功返回及窄屏大字体 / 横屏；结果以实际运行的报告为准，不代表手机验收。

## 金额选填与升级验收

数据库从 version 1 升到 2：取消项目金额与服务合计的 NOT NULL，复制原服务、项目和照片元数据后重建表及约束。客户 / 车辆、已有金额（包括 0）、主键、自增上界、照片路径与文件均保留；无需卸载或清数据。`AmountMigrationTest` 从真实 schema 1 建旧库，经生产数据库入口升级验证。

1. 覆盖安装后打开旧服务，核对金额、项目、照片及归档车辆。
2. 新建服务：一个项目留空、另一个填 `19.90`，保存成功；服务详情、车辆历史和首页应显示“金额待填”，重开后仍保持。
3. 编辑并给空项目补填 `0`，合计为 `19.90`；再次清空并保存，恢复“金额待填”。负数、文字或三位小数仍不能保存。
4. 新备份使用格式版本 2 / 数据库版本 2，保存真实 `null`；可恢复原版本 1 / 数据库版本 1 的备份。旧 App 无法读取新版备份。金额待填、明确的 0、项目和照片在导出 / 恢复后均应一致。

## Milestone 2 手机手动验收

在 Android Studio 运行或覆盖安装 Debug APK；保留应用数据，无需卸载。

1. 首页 → 新增车辆，只填车牌保存；再编辑车主、手机号、品牌、车型、颜色和备注，返回后应刷新。
2. 输入相同车牌的大小写 / 空格变体，保存应提示重复并保留表单；首页或车辆页按车牌、姓名、手机号查到车辆，品牌筛选生效。
3. 车辆详情 → 新增服务，添加多个分类项目，例如 `300.10 + 29.90`，合计 `330.00`；日期可输入或选择，服务及项目备注可保存。
4. 编辑服务金额、移除项目后保存，服务详情、车辆历史和首页最近到店应同步更新；负金额、多于两位小数及无效日期应明确提示。
5. 未保存车辆 / 服务表单切到后台或锁屏再恢复，输入保留；按返回出现放弃确认，“继续编辑”保留内容。
6. 无照片服务删除需要确认，取消不删除；确认后回车辆详情。归档车辆在默认列表隐藏，勾选“显示已归档车辆”仍能查看历史并恢复。
7. 设置修改排序与图片质量，完全退出重开后配置保留；新任务仍先进入首页。锁屏 / 后台恢复保留原页面，首页 → 车辆 → 设置 → 首页快速往返及返回键切换时无旧页面重影；车辆页搜索内容和设置页滚动位置在返回标签后保留。
8. 已保存车辆与服务在完全退出重开、手机重启后可读取；再检查键盘遮挡、横屏、大字体和深色模式。图片与主动备份验收步骤见后文。

## Milestone 3 图片流程

沿用 `Compose UI → ViewModel → Repository → DAO → Room`，新增 `PhotoFileStore` 负责文件 IO。系统相机使用 `ActivityResultContracts.TakePicture()` 与应用自己的 `PhotoFileProvider`；共享范围仅为 `photo_drafts/`，不暴露数据库或已保存图库，也不申请 CAMERA / 存储 / 网络权限。

- 进入新增 / 编辑服务，在维修前、维修后、票据、其他区域拍照。拍照取消清理空文件；不可读照片保留错误反馈，可继续处理或取消。
- 原图暂存 `files/photo_drafts/<会话 UUID>/`。压缩在 `Dispatchers.IO` 执行，先采样再限制最长边为 1920px，矫正八种 EXIF 方向；重新编码 JPEG 不复制原照片的定位信息。
- 新照片使用设置中的省空间 50% / 标准 70% / 高清 90%；保留已验收的默认 70%，不修改旧照片。文件大小取决于照片内容，不强制把票据压到固定 KB。
- 保存服务时复制已压缩草稿到 `files/images/order_<ID>/photo_<UUID>.jpg`，Room 只保存相对路径。保存失败保留草稿并回滚服务、金额、项目与照片；成功后清理本次草稿。放弃编辑清理新拍照片，保留原照片。
- 表单与详情显示分类缩略图；点击打开大图，支持滑动、双指缩放、双击放大、重置缩放和删除。编辑中的旧照片直到保存才删除；详情图库删除立即生效，需要确认。
- Coil 缩略图请求限制为 384px，大图最大 1920px。首页 / 车辆卡片选取最近服务中的非票据照片，没有照片时保留车辆图标；车辆历史仍仅展示照片数量。
- 单张或整单删除先将图片暂移至 `photo_trash/`，再提交 Room 删除，最后清理文件。事务失败自动恢复原文件；进程中断后首次读取按 Room 引用恢复暂移文件、清理未提交的新文件。清理失败保留待重试文件并记录错误，下次读取重试，避免把已提交保存误报为失败而重复创建服务。
- 相机请求、已发起标记及照片草稿存于 SavedStateHandle / Bundle。系统恢复原任务时可继续处理相机结果；强制停止或从最近任务移除不能保证恢复未保存表单。为避免误删可恢复草稿，不自动清理其他草稿会话；此类未恢复会话可能留下私有临时文件。
- 系统相机尚未确认的照片可能仍只保存在相机应用内，未写入输出 URI。App 的草稿恢复不包含外部相机的预览状态，不能保证这一阶段切后台 / 息屏后的照片保留。用户已确认此场景下系统相机预览消失并退回取景界面，并决定暂缓 CameraX 改造，继续使用系统相机；使用时先在相机内确认照片，再切后台 / 息屏。参见 [Android 相机 Intent](https://developer.android.com/media/camera/camera-intents) 和 [TakePicture 结果契约](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture)。

图片阶段未修改实体或索引；当前金额选填使用数据库 version 2 与保留数据的迁移，图片路径规则保持兼容。

`PhotoWorkflowTest` 使用真实 JPEG、EXIF、Room 与磁盘文件，覆盖方向、尺寸、质量档位、保存 / 编辑 / 删除、部分文件失败、SQLite 回滚、中断恢复、并发保存和数据库重开。`CoreUiTest` 模拟系统相机结果，实际完成 Compose 拍照入口、重建恢复、保存、图片解码、查看与删除。Windows 测试使用窄目录 FileProvider shadow，并用 Coil 的 BitmapFactory 解码器替代本机不支持的 ImageDecoder 文件接口；JPEG 编解码与文件处理仍真实执行，测试不等同于手机相机授权验证。

`PhotoFileProviderTest` 为 Android 设备上的真实 URI 读写和共享目录边界测试，只使用随机测试草稿，不清理真实档案。本次只编译该测试 APK，未执行手机测试。图片界面截图使用蓝色色块测试 JPEG，输出在 `app/build/reports/milestone2-ui/16-photo-service-form.png` 至 `18-photo-viewer.png`；不是真实维修照片或真机截图。

## Milestone 3 手机手动验收

覆盖安装新 Debug APK，保留现有应用数据。推荐依次验证：

1. 新增服务 → 四类各拍一张，包含横向、竖向照片与票据；拍完返回正确分类，方向正确。再取消一次拍照，应没有空缩略图且能继续保存。
2. 点击表单缩略图查看；保存后在服务详情打开任意一张，起始页正确，滑动 / 双指放大 / 双击 / 重置 / 返回均可用。
3. 在系统相机中确认照片，返回表单后锁屏、切后台、旋转；已交付照片和已有文字草稿应保留，不重复弹出相机。结果未送达且相机已写入输出文件时可“继续处理照片”，也可“取消本次拍照”。相机尚未确认时切后台 / 息屏存在丢失预览的已知限制，用户已决定暂缓修改，此项未修复。
4. 编辑旧服务，移除旧照片并新拍一张后放弃修改，原照片仍在；再次编辑并保存，图片增删、服务金额和历史照片数量同步更新。
5. 单张删除先取消，再确认，其他照片与金额保持；整单删除先取消，再确认，该服务及关联图片消失，车辆仍在。
6. 设置切换 50% / 70% / 90%，之后的新照片使用相应档位；已有照片保持。完全退出重开及手机重启后已保存照片仍可查看。
7. 检查大字体、横屏、深色模式，继续复测首页 / 车辆 / 设置快速切换和锁屏恢复规则。相机硬件、系统授权、真实清晰度与内存表现由手机验收确认。

## Milestone 4 搜索规则与验收

按设计文档第 33 节及 Milestone 4，复用 Repository / DAO 的查询与既有首页最近到店，在 ViewModel 与界面完善搜索交互。搜索阶段未改实体、索引、依赖或权限；当前数据库版本与金额选填升级规则见上文。

| 项目 | 规则 |
| --- | --- |
| 车牌 | 通过 normalizedPlateNumber 包含匹配；去首尾及内部空白，英文转大写，与保存时规则一致 |
| 车主 / 手机号 | 去关键词首尾空白后包含匹配；联系人未填写不阻止车牌查询 |
| 匹配顺序 | 车牌 > 姓名 > 手机号；同一车辆只出现一次，同级按设置中的车辆排序展示 |
| 品牌 / 归档 | 与关键词同时满足；品牌精确匹配已有品牌，默认排除归档车辆；当前选中品牌在档案编辑后仍显示，便于取消筛选 |
| 最近到店 | 首页取最近二十次服务记录，服务日期倒序，同日 ID 倒序；同车多次到店保留各次记录。车辆列表摘要取该车最新服务日期，无服务车辆在最近到店排序下排在有服务车辆之后 |
| 分批读取 | 先在数据库全量筛选再取可见批次，首批五十辆；用户点击加载更多再扩大批次，输入或筛选改变回到首批 |

首页与车辆页共用 VehicleListViewModel。查询条件改变会取消旧读取，非空搜索等待条件稳定 250ms；输入立即更新，结果只在对应条件下显示。加载下一批保留已有卡片并禁用重复加载。只订阅排序设置，修改图片质量不会重新触发车辆查询。此处使用 [Kotlin Flow 的 flatMapLatest](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/flat-map-latest.html) 取消前一次观察，文件和数据库操作仍在原数据层执行。

本机验证包括真实 Room 的三类匹配优先级、车牌空白 / 大小写、LIKE 特殊字符、跨首批查询、品牌与归档组合、最近到店排序及编辑 / 删除 / 归档更新。VehicleListViewModelTest 用可暂停的真实 Room 查询线程验证慢查询不会显示旧结果，并检查连续输入、清空、分页和 SavedStateHandle 重建。CoreUiTest 实际完成首页搜索、键盘搜索、清空、品牌切换、无结果、Activity 重建、编辑品牌后取消筛选、加载更多及返回车辆详情。截图使用虚构测试档案，位于 app/build/reports/milestone2-ui/19-home-phone-search.png 至 21-vehicle-plate-search.png。

覆盖安装 Debug APK，保留现有数据，由用户依次验收：

1. 首页分别输入车牌片段、带内部空白 / 小写的车牌、姓名片段和手机号片段；核对结果卡片上的车辆、车主及手机号。多个字段同时匹配时，顺序为车牌、姓名、手机号。
2. 连续快速输入不同关键词，结果对应最后输入；不存在的关键词显示“未找到车辆”。键盘搜索收起键盘，清除图标在失去焦点后仍可见，点击后恢复最近到店。
3. 车辆页组合关键词与品牌，点击“全部”取消品牌条件；切换归档选项检查范围。进入详情编辑品牌后返回，当前品牌条件仍清楚显示，可取消筛选。
4. 建立或编辑不同日期的服务，首页最近到店与车辆最近服务日期同步更新；同日保持稳定顺序。车辆排序设置影响同级搜索结果，首页仍按到店日期排序。
5. 档案超过五十辆时加载更多；即使目标档案在首批之外，搜索也应能找到。锁屏 / 后台返回及旋转后保留当前输入和品牌筛选，再检查窄屏、大字体、深色模式和标签切换。

本机测试不等同于手机验收；结果以实际运行的报告为准。系统相机确认前的预览仍由相机应用维护。

## Milestone 5 备份、统一配置与验收

所有备份默认参数集中在 [`app/src/main/assets/backup.properties`](app/src/main/assets/backup.properties)。这是随 APK 打包的 UTF-8 配置文件；修改后重新构建。无需在业务 Kotlin 文件中修改 WebDAV 地址。

| 配置 | 作用 |
| --- | --- |
| `webdav.url` / `webdav.directory` | 默认 HTTPS WebDAV 地址与应用专用目录；设置页可覆盖 |
| `auto.interval.hours` / `auto.unmetered` | 检查周期与默认网络条件按配置文件读取；网络条件可在设置页切换 |
| `network.*.timeout.seconds` | 连接、读取与写入超时 |
| `retry.backoff.seconds` / `retry.max.attempts` | 临时网络错误指数退避与尝试次数上限，默认 30 秒 / 5 次 |
| `cloud.max.archive.bytes` | 云端单文件限制，默认保守按 500,000,000 字节检查 |
| `cloud.list.page.size` | 云端版本列表首批与每次加载更多的数量，默认 5 |
| `local.max.archive.bytes` / `restore.max.*` | 本地 ZIP / 解压总量默认 50 GiB、单份 JSON 默认 128 MiB、文件数默认 100,000；超限报错，不丢弃照片 |
| `retention.policy` | 首版固定 `keep_all`，每次创建独立快照，全部保留，不自动删除云端文件 |

账号和应用密码必须在 App 设置页填写，不能放在配置文件、源码或 `local.properties`。运行时设置集中在私有 `datastore/backup_settings.preferences_pb`，密码由 Android Keystore 的 AES-GCM 密钥加密；密钥、凭据与应用设置不进入备份，换机恢复后重新配置。修改地址或账号时须重新输入应用密码；同一配置下密码留空可沿用已保存凭据。密码不会进入 SavedStateHandle / Bundle。

ZIP 格式为 `manifest.json + data.json + images/order_<ID>/*.jpg`。直接导出五张业务表和已保存照片，不复制正在使用的数据库，不包含未保存草稿。manifest 保存格式版本 2、数据库版本 2、UTC 时间、各表数量、数据 / 图片 SHA-256 与图片字节数；导入兼容旧版本 1，待填金额仅在新格式中允许。业务写入和照片复制共用 Repository 锁，上传在锁外进行；上传期间新增修改不会被标记为已经备份。

本地导出的建议文件名与云端版本名统一为 `CarShopBackup_yyyyMMdd_HHmmss_SSS_<UUID>.zip`，其中时间固定使用北京时间（Asia/Shanghai，UTC+8），与设备时区无关。manifest 的 UTC 时间格式保持兼容，已有备份仍可恢复。

恢复规则已经用户确认：**完整替换本机所有已保存档案，不合并**。导入先暂存并检查 ZIP 中央目录 / CRC、版本、数量、外键、标准化车牌、日期、整数分金额合计、图片摘要 / JPEG 和路径边界；通过后显示数量、时间与替换说明，必须再次确认。新照片先写入独立 UUID 文件名，再在 Room 事务内替换各表，避免覆盖旧图；事务失败按原引用保留旧图并清理未提交新图。进程中断后的文件清理由既有 `PhotoFileStore.reconcile` 处理。恢复后清理旧导航详情与表单并回首页，图片路径可能更名，但照片内容、分类、主键与业务关联保留。

备份生成、导入预览及确认恢复均检查 JPEG 头尾标记、有效尺寸和采样像素解码。CRC / SHA-256 只能核对包内字节，不能证明打包前照片完整；截断原图会阻止生成成功快照，损坏备份在替换本机档案前拒绝。逐张采样解码控制内存，不修改图片内容、数据库或 ZIP 版本；大小配置仍以实际可用存储空间为约束。

设置页使用顺序：保存 WebDAV 配置 → 测试连接 → 立即备份到云端。坚果云默认地址与应用密码生成方式见 [官方帮助](https://help.jianguoyun.com/?p=2064)。上传先以 `.uploading` 暂存，再 MOVE 成独立 ZIP，通过目标文件的 `PROPFIND Depth: 0` / `DAV:getcontentlength` 核对字节数；临时文件不显示为恢复候选。云端恢复先刷新列表、选择版本并下载验证，之后与本地恢复使用同一确认流程。只接受 HTTPS，保持系统证书校验，重定向地址须改为最终地址，不携带凭据跳转。

大小校验不依赖 HEAD 的 `Content-Length`：HTTP 允许 HEAD 省略该字段，不能将缺失直接当作文件大小不一致（[HTTP 规范](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.3.2)、[WebDAV 文件长度属性](https://www.rfc-editor.org/info/rfc4918/)）。只接受目标文件成功属性分组中的有效大小；缺失属性提示“已上传但无法确认完整性”，真实大小不一致时显示本地 / 云端字节数，两者都不更新最近成功时间与已备份摘要。

云端配置、连接、上传、列表 / 下载及自动备份的失败原因只显示在“上传与自动备份”卡片。本地导出 / 恢复错误显示在本地区域；错误来源随结果持久化，升级前未记录来源的历史错误归入云端区域。

刷新云端备份成功后，按文件名时间倒序只显示最新 5 个版本；点击列表底部“加载更多”，每次追加 5 个，全部展开后按钮隐藏。再次刷新回到首批，读取失败时保留上次列表。分批展示使用本次服务器返回的目录列表，加载更多不重新请求目录或下载 ZIP；每批数量在 `cloud.list.page.size` 统一配置。

自动开关默认关闭；开启后 WorkManager 使用唯一周期任务和网络约束。只在已保存数据 / 照片摘要变化时上传；删除、归档和金额编辑也能触发下一次快照。手动和自动任务共用互斥锁；临时网络错误退避重试，认证失败 / 空间不足等需用户处理的错误明确展示。关闭开关取消自动任务，不取消用户明确发起的手动上传。最近导出与最近云端成功分别展示，导出不代表已经上传。

验收必须使用测试档案，按以下顺序由用户在手机上操作：

1. **先导出当前档案到应用外部**，在系统文件应用核对 ZIP。新建测试服务，包含多个项目与四类照片；导出、改变本地测试数据、选择备份，先取消确认，原数据应保持。再次确认恢复，核对客户多车关系、归档状态、项目金额与照片；重启后核对持久化。
2. 选择损坏 ZIP、缺图或不支持版本，看到明确错误且原档案 / 照片保持。确认过程中旋转 / 切后台，预览仍需手动确认。实际磁盘不足、系统文档提供方权限由手机验收确认。
3. 输入坚果云应用密码，保存并测试连接；立即上传，核对云端新 ZIP；刷新列表，下载后先取消，再在已备份当前档案的前提下确认恢复。错误密码、断网、配额不足应失败且不更新最近成功时间。
4. 开启自动备份并选择网络条件；无变更不新增云版本，新增 / 编辑 / 删除后下次运行生成独立版本；上传时仍可保存业务，之后的新修改下次备份。关闭后不再自动上传，历史备份不会随本地删除而删除。
5. 检查大字体、横屏、深色模式和标签切换；实际 Android Keystore、Wi-Fi / 计费网络、锁屏、省电及重启后的 WorkManager 行为需要真机验证，不能以本机测试替代。

本机 `BackupWorkflowTest` 使用真实 Room / JPEG / ZIP；`WebDavBackupTest` 使用本机自签 HTTPS 服务和注入的测试证书信任 / JVM AES-GCM，生产仍使用系统信任及 Android Keystore；`BackupSchedulerTest` 检查真实队列约束；`BackupUiTest` 模拟系统文档返回并点击真实 Compose 界面。`BackupKeystoreTest` 为使用随机测试别名的设备测试，仅编译不会证明设备验证通过。结果以实际运行的报告为准。

限制：首版为全量快照，没有分包、增量或自动保留清理，照片增长可能超过单文件限制；此时可先本地导出，后续评估扩展。坚果云官方说明单次 WebDAV 请求最多返回 750 个目录项，历史很多时列表可能不完整，可用网盘客户端下载指定 ZIP 后本地恢复。失败上传可能留下 `.uploading` 文件，不自动删除，用户可在网盘中确认后清理。暂存使用私有缓存，系统清理后须重新选择恢复文件。WorkManager 执行受网络、省电和单次运行时长约束，不承诺固定时刻或无限时长；ZIP 包含业务隐私数据，当前包本身未加密。

## Milestone 6 车牌 OCR、配置与验收

按 [百度车牌识别指南](https://cloud.baidu.com/doc/OCR/s/ck3h7y191) 和 [鉴权指南](https://ai.baidu.com/ai-doc/REFERENCE/Ck3dwjhhu) 实现 REST 调用，复用 OkHttp、kotlinx.serialization、系统相机、FileProvider 与现有图片压缩，不引入百度 SDK、CameraX 或额外权限。数据流为 `PlateOcrScreen → PlateOcrViewModel → OcrRepository → PlateRecognizer`。OCR 页确认后经导航结果回填来源页面；搜索沿用原查询 / 筛选流程，新增沿用原表单 / 保存流程。OCR 不改变业务表，当前数据库及 ZIP 版本随金额选填升级为 2。

### 配置文件

公共默认参数在 [`app/src/main/assets/ocr.properties`](app/src/main/assets/ocr.properties)，修改后重新构建：

| 配置 | 作用 |
| --- | --- |
| `baidu.token.url` / `baidu.license.plate.url` | 百度 HTTPS Token / 车牌识别地址，不含账号、查询参数或片段 |
| `network.*.timeout.seconds` | 连接、读取、写入和整个请求超时，默认 15 / 30 / 30 / 60 秒 |
| `token.refresh.margin.seconds` | 按服务返回的 `expires_in` 提前刷新，默认 60 秒；Token 只在内存缓存 |
| `image.max.edge` / `image.jpeg.quality` | OCR 独立压缩设置，默认 1920px / 90%，复用现有 1440～1920px 和 50 / 70 / 90 质量规则，不改变服务照片设置 |
| `image.min.side` / `request.max.form.bytes` | 最短边默认 15px；最长边另受百度 4096px 上限约束；Base64 与 URL 编码后的表单默认按 8,000,000 字节限制 |
| `recognition.multi.detect` | 默认 true，展示多车牌供人工选择，不自动选择车辆档案 |

凭据模板为根目录 [`ocr-credentials.example.properties`](ocr-credentials.example.properties)。复制为已被 Git 忽略的 `ocr-credentials.local.properties`，填入百度智能云 **OCR 应用** 的两项凭据；已有本机文件可直接修改：

```properties
baidu.api.key=你的OCR应用APIKey
baidu.secret.key=你的OCR应用SecretKey
```

不是 IAM 的 Access Key / Secret Access Key，也不是新版统一 API Key。此调用使用 OCR 应用的 API Key + Secret Key 兑换 access_token，无需 App ID 或手动配置 Token。先在百度控制台确认应用拥有车牌识别权限与可用额度。

[`app/build.gradle.kts`](app/build.gradle.kts) 在构建时读取本机文件，生成 `BuildConfig.BAIDU_OCR_API_KEY` / `BAIDU_OCR_SECRET_KEY`；`OcrRepository` 从代码读取。填好或更换凭据后重新构建并覆盖安装 APK，手机无需传输配置文件，App 没有凭据输入或导入入口。缺少任一项时禁用云识别，原搜索和新增表单仍可手动输入。

```powershell
.\gradlew.bat :app:assembleDebug
```

凭据会编译进 APK，可以被提取，因此仅用于店主自用安装，不公开分享含凭据的 APK。本机文件不提交 Git；凭据和 Token 不进入日志、SavedStateHandle 或业务备份。OCR 不使用运行时 DataStore / Keystore 配置；WebDAV 的加密配置流程继续沿用原实现。

### 使用与失败处理

1. 搜索车辆时，从首页或车辆页点击“拍车牌搜索”；新增车辆时，在原表单点击“拍照填入车牌号”。进入识别页点击“拍照并识别车牌”，页面先说明本次照片将上传至百度。
2. 在系统相机确认照片后开始识别；在 IO 线程缩放、矫正 EXIF 并重编码 JPEG，移除原图元数据。图片只存在当前 `photo_drafts/<UUID>/` 会话，不新增 Photo 记录，不作为维修照片或业务备份。
3. 展示识别候选与原始逐字符概率的平均置信度；概率不完整或无效时显示未提供。多车牌可切换；车牌输入框允许人工修改，原始置信度不代表修改后的正确率。
4. 人工确认后按去空白、英文大写规则返回车牌文本。搜索入口回填原搜索框，保留品牌 / 归档等筛选，用户自行选择搜索结果；新增入口只更新原表单的车牌号，保留姓名、手机号和其他字段，点击“保存车辆”才写入 Room，重复车牌仍由原保存逻辑校验。OCR 页不查询车辆档案或直接打开详情。
5. 网络、超时、鉴权、权限、额度、无车牌和坏响应均显示中文错误；失败照片保留供用户显式重试或取消。仅服务返回 110 / 111 时自动刷新 Token 并重试一次，其他失败不自动重发。TLS 使用系统证书信任，不跟随重定向携带凭据。

成功识别、取消拍照、返回和确认回填时清理当前临时会话；返回时同时取消网络请求，未确认的文本不回填。草稿、候选和相机发起标记使用 Bundle 恢复，系统重建不重复打开已发起相机，也不自动重发中断的云识别；有待处理照片时需用户点击“继续识别照片”或取消。回填结果消费后置空，连续拍照可重复回填，重建不会用旧结果覆盖后续输入。相机尚未确认照片时切后台 / 息屏仍存在 Milestone 3 的已知限制；强制停止或移除任务后不可恢复的会话可能留下私有临时文件，不自动清理其他草稿以免误删可恢复的照片。

### 手机手动验收

覆盖安装 Debug APK，保留现有档案；本机测试不使用真实账号、不操作手机。按以下顺序验收：

1. 在本机配置正确的 OCR 应用凭据并重建 APK，覆盖安装后直接使用；错误凭据、无权限或额度不足时得到相应提示。空凭据构建禁用拍照识别，原页面手动输入仍可用。
2. 搜索入口拍已有车牌，检查候选 / 置信度并修改；确认回到原搜索框，填入修改后的车牌，保留原筛选，再手动点击结果进入档案。归档结果受“显示已归档车辆”筛选控制；连续拍照能够多次替换搜索词，返回取消不改变原搜索词。
3. 在新增表单先填写姓名、手机号和备注，再拍车牌；人工确认后只更新车牌字段，其他字段在旋转或页面重建后仍保留，数据库在点击保存前不增加。另测拍照取消、重复车牌、多车牌、新能源车牌和缺少置信度结果。
4. 相机取消不上传；断网 / 超时 / 未识别到车牌保留错误和重试 / 取消入口；返回及时结束等待。识别中旋转 / 锁屏恢复不重复调用，系统回收后的中断任务需手动继续。系统相机、真实图片清晰度与百度识别准确率需实际手机确认。
5. 复测首页 / 车辆 / 设置切换、窄屏、大字体、横屏、深色模式、已有车辆 / 服务保存及备份。确认 OCR 不增加服务照片或改变备份内容。

`OcrWorkflowTest` 使用本机 HTTPS 服务、虚构代码凭据和真实 JPEG 验证协议、Token、TLS、错误及临时图片；`PlateOcrViewModelTest` 使用真实 Room、JPEG 和 Parcel 验证人工确认与恢复；`PlateOcrUiTest` 点击真实 Compose 页面验证搜索回填、保留筛选、连续回填 / 取消、新增草稿恢复与明确保存，以及窄屏大字体候选。真实百度调用及系统相机仍需上述手机验收；构建与测试结果以实际运行的报告为准。

## 真机运行与验证

1. 手机启用开发者选项和 USB 调试，USB 连接电脑并确认调试授权。
2. Android Studio 选择 `app` 和已连接设备，点击 Run。设备如限制安装，按系统提示允许 USB 安装。
3. 或将 SDK 的 `platform-tools` 加入 PATH 后使用下列命令。存在多个设备时设置 `ANDROID_SERIAL` 为目标序列号。

```powershell
adb devices -l
.\gradlew.bat :app:installDebug
adb shell am start -n com.example.carrepairmanager/.MainActivity
.\gradlew.bat :app:connectedDebugAndroidTest
```

`NavigationSmokeTest` 覆盖标签切换、重复点击、系统返回、Activity 重建恢复、车辆 → 设置 → 首页反复切换，以及动画时钟暂停时新旧文字不重叠；已改为检查固定入口，不要求手机库为空，不清理真实数据。手机测试由用户自行执行。

本机 `NavigationViewModelTest` 覆盖新任务默认首页、上个任务的设置页不影响新启动、已有任务状态恢复和未知路由回首页；它不等同于真机锁屏或进程回收测试。

手动验收（由用户操作手机）：全新启动首页 → 设置 → 从最近任务划掉应用 → 点击桌面图标，预期首页；进入车辆页 → 锁屏 → 解锁 → 从桌面或最近任务返回已有任务，预期仍在车辆页；设置页重复锁屏和后台恢复流程，预期仍在设置页。再检查横竖屏、后台恢复与深色模式。

同时保持导航回归验收：车辆 → 设置 → 点击首页，预期显示“最近到店”；连续往返三个标签，文字应直接切换，不出现新旧文字叠加。

## Git

GitHub 仅公开根目录 `README.md`；其他 Markdown 文档由 `.gitignore` 排除并在本机保留。

项目已初始化 Git。`.gitignore` 排除所有模块的构建产物、Kotlin/Gradle 缓存、`.idea/`、本机 properties / 环境凭据、签名密钥、数据库、运行时设置、备份 ZIP 和安装包。根目录的 `images/`、`photos/`、`photo_drafts/`、`databases/`、`datastore/`、`files/`、`backups/`、`exports/`、`private/` 用于本机数据，不加入 Git；公开文档图片放在 `docs/images/`。

源码、`gradle/wrapper/gradle-wrapper.jar`、Room schema JSON、公共 `backup.properties` / `ocr.properties`、空白 `ocr-credentials.example.properties` 和示意文档图片应随项目提交。真实 OCR 凭据仅放在 `ocr-credentials.local.properties`；WebDAV 账号 / 应用密码仅在 App 内配置。含本机 OCR 凭据的 APK / AAB 不公开上传到 GitHub Releases 或 Actions 构建产物。

提交前检查 `git status --short`、`git diff --check` 和 `git diff --cached --name-status`，并用 `git check-ignore -v <文件路径>` 验证私有文件被忽略。`.gitignore` 不会移除已跟踪文件或旧提交中的内容；本次已从索引移除 `.idea/`，本地文件保留。上传前仍需核对新增图片 / 文档是否含真实车牌、手机号、姓名、票据或维修照片。
