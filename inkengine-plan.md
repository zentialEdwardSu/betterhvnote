# 高性能墨水屏手写笔记应用  
## 软件需求规格与架构设计文档

**文档版本：** v0.1  
**项目阶段：** 基础架构 / MVP 设计  
**目标设备：** 支持主动式触控笔与压感输入的墨水屏设备  
**核心定位：** 只面向笔输入、低延迟、高稳定性、可扩展的原生手写笔记应用

---

# 1. 项目概述

## 1.1 项目目标

本项目旨在实现一款面向墨水屏设备的高性能手写笔记应用。

应用以“手写”为唯一核心交互方式，不以键盘输入、富文本编辑、复杂排版为主要目标。第一阶段重点解决：

1. 低延迟压感书写；
2. 长时间书写情况下的稳定性能；
3. 大量笔画情况下的局部高效渲染；
4. 完整的矢量笔画编辑能力；
5. 多页面笔记管理；
6. 可靠保存和异常恢复；
7. 清晰、可扩展的软件架构。

第一阶段不追求大量附加功能，而优先保证手写体验和基础架构稳定。

长期需要支持：

- 图片插入；
- 网络同步；
- 多设备笔记同步；
- 录音；
- 录音时间与页面/笔画关联；
- OCR；
- AI 内容理解；
- AI 手写整理；
- AI 搜索；

因此基础版本的数据结构不得依赖单纯 Bitmap，也不得将页面设计成固定的 `vector<Stroke>`。

---

# 2. 设计原则

整个系统遵循以下核心原则。

## 2.1 Stroke 是数据，Bitmap 是缓存

笔迹原始数据必须保存为：

\[
S=\{(x_i,y_i,p_i,t_i)\}_{i=1}^{N}
\]

其中：

- \(x_i,y_i\)：笔尖位置；
- \(p_i\)：压感；
- \(t_i\)：采样时间。

屏幕上的像素只是 Stroke 的渲染结果。

禁止将页面 Bitmap 作为笔记的唯一真实数据。

---

## 2.2 Scene 是笔记，Tile 是显示缓存

逻辑页面：

```text
Page
 └── Scene
      ├── StrokeObject
      ├── StrokeObject
      └── ...
```

显示：

```text
Scene
 ↓
Rasterize
 ↓
Tile Cache
 ↓
E-Ink Display
```

Tile 可以随时删除并重新生成。

Scene 不可以依赖 Tile。

---

## 2.3 编辑对象，不编辑最终图像

例如“像素橡皮”在用户界面上表现为擦掉部分线条，但内部操作是：

```text
Stroke A

───────────────

      erase

──────    ─────

Stroke A1
Stroke A2
```

即：

\[
Stroke_A\rightarrow
\{Stroke_{A1},Stroke_{A2}\}
\]

而不是修改 Bitmap。

---

## 2.4 所有持久对象具有 UUID

包括：

- Notebook；
- Page；
- Stroke；
- Image；
- 未来的 Audio；
- Annotation 等。

例如：

```text
Notebook UUID
Page UUID
Object UUID
```

页面编号只代表显示顺序，不作为身份标识。

---

## 2.5 时间信息从第一版开始保存

即使 V1 没有录音，也必须保存：

```text
InkPoint.timestamp
Stroke.createdAt
Stroke.updatedAt
```

这样未来可以实现：

```text
Audio Time
    ↓
Stroke Time
    ↓
Page
    ↓
Canvas Position
```

---

# 3. MVP 功能范围

V1 必须实现：

- 主动笔输入；
- 压感；
- 笔宽修改；
- 基础颜色；
- Stroke Eraser；
- Point Eraser；
- 套索选区；
- 选区移动；
- 选区缩放；
- 选区删除；
- Undo；
- Redo；
- 多页面；
- 页面添加；
- 页面删除；
- 页面切换；
- 页面排序；
- 自动保存；
- 崩溃恢复；
- 打开笔记；
- 保存笔记；
- PNG 导出；
- PDF 导出。

V1 明确不包含：

- 网络；
- 用户系统；
- 云同步；
- AI；
- OCR；
- 录音；
- 图片；
- 文本框；
- 复杂笔刷；
- 无限画布；
- 多用户协同。

---

# 4. 非功能需求

## 4.1 输入延迟

目标：

\[
T_{input\rightarrow display}<20ms
\]

软件内部目标应尽量控制在：

\[
T_{software}<8\sim12ms
\]

实际最终延迟还受到墨水屏控制器影响。

书写路径中禁止执行：

- 文件 IO；
- SQLite 查询；
- 页面序列化；
- 全页面重绘；
- 全 Scene 搜索；
- 缩略图生成；
- PDF 导出。

---

## 4.2 页面规模

设计目标至少支持：

```text
10,000～50,000 strokes/page
```

在此规模下：

- 新增笔画延迟基本不随总笔画数线性增长；
- 擦除只搜索局部区域；
- 选区仅查询相关区域；
- 普通书写不触发全页重新 rasterize。

---

## 4.3 内存

页面 Bitmap 不应该随着页面逻辑尺寸无限增长。

必须使用 Tile Cache。

例如：

```text
256 × 256
或
512 × 512
```

Tile 数量由缓存策略控制。

---

## 4.4 稳定性

以下情况不应导致笔记损坏：

- 强制关闭应用；
- 系统杀死进程；
- 设备断电；
- 写笔记过程中切换页面；
- 保存过程中异常退出。

采用：

```text
SQLite Transaction
+
WAL
+
周期 checkpoint
```

保证一致性。

---

# 5. 总体系统架构

```text
┌───────────────────────────────────────────────┐
│                  Application                  │
├───────────────────────────────────────────────┤
│                                               │
│ UI Layer                                      │
│ Toolbar / Page Navigator / Canvas             │
│                                               │
├───────────────────────────────────────────────┤
│               Canvas Controller               │
│                                               │
│ Input Controller ─── Tool Controller          │
│                         │                     │
│                         ▼                     │
│                     Commands                  │
│                                               │
├───────────────────────────────────────────────┤
│                 Document Core                 │
│                                               │
│ Notebook                                      │
│   └── Page                                    │
│        ├── Scene                              │
│        ├── Spatial Index                      │
│        └── Object Model                       │
│                                               │
├───────────────────────────────────────────────┤
│                 Render Engine                 │
│                                               │
│ Live Ink                                      │
│ Static Tile Cache                             │
│ Overlay                                       │
│ Dirty Region                                  │
│                                               │
├───────────────────────────────────────────────┤
│               Persistence Layer               │
│                                               │
│ SQLite                                        │
│ WAL                                           │
│ Autosave                                      │
│ Document Repository                           │
│                                               │
├───────────────────────────────────────────────┤
│                  Services                     │
│                                               │
│ Export / Thumbnail / Future Sync / Future AI  │
│                                               │
└───────────────────────────────────────────────┘
```

---

# 6. 推荐工程结构

```text
app/
│
├── core/
│   ├── document/
│   │   ├── Notebook
│   │   ├── Page
│   │   ├── PageObject
│   │   └── StrokeObject
│   │
│   ├── ink/
│   │   ├── InkPoint
│   │   ├── StrokeBuilder
│   │   ├── StrokeGeometry
│   │   ├── PressureCurve
│   │   └── StrokeSimplifier
│   │
│   ├── tools/
│   │   ├── Tool
│   │   ├── PenTool
│   │   ├── StrokeEraserTool
│   │   ├── PointEraserTool
│   │   └── SelectionTool
│   │
│   ├── command/
│   │   ├── Command
│   │   ├── CommandManager
│   │   └── commands/
│   │
│   ├── spatial/
│   │   └── SpatialIndex
│   │
│   └── geometry/
│
├── render/
│   ├── RenderEngine
│   ├── RenderBackend
│   ├── TileCache
│   ├── DirtyRegionManager
│   └── EInkBackend
│
├── storage/
│   ├── NotebookRepository
│   ├── SQLiteStore
│   ├── MigrationManager
│   └── RecoveryManager
│
├── export/
│   ├── PdfExporter
│   └── ImageExporter
│
├── platform/
│   ├── StylusAdapter
│   ├── DisplayAdapter
│   └── FileSystemAdapter
│
└── ui/
    ├── MainWindow
    ├── Toolbar
    ├── CanvasView
    └── PageNavigator
```

---

# 7. 文档对象模型

## 7.1 Notebook

```cpp
class Notebook {
public:
    UUID id;

    std::string title;

    std::vector<UUID> pageOrder;

    Time createdAt;
    Time updatedAt;
};
```

注意：

```text
pageOrder
```

与 Page 自身的 UUID 分离。

例如：

```text
pageOrder:

P7
P2
P9
P3
```

删除、移动页面不会改变 Page ID。

---

# 8. Page

```cpp
class Page {
public:
    UUID id;

    PageSize size;

    Scene scene;

    SpatialIndex spatialIndex;
};
```

Page 不负责具体绘制。

Page 表示逻辑内容。

---

# 9. 通用 PageObject

```cpp
enum class ObjectType {
    Stroke,

    // Future
    Image,
    Text
};
```

基础类：

```cpp
class PageObject {
public:
    UUID id;

    ObjectType type;

    Rect localBounds;

    Transform2D transform;

    int zIndex;

    Time createdAt;
    Time updatedAt;
};
```

其中：

```text
localBounds
```

表示对象自身坐标系中的边界。

```text
transform
```

表示对象到页面坐标的变换。

---

# 10. Transform2D

建议所有页面对象使用：

\[
T=
\begin{bmatrix}
a&c&t_x\\
b&d&t_y\\
0&0&1
\end{bmatrix}
\]

从而统一支持：

- 平移；
- 缩放；
- 将来的旋转。

即：

\[
P_{page}=TP_{local}
\]

这样选区移动时无需重新修改几千个 InkPoint。

只需：

```cpp
object.transform =
    deltaTransform * object.transform;
```

可以显著降低大选区操作成本。

---

# 11. StrokeObject

```cpp
class StrokeObject : public PageObject {
public:
    std::vector<InkPoint> points;

    PenStyle style;
};
```

---

# 12. InkPoint

```cpp
struct InkPoint {
    float x;
    float y;

    float pressure;

    uint64_t timestamp;
};
```

推荐预留：

```cpp
float tiltX;
float tiltY;
float azimuth;
```

但第一版可以不使用。

---

# 13. PenStyle

```cpp
struct PenStyle {
    float baseWidth;

    Color color;

    PressureCurve pressureCurve;

    PenType type;
};
```

第一版：

```cpp
enum PenType {
    NormalPen
};
```

未来：

```text
FountainPen
Marker
Pencil
Highlighter
```

---

# 14. 压感模型

定义归一化压感：

\[
p\in[0,1]
\]

输出笔宽：

\[
w=w_0f(p)
\]

第一版推荐：

\[
f(p)=a+(1-a)p^\gamma
\]

例如：

\[
a=0.25
\]

\[
\gamma=0.7
\]

因此：

\[
w=w_0(0.25+0.75p^{0.7})
\]

避免轻微压力时笔迹完全消失。

PressureCurve 必须单独封装。

禁止将计算公式写死在 RenderEngine 中。

---

# 15. 输入系统

输入事件统一转换为：

```cpp
struct StylusEvent {
    EventType type;

    Vec2 position;

    float pressure;

    uint64_t timestamp;

    StylusButton buttons;
};
```

EventType：

```text
Down
Move
Up
Cancel
```

---

# 16. Stylus-only 策略

Canvas 默认只接受：

```text
Stylus
Eraser-end Stylus
```

忽略：

```text
Finger
Mouse
Generic Touch
```

核心层判断：

```cpp
if (event.deviceType != DeviceType::Stylus)
    return;
```

如果未来希望触摸用于翻页，可以在 UI 输入层单独配置，而不能影响 Ink Engine。

---

# 17. Tool 系统

定义：

```cpp
class Tool {
public:
    virtual void onDown(
        const StylusEvent&) = 0;

    virtual void onMove(
        const StylusEvent&) = 0;

    virtual void onUp(
        const StylusEvent&) = 0;

    virtual void onCancel() = 0;
};
```

实现：

```text
Tool
├── PenTool
├── StrokeEraserTool
├── PointEraserTool
└── SelectionTool
```

Canvas 不允许存在大量：

```cpp
if (tool == PEN)
...
else if (tool == ERASER)
...
```

逻辑全部由 Tool 实现。

---

# 18. PenTool 状态机

状态：

```text
Idle
 ↓ Down
Drawing
 ↓ Up
Idle
```

流程：

```text
Stylus Down
    ↓
create StrokeBuilder
    ↓
sample points
    ↓
filter
    ↓
smoothing
    ↓
pressure mapping
    ↓
live rendering
    ↓
Stylus Up
    ↓
simplification
    ↓
final geometry
    ↓
AddStrokeCommand
```

---

# 19. StrokeBuilder

StrokeBuilder 只负责当前笔。

```cpp
class StrokeBuilder {
private:
    std::vector<InkPoint> rawPoints;
    std::vector<InkPoint> processedPoints;
};
```

职责：

- 接收采样；
- 去除重复点；
- 平滑；
- 插值；
- 输出局部 geometry；
- 完成 Stroke。

---

# 20. 输入采样过滤

如果连续两个点距离：

\[
d(P_i,P_{i-1})<\epsilon
\]

且：

\[
|p_i-p_{i-1}|<\epsilon_p
\]

可以丢弃新的样本。

避免设备高采样率产生大量冗余点。

---

# 21. 平滑

第一版可以采用轻量级方法，例如：

- One Euro Filter；
- Catmull-Rom；
- 局部三点滤波。

要求：

1. 不能引入明显输入延迟；
2. 不得等待整条笔结束后才显示；
3. 在线算法优先。

Live Stroke 与 Final Stroke 可以使用略不同的精度。

---

# 22. Stroke Simplification

抬笔之后执行一次路径简化。

例如使用：

```text
Ramer-Douglas-Peucker
```

或者基于距离与曲率的自定义简化。

要求保持：

- 路径形状；
- 压感变化；
- 时间信息。

因此不能简单删除时间关键点。

---

# 23. Variable Width Stroke Geometry

对于中心线：

\[
P_0,P_1,\ldots,P_n
\]

计算切线：

\[
T_i=P_{i+1}-P_{i-1}
\]

法线：

\[
N_i=
\frac{(-T_{iy},T_{ix})}
{\|T_i\|}
\]

笔宽：

\[
w_i=w_0f(p_i)
\]

左右边界：

\[
L_i=P_i+\frac{w_i}{2}N_i
\]

\[
R_i=P_i-\frac{w_i}{2}N_i
\]

形成：

```text
L0────L1────L2────L3
│ \   │ \   │ \   │
│  \  │  \  │  \  │
R0────R1────R2────R3
```

最终转换为 triangle strip 或 path geometry。

---

# 24. 实时笔迹与最终笔迹

必须区分：

```text
Live Stroke
```

和：

```text
Committed Stroke
```

Live Stroke：

- 当前正在书写；
- 临时 geometry；
- 不进入 Tile Cache；
- 不访问数据库。

Committed Stroke：

- 已完成；
- 插入 Scene；
- 更新 Spatial Index；
- 更新 Tile；
- 加入 Undo；
- 异步保存。

---

# 25. Render Engine

渲染至少分三层：

```text
┌───────────────────────┐
│ Overlay Layer         │
│ Selection / Lasso     │
├───────────────────────┤
│ Live Ink Layer        │
│ Current Stroke        │
├───────────────────────┤
│ Static Tile Layer     │
│ Completed Objects     │
└───────────────────────┘
```

最终：

\[
Frame=
Static+Live+Overlay
\]

---

# 26. Tile Cache

页面被划分为固定逻辑 Tile。

例如：

```text
512 × 512 logical px
```

```text
┌───────┬───────┬───────┐
│ T00   │ T10   │ T20   │
├───────┼───────┼───────┤
│ T01   │ T11   │ T21   │
├───────┼───────┼───────┤
│ T02   │ T12   │ T22   │
└───────┴───────┴───────┘
```

每个 Tile：

```cpp
struct Tile {
    TileID id;

    Bitmap bitmap;

    bool dirty;

    uint64_t version;
};
```

---

# 27. Dirty Tile

新增 Stroke：

```text
Stroke Bounds
    ↓
计算 overlap tiles
    ↓
mark dirty
```

删除 Stroke 同理。

只有 dirty Tile 需要重新 rasterize。

---

# 28. Dirty Rectangle

Live Ink 不需要重绘整个 Tile。

每个新 segment：

\[
dirtyRect=
bounds(segment)+margin
\]

margin 至少考虑：

- 最大笔宽；
- Anti-aliasing；
- 墨水屏更新边界。

然后：

```text
DisplayAdapter.update(dirtyRect)
```

---

# 29. 墨水屏显示适配

渲染层和墨水屏驱动必须隔离。

定义：

```cpp
class DisplayAdapter {
public:
    void updateRegion(Rect rect,
                      UpdateMode mode);

    void fullRefresh();
};
```

UpdateMode 由具体设备实现：

```text
Fast
Normal
Quality
```

核心 Ink Engine 不应知道具体屏幕刷新协议。

---

# 30. Ghosting 管理

快速局部刷新会产生残影。

因此 DisplayAdapter 应维护：

```text
partialUpdateCount
dirtyAreaAccumulated
elapsedTime
```

达到阈值后请求一次：

```text
Full Refresh
```

具体策略由设备适配层控制。

---

# 31. Spatial Index

以下功能均不能遍历全页面：

- 擦除；
- 选区；
- 点击命中；
- Tile 重绘；
- 局部刷新。

需要空间索引。

第一版推荐：

```text
Uniform Grid Spatial Index
```

相比 R-tree：

- 实现更简单；
- 笔迹分布通常均匀；
- 增删成本低；
- 更容易控制性能。

未来可以替换 R-tree。

---

# 32. Spatial Index 接口

```cpp
class SpatialIndex {
public:
    void insert(UUID id, Rect bounds);

    void remove(UUID id);

    void update(UUID id,
                Rect oldBounds,
                Rect newBounds);

    std::vector<UUID>
    query(Rect area);
};
```

调用方不应知道内部采用 Grid 还是 R-tree。

---

# 33. Stroke Eraser

用户操作：

```text
橡皮经过 Stroke
→ 整条删除
```

算法：

```text
Eraser Movement
      ↓
Swept Bounds
      ↓
SpatialIndex.query()
      ↓
candidate strokes
      ↓
precise intersection
      ↓
DeleteStrokeCommand
```

复杂度由：

\[
O(N)
\]

降低为近似：

\[
O(k)
\]

其中 \(k\) 为局部候选 Stroke 数量。

---

# 34. Point Eraser

用户视觉上：

```text
──────────────

      erase

──────    ─────
```

内部：

```text
Original Stroke A

        ↓

intersection

        ↓

Stroke A1
Stroke A2
```

执行：

```text
SplitStrokeCommand
```

---

# 35. Point Eraser 几何模型

橡皮可表示为：

\[
E(t)=Circle(C(t),r)
\]

求 Stroke centerline 与 swept eraser region 的交集。

删除：

\[
d(P,E)<r+\frac{w(P)}{2}
\]

的线段。

然后将剩余连续区间重新构成 Stroke。

---

# 36. 选区

第一版采用 Lasso Selection。

用户绘制闭合或近似闭合路径：

```text
       __________
      /          \
     /   hello    \
     \            /
      \__________/
```

流程：

```text
Lasso
 ↓
Lasso Bounds
 ↓
Spatial Index
 ↓
Candidate Objects
 ↓
Precise Geometry Test
 ↓
SelectionSet
```

---

# 37. SelectionSet

```cpp
struct SelectionSet {
    std::vector<UUID> objectIds;

    Rect bounds;
};
```

Selection 自身不复制对象。

只持有 ID。

---

# 38. 选区移动

移动量：

\[
\Delta=(d_x,d_y)
\]

构造：

\[
T_\Delta=
\begin{bmatrix}
1&0&d_x\\
0&1&d_y\\
0&0&1
\end{bmatrix}
\]

对象：

\[
T'_i=T_\Delta T_i
\]

操作对应：

```text
TransformObjectsCommand
```

---

# 39. 选区缩放

以选区中心 \(C\) 为中心：

\[
P'=C+s(P-C)
\]

对应矩阵：

\[
T=
T_C
S
T_{-C}
\]

缩放默认同时缩放：

- 路径；
- 笔宽。

这更符合“把手写内容整体放大/缩小”的用户认知。

---

# 40. 选区删除

实现：

```text
DeleteObjectsCommand
```

其中保存：

```text
vector<ObjectSnapshot>
```

保证 Undo 可以恢复。

---

# 41. Command 系统

所有会改变 Document 的用户操作必须经过 Command。

```cpp
class Command {
public:
    virtual void execute() = 0;

    virtual void undo() = 0;

    virtual CommandType type() const = 0;
};
```

---

# 42. Command 类型

第一版：

```text
AddStrokeCommand

DeleteObjectsCommand

SplitStrokeCommand

TransformObjectsCommand

ChangeObjectStyleCommand

AddPageCommand

DeletePageCommand

MovePageCommand
```

---

# 43. Undo / Redo

```text
             execute
                ↓
             Command
                ↓
          ┌───────────┐
          │ UndoStack │
          └───────────┘

Undo:

UndoStack
    ↓
command.undo()
    ↓
RedoStack
```

执行新操作以后：

```text
RedoStack.clear()
```

---

# 44. Command Merge

连续事件不应该产生数百条 Undo。

例如选区拖动：

```text
move 1 px
move 1 px
move 2 px
move 3 px
```

最终合并为：

```text
TransformObjectsCommand

Before Transform
After Transform
```

用户 Undo 一次恢复整个拖动操作。

---

# 45. Document 与 Render 解耦

执行：

```text
Command
```

后：

```text
Document Changed
      ↓
ChangeSet
```

例如：

```cpp
struct DocumentChangeSet {
    std::vector<UUID> added;

    std::vector<UUID> removed;

    std::vector<UUID> changed;

    Rect dirtyBounds;
};
```

RenderEngine 根据 ChangeSet 更新 Tile。

Storage 根据 ChangeSet 持久化。

两个模块互不直接调用。

---

# 46. 多页面管理

Notebook：

```text
Notebook
│
├── Page A
├── Page B
├── Page C
└── Page D
```

页面顺序：

```cpp
vector<PageID> pageOrder;
```

切换页面时：

```text
Current Page
     ↓
保存当前状态
     ↓
加载目标 Page Scene
     ↓
加载可见 Tile
     ↓
显示
```

---

# 47. Page Cache

不建议所有页面一直驻留内存。

推荐：

```text
Current Page
Previous Page
Next Page
```

优先缓存。

其他页面只保存：

```text
metadata
thumbnail
```

需要时再加载 Scene。

---

# 48. 缩略图

缩略图生成不得阻塞 UI。

```text
Page Change
     ↓
Background Job
     ↓
Render Thumbnail
     ↓
Cache
```

缩略图只是缓存，可以删除并重新生成。

---

# 49. 数据持久化

第一版推荐采用：

**SQLite + WAL。**

原因：

- 成熟稳定；
- 支持事务；
- 支持增量修改；
- 崩溃恢复成熟；
- 不需要每次保存整个 Notebook；
- 方便未来增加索引和同步状态。

---

# 50. 数据库概念结构

```text
notebooks

pages

objects

strokes

page_order

metadata

operation_journal
```

例如：

```sql
notebooks(
    id,
    title,
    created_at,
    updated_at
)
```

```sql
pages(
    id,
    notebook_id,
    width,
    height,
    created_at
)
```

```sql
objects(
    id,
    page_id,
    type,
    transform,
    bounds,
    z_index
)
```

```sql
strokes(
    object_id,
    style,
    points_blob
)
```

---

# 51. InkPoint 存储

大量 InkPoint 不推荐逐行写数据库。

例如：

```text
stroke_points
x
y
pressure
time
```

每个点一行会产生过大数据库开销。

推荐：

```text
Stroke → serialized points blob
```

即一个 Stroke 对应一个压缩 binary block。

---

# 52. Point Blob

可以进行 delta encoding。

第一个点：

\[
(x_0,y_0,t_0)
\]

后续：

\[
(\Delta x_i,\Delta y_i,\Delta t_i,p_i)
\]

即：

\[
\Delta x_i=x_i-x_{i-1}
\]

\[
\Delta y_i=y_i-y_{i-1}
\]

\[
\Delta t_i=t_i-t_{i-1}
\]

对于手写轨迹通常数值变化较小，便于压缩。

---

# 53. Schema Version

数据库必须记录：

```text
schema_version
```

例如：

```text
1
2
3
...
```

所有升级通过：

```text
MigrationManager
```

完成。

禁止直接修改已有数据库意义而不修改 version。

---

# 54. 保存策略

每个 Command 完成后：

```text
Command
 ↓
Document Memory
 ↓
Persistence Queue
 ↓
SQLite Transaction
```

持久化线程异步执行。

不能阻塞输入线程。

---

# 55. Crash Recovery

采用 SQLite WAL 后：

```text
Committed Transaction
```

天然具备崩溃恢复能力。

但 Command 与数据库必须保证：

```text
Atomic Transaction
```

例如 SplitStroke：

```text
DELETE Stroke A

INSERT Stroke A1

INSERT Stroke A2
```

必须在同一 transaction。

不能出现：

```text
A 被删除
但 A1/A2 未写入
```

---

# 56. Autosave

应用不需要传统意义上的“按保存才写文件”。

正常操作自动持久化。

UI 上的“保存”可以理解为：

```text
Flush pending operations
+
Checkpoint WAL
```

---

# 57. 文件组织

对于用户看到的 Notebook 文件，可以定义：

```text
*.inknote
```

逻辑内容：

```text
Notebook database
+
Future assets
```

第一阶段可以直接采用单 SQLite 文件。

未来加入图片以后可以采用 bundle：

```text
document.inknote/
│
├── notebook.db
│
├── assets/
│   ├── ...
│
└── preview/
```

分享时打包成单文件。

---

# 58. PNG 导出

流程：

```text
Page Scene
   ↓
Offscreen Render
   ↓
High-resolution Bitmap
   ↓
PNG Encoder
```

PNG 导出与屏幕 Tile Cache 无关。

不能简单截屏。

---

# 59. PDF 导出

因为 Stroke 保留矢量数据：

```text
Stroke
  ↓
Vector Geometry
  ↓
PDF Path
```

可以生成真正的矢量 PDF。

优点：

- 放大不失真；
- 文件较小；
- 打印质量高。

---

# 60. 坐标系统

必须明确区分：

```text
Device Coordinates
View Coordinates
Page Coordinates
Object Local Coordinates
```

流程：

```text
Stylus Device Position
       ↓
View Transform
       ↓
Page Position
       ↓
Object Local Position
```

所有笔迹永久存储使用：

```text
Page Coordinates
```

不得保存屏幕像素坐标。

---

# 61. DPI 独立

页面内部采用逻辑坐标。

例如：

```text
1 logical unit
```

不得与实际屏幕一个物理像素永久绑定。

这样不同 DPI 设备打开同一文档不会发生尺寸改变。

---

# 62. Render Backend

推荐定义抽象接口：

```cpp
class RenderBackend {
public:
    void beginFrame();

    void drawStroke(...);

    void drawSelection(...);

    void drawImage(...);

    void endFrame();
};
```

第一版可以采用：

- Skia CPU；
- Skia GPU；
- 平台原生 Render API。

具体实现不得渗透 Document Core。

---

# 63. 线程模型

推荐：

```text
┌──────────────┐
│ Input Thread │
└──────┬───────┘
       │
       ▼
 StrokeBuilder
       │
       ▼
┌──────────────┐
│Render Thread │
└──────┬───────┘
       │
       ▼
   E-Ink Display


┌───────────────┐
│Storage Worker │
└───────────────┘


┌───────────────┐
│Background Job │
│Thumbnail/PDF  │
└───────────────┘
```

---

# 64. 输入热路径

以下必须非常短：

```text
Stylus Event
 ↓
StrokeBuilder
 ↓
Live Geometry
 ↓
Dirty Rect
 ↓
Render
```

热路径中禁止：

```text
SQLite
filesystem
thumbnail
PDF
global scene traversal
```

---

# 65. Scene 线程安全

Document 修改应集中到一个逻辑线程。

推荐：

```text
UI / Document Thread
```

负责执行 Command。

RenderThread 获取：

```text
immutable render snapshot
```

或者版本化对象。

避免 RenderThread 与 DocumentThread 同时修改 `vector<Stroke>`。

---

# 66. UI 设计

整体 UI 延续目标示意：

```text
┌───┬─────────────────────────────┐
│ ● │                             │
│ ✎ │                             │
│ ● │                             │
│ ◉ │          Canvas             │
│ ◇ │                             │
│ □ │                             │
│   │                             │
└───┴─────────────────────────────┘
```

核心原则：

- Canvas 占据绝大部分区域；
- 工具栏固定在侧边；
- 不使用复杂菜单层级；
- 主要操作一笔可达；
- 当前 Tool 状态必须明确。

---

# 67. V1 Toolbar

建议：

```text
Back / Notebook

Pen

Stroke Eraser

Point Eraser

Selection

Pen Width

Color

Undo

Redo

Page
```

具体图标可以根据墨水屏视觉特性调整。

---

# 68. Pen Width

第一版提供有限离散级别：

```text
Thin
Medium
Thick
```

或者：

```text
0.5
1.0
1.5
2.0
3.0
```

相比连续 Slider，更适合快速操作。

内部仍然保存 float。

---

# 69. Color

墨水屏版本建议限制基础颜色。

例如：

```text
Black
Dark Gray
Light Gray
White
```

彩色墨水屏未来可以增加：

```text
Red
Blue
Green
Yellow
```

Document Model 使用标准 RGBA，不应该限制为屏幕实际颜色。

显示时再由 DisplayBackend 映射。

---

# 70. 性能监测指标

开发阶段必须持续记录：

```text
Input event latency

Live stroke render time

Tile rasterization time

Partial refresh time

Number of active tiles

Memory usage

Stroke count

Spatial query duration

Autosave duration

Page load duration
```

否则无法真正判断系统是否“高性能”。

---

# 71. 建议性能目标

基础软件内部目标：

| 项目 | 目标 |
|---|---:|
| Pen event processing | < 1 ms |
| Live segment geometry | < 1 ms |
| Live software rendering | < 4 ms |
| Spatial query | < 2 ms |
| Command execute | < 5 ms |
| 普通页面切换 | < 300 ms |
| Undo 普通操作 | < 50 ms |
| Autosave | 不阻塞 UI |
| 10k Stroke 页面书写 | 无明显性能下降 |

最终视觉刷新仍由具体墨水屏硬件限制。

---

# 72. 内存管理

Stroke points 数量可能很大。

例如：

```text
30,000 strokes
×
100 points
=
3,000,000 InkPoints
```

因此必须避免每个 InkPoint 携带大量对象开销。

推荐：

```cpp
struct InkPoint
```

连续存储：

```cpp
std::vector<InkPoint>
```

而不是：

```text
vector<shared_ptr<InkPoint>>
```

---

# 73. 对象生命周期

Document 拥有 PageObject。

外部模块主要引用：

```text
UUID
```

而非裸指针。

例如 Selection：

```text
vector<UUID>
```

这样删除对象后不会遗留大量悬空指针。

---

# 74. 日志

系统内部日志至少分类：

```text
INPUT
RENDER
DOCUMENT
DATABASE
EXPORT
RECOVERY
PERFORMANCE
```

Release 环境不记录完整笔画内容，以避免产生巨大日志并降低隐私风险。

---

# 75. 错误处理

磁盘写失败：

```text
Persistence Queue
 ↓
write failed
 ↓
retain unsaved operations
 ↓
UI warning
```

不得因为单次写入失败立即清空内存文档。

---

# 76. 数据库损坏处理

打开 Notebook：

```text
Open
 ↓
Integrity Check
 ↓
Normal
```

异常：

```text
Recovery
 ↓
WAL Recovery
 ↓
Last Valid State
```

如果仍失败：

```text
Read-only Recovery Mode
```

优先尽可能导出可恢复页面。

---

# 77. 测试架构

测试至少包含：

```text
Unit Tests

Geometry Tests

Document Tests

Command Tests

Persistence Tests

Render Tests

Performance Tests

Crash Tests
```

---

# 78. Pen 测试

验证：

- Down/Move/Up；
- 只有一个点；
- 极快移动；
- 极慢移动；
- 压感快速变化；
- Cancel；
- 笔突然离开；
- 高频采样；
- 重复采样点。

---

# 79. 擦除测试

Stroke Eraser：

```text
完全命中
边缘命中
不命中
多 Stroke 命中
Transform 后命中
```

Point Eraser：

```text
擦中间
擦开头
擦结尾
一次分成两段
一次分成多段
整条删除
```

---

# 80. Undo / Redo 测试

必须测试：

```text
Draw
Undo
Redo

Erase
Undo
Redo

Split
Undo
Redo

Move
Undo
Redo

Scale
Undo
Redo

Delete Page
Undo
Redo
```

以及：

```text
Undo
Undo
Undo
New Command
```

此时 Redo 必须清空。

---

# 81. Crash Test

自动化执行：

```text
Write stroke
kill process

Erase stroke
kill process

Move objects
kill process

Delete page
kill process
```

重启之后验证：

```text
数据库完整
页面可打开
无部分 Command 状态
```

---

# 82. Performance Test

生成：

```text
1,000 strokes
5,000 strokes
10,000 strokes
50,000 strokes
```

分别测试：

- 新增 Stroke；
- 擦除；
- 选区；
- Page Load；
- Zoom；
- Tile Render。

禁止算法在 50k Stroke 后退化成明显卡顿。

---

# 83. Future：Image

未来增加：

```text
PageObject
├── StrokeObject
└── ImageObject
```

例如：

```cpp
class ImageObject : public PageObject {
    AssetID assetId;
};
```

现有：

- Transform；
- Selection；
- Delete；
- Undo；
- Spatial Index；

全部可以复用。

---

# 84. Future：网络同步

因为所有对象有 UUID，所以未来同步可以采用 Operation 模式。

例如：

```text
Operation
│
├── op_id
├── device_id
├── object_id
├── type
├── timestamp
└── payload
```

操作：

```text
ADD_OBJECT
DELETE_OBJECT
TRANSFORM_OBJECT
UPDATE_STYLE
ADD_PAGE
MOVE_PAGE
```

因此不需要不断上传整个 Notebook。

---

# 85. Future：录音

未来：

```text
AudioSession
│
├── Audio File
├── startTimestamp
└── Notebook ID
```

因为 InkPoint 已包含 timestamp：

```text
Audio Seek 13:21
      ↓
global timestamp
      ↓
Stroke timestamp query
      ↓
Page UUID
      ↓
Stroke UUID
      ↓
Canvas Position
```

即可实现：

> 拖动音频 → 自动跳转到当时正在书写的位置。

---

# 86. Future：AI

AI 模块不得直接依赖 Render Bitmap。

应主要读取：

```text
Notebook
Page
Stroke
Image
Audio
OCR
```

例如：

```text
AI Service
     │
     ▼
Document Semantic API
     │
     ├── Strokes
     ├── Spatial layout
     ├── Timeline
     └── Images
```

未来可以实现：

- 手写识别；
- 页面摘要；
- 搜索；
- 自动标题；
- 数学公式识别；
- 内容问答；
- 会议总结；
- 录音与笔记联合理解。

---

# 87. Future API 边界

从第一版开始可预留：

```cpp
class DocumentObserver;

class AssetRepository;

class SyncProvider;

class AudioProvider;

class AIProvider;
```

但 V1 不实现具体功能。

禁止为了“未来可能需要”提前实现大量无用代码。

只需要保证核心模型不会阻碍这些能力。

---

# 88. 推荐开发阶段

## Phase 0：技术验证

实现：

```text
Stylus
Pressure
Live Ink
Dirty Rect
E-Ink Update
```

目标：

验证最低书写延迟。

此阶段不开发 Notebook 系统。

---

## Phase 1：Ink Engine

完成：

```text
Stroke
Pressure
Smoothing
Variable Width
Static Rendering
```

要求：

10000 Stroke 页面仍可正常书写。

---

## Phase 2：Document Core

完成：

```text
Notebook
Page
PageObject
UUID
Scene
Spatial Index
```

---

## Phase 3：编辑系统

完成：

```text
Stroke Eraser
Point Eraser
Lasso
Move
Scale
Delete
```

---

## Phase 4：Command System

完成：

```text
Undo
Redo
Command Merge
```

---

## Phase 5：Persistence

完成：

```text
SQLite
WAL
Autosave
Crash Recovery
Migration
```

---

## Phase 6：Page System

完成：

```text
Multi-page
Page Add
Page Delete
Page Move
Thumbnail
Page Cache
```

---

## Phase 7：Export

完成：

```text
PNG
PDF
```

至此形成第一个完整版本。

---

# 89. MVP 完成标准

V1 可以被认为完成，需要同时满足以下条件。

### 书写

- 压感正常；
- 连续快速书写不明显掉线；
- 长时间书写不存在持续恶化；
- UI 与保存操作不会阻塞 Ink。

### 编辑

- 两类橡皮均正常；
- Lasso 正常；
- 移动正常；
- 缩放正常；
- 删除正常。

### History

- 所有用户编辑均可 Undo；
- Redo 行为一致；
- 不需要页面 Bitmap 快照。

### Document

- 支持多页；
- 页面可排序；
- 可长期保存；
- 可重新打开。

### Recovery

- 强制杀进程后笔记仍可恢复；
- 不出现半次操作状态。

### Performance

至少：

```text
10,000 strokes/page
```

正常书写体验没有明显变化。

### Export

- PNG 正确；
- PDF 正确；
- PDF 中 Stroke 保持高质量矢量输出。

---

# 90. 首版关键技术决策

最终建议确定以下决策，并在开发过程中尽量不要随意改变。

### 数据

```text
Stroke = 原始数据
Bitmap = 缓存
```

### 对象

```text
PageObject + UUID
```

而不是：

```text
Page = vector<Stroke>
```

### Transform

```text
对象保存独立 Transform2D
```

避免频繁修改原始笔迹点。

### 编辑

```text
Command Pattern
```

统一所有改变 Document 的操作。

### 擦除

```text
Stroke Eraser = Delete

Point Eraser = Split
```

不修改最终 Bitmap。

### 查询

```text
Spatial Index
```

禁止频繁扫描整页 Stroke。

### 渲染

```text
Static Tile
+
Live Ink
+
Overlay
```

### 墨水屏

```text
Dirty Rectangle
+
Partial Update
```

### 存储

```text
SQLite
+
WAL
+
Transactions
```

### 时间

所有 Stroke 从第一版开始保存 timestamp。

---

# 91. 最终核心数据流

用户开始写字：

```text
Stylus
  ↓
StylusAdapter
  ↓
InputController
  ↓
PenTool
  ↓
StrokeBuilder
  ↓
Live Geometry
  ↓
RenderEngine
  ↓
Dirty Rectangle
  ↓
E-Ink Partial Update
```

抬笔：

```text
StrokeBuilder
  ↓
Finalize Stroke
  ↓
AddStrokeCommand
  ↓
Scene
  ├── SpatialIndex
  ├── Tile invalidation
  └── Persistence Queue
```

后台：

```text
Persistence Queue
      ↓
SQLite Transaction
      ↓
WAL
      ↓
Persistent Notebook
```

显示：

```text
Scene
 ↓
Tile Rasterizer
 ↓
Tile Cache
 ↓
Static Layer

Current Stroke
 ↓
Live Layer

Selection
 ↓
Overlay Layer

      ↓

Composition
      ↓
Dirty Region
      ↓
Display
```

---

# 92. 最终模块关系

```text
                    Application
                        │
        ┌───────────────┴───────────────┐
        │                               │
        ▼                               ▼
       UI                         NotebookManager
        │                               │
        ▼                               ▼
 CanvasController                   Notebook
        │                               │
 ┌──────┼────────┐                      ▼
 │      │        │                    Page
 ▼      ▼        ▼                      │
Input  Tool    Render                   ▼
       │                              Scene
       ▼                               │
    Command                ┌────────────┼────────────┐
       │                   │            │            │
       ▼                   ▼            ▼            ▼
CommandManager         Objects    SpatialIndex   TileCache
       │
       ▼
Document Changes
       │
 ┌─────┴──────────────┐
 ▼                    ▼
Renderer          Persistence
                       │
                       ▼
                    SQLite
```

---

# 93. 项目最重要的架构边界

项目开发过程中必须始终维持以下依赖方向：

```text
UI
 ↓
Controller
 ↓
Core
```

而不是：

```text
Core → UI
```

以及：

```text
Render → Core Model
Storage → Core Model
```

Render 与 Storage 是 Core 的消费者。

Core 不应该依赖：

```text
具体 UI Framework
具体 E-Ink SDK
SQLite API
PDF Library
网络 SDK
AI SDK
```

这些全部位于外围模块。

这样才能保证以后更换：

- Android；
- Linux；
- 墨水屏厂商；
- Render Backend；
- 数据库；
- 同步服务；

不会要求重写 Ink Engine 和 Document Core。

---

# 94. 架构结论

该应用的基础系统最终可以概括为：

```text
               Vector Document
                      │
            ┌─────────┴─────────┐
            │                   │
         Editing             Rendering
            │                   │
         Command            Tile Cache
            │                   │
      Spatial Index          Live Ink
            │                   │
            └─────────┬─────────┘
                      │
                  E-Ink Screen


               Persistence
                    │
             SQLite + WAL
                    │
             Crash Recovery
```

第一版本真正需要稳定下来的并不是 UI，而是五个基础设施：

**Ink Engine**

负责高质量、低延迟地将 Stylus samples 转换为 Stroke。

**Document Model**

负责 Notebook、Page、PageObject、Stroke 和 UUID。

**Render Engine**

负责 Live Ink、Tile Cache、Dirty Rectangle 与墨水屏刷新。

**Command System**

负责全部编辑行为、Undo 和 Redo。

**Persistence Layer**

负责 SQLite、自动保存、事务和异常恢复。

只要这五部分保持清晰边界，之后加入 Image、Audio、Sync、OCR 和 AI 都属于在既有架构上增加能力，而不需要重新设计整个笔记系统。

这也是本项目基础版本的首要架构目标。