# 01 Windows 协议与技术选型

| 技术 | 评估 |
| --- | --- |
| JavaFX Clipboard | `DataFormat.FILES` 适合简单文件复制。API 在 JavaFX 线程访问，文件对象可能等候外部提供者延迟渲染。没有可将会话版本核验与更新放在同一剪贴板锁内的接口。 |
| AWT Clipboard | `javaFileListFlavor` 和 `ClipboardOwner` 可用于可移植文件和文本共享。所有权丢失回调由系统异步派发，Java API 缺少持锁条件更新及完整的 Shell 移动反馈。 |
| 已有 JNA 加 Win32 | 可以在独立线程获取剪贴板版本、读取和发布指定格式，在同一临界区检查版本并更新。项目已依赖 JNA。 |
| OLE IDataObject | 可以提供延迟文件内容和 Shell 移动协议的回调，涉及 STA 消息循环、引用计数、所有权、完成语义及各接收端的兼容性。阶段 2.6 用于隔离实验。 |

阶段 2.6 选择 JNA 适配器。发布端即时填充 Unicode `CF_HDROP`、`Preferred DropEffect=COPY` 和一个随机会话标记。Unicode `DROPFILES` 头部给出路径列表偏移，后接 NUL 终止的 UTF-16 路径，列表用额外的 NUL 结束。Windows 接管成功传入的可移动全局内存。代码释放尚未移交的块，并及时解除读锁。剪贴板占用按 15、30、45 ms 各等待一次，随后报告失败。 [JavaFX](https://openjfx.io/javadoc/21/javafx.graphics/javafx/scene/input/Clipboard.html) · [AWT](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/datatransfer/Clipboard.html) · [DROPFILES](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/ns-shlobj_core-dropfiles) · [SetClipboardData](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setclipboarddata)

隔离实验中，只有 `CF_HDROP` 的应用数据可被 `DragQueryFileW` 解码，Shell 的 `SHCreateShellItemArrayFromDataObject` 返回 `DV_E_FORMATETC`。为此对可解析的本地固定磁盘路径补充 `Shell IDList Array`。项目标识由 `SHParseDisplayName` 产生，按 CIDA 结构编码。Shell 接收端随后通过 `IFileOperation` 完成文件与目录复制。无法生成项目标识的路径仍可通过标准文件列表传入支持该格式的消费者，Shell 接收兼容性需要按路径类型验证。 [CIDA](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/ns-shlobj_core-cida) · [Shell 项目数组](https://learn.microsoft.com/en-us/windows/win32/api/shobjidl_core/nf-shobjidl_core-shcreateshellitemarrayfromdataobject) · [原生接收测试](../../../src/test/java/edu/files/ShellClipboardCopy.java)

| Shell 格式 | 意义 |
| --- | --- |
| `CF_HDROP` | 已存在的文件系统路径列表，可包含文件和目录。 |
| `Preferred DropEffect` | 来源表达的意图。COPY 位为 1，MOVE 位为 2。Windows Shell 实测复制写入 5，即 COPY 与 LINK 位，剪切写入 2。包含 MOVE 位时拒绝转入复制路径。 |
| `Performed DropEffect` | 目标报告实际传输效果。不同移动方式下，来源是否还有工作要做取决于协议。 |
| `Logical Performed DropEffect` | 移动等操作的逻辑结果，可能和实际执行过程采用的文件操作不同。 |
| `Paste Succeeded` | 在删除式粘贴协议中表示目标完成传输，某些数据源接到它后可能删除来源。 |

Shell 对同卷优化移动、先复制再删除和虚拟文件使用不同协商。单写 MOVE 会把普通路径交给接收者，在路径同名替换后无法以本应用的身份凭据约束对方。外部结果字段也缺少源对象的可靠身份。内部剪切因此使用本窗口的私有标记与文件引擎，不发布标准 MOVE，不响应外部完成字段进行删除。 [Shell Clipboard Formats](https://learn.microsoft.com/en-us/windows/win32/shell/clipboard) · [Shell Data Transfer Scenarios](https://learn.microsoft.com/en-us/windows/win32/shell/datascenarios)

剪贴板序号属于窗口站。延迟渲染在取到数据时才可能更新序号，所以应用在显式粘贴时读快照，并记录取数后的版本。日常探测只读序号和格式存在性，避免主动请求第三方文本、图片或延迟文件内容。监听消息需要独立消息泵，单次主动粘贴缺少及时的控件和剪切标记反馈。本阶段结合焦点事件、约 450 ms 的轻量探测和按需完整读取。 [剪贴板序号](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getclipboardsequencenumber) · [剪贴板所有权及延迟渲染](https://learn.microsoft.com/en-us/windows/win32/dataxchg/clipboard-operations)
