# HyperVolumePager — HyperOS 音量面板多应用音量分页模块(LSPosed)

> 目标:`系统界面组件`(miui.systemui.plugin)v18.2.1.88.0 + `系统界面`(com.android.systemui)宿主。
> 效果:展开音量面板后,第1页保持默认「媒体 / 铃声 / 闹钟」三列;发声应用音量列按**每页 3 条动态分页**
> (总页数 = 1 + ⌈应用数÷3⌉,例:5个App → 共3页),两区之间圆点指示随页数自动增减;
> 折叠态行为与原生完全一致(活动应用流照常显示在折叠条上)。

## 安装使用

1. GitHub Actions 构建产物 `HyperVolumePager-debug-apk`(debug 签名可直接安装);
2. LSPosed 中启用本模块,作用域勾选 **系统界面(com.android.systemui)**;
3. 重启 SystemUI(或重启手机);
4. 播放任意 App 音频 → 按音量键 → 点面板上的 ⋯ 展开 → 两区之间的圆点行按页切换(圆点数 = 应用分页数 + 1)。

## 工程结构

```
app/src/main/java/com/hyper/volumepager/
├── HookEntry.java           入口:仅处理 com.android.systemui 进程
├── PluginLoaderCapture.java 捕获 miui.systemui.plugin 的 ClassLoader(Factory.create + 构造器双锚点)
├── VolumePatcher.java       全部业务 hook(H1~H5)+ 第2页/圆点注入 + 借出归还逻辑
└── Logx.java                日志(logcat 过滤 HyperVolumePager)
```

## Hook 点速查(细节见开发文档)

| 编号 | 目标 | 说明 |
|---|---|---|
| H0 | `PluginInstance$Factory.create*` / `PluginInstance.<init>` | 捕获插件 ClassLoader |
| H1 | `VolumePanelViewController.initPanelView()` after | 注入裁剪视口+胶片条(动态分页)+ 动态圆点行 |
| H2 | `addColumn(IZZ)` before/after | `(true,true)` 即动态流调用约定,置标记 |
| H3 | `$VolumeColumns.addView(View[/int])` before | 动态列改投胶片条并吞掉原调用 |
| H4 | `$VolumeColumns.removeView(View)` before | 从实际父容器移除 |
| H5 | `$VolumeColumns.updateExpandedH(Z)` before/after | 归还/借出 + 收起复位 |
| 补充 | `onStateChangedH*` / `setActiveStream(IZ)` after | 折叠态活动流变化补借出 |

## 二次逆向复核结论(2024 复核记录)

对 v18.2.1.88.0 逐点重验后,模块已按以下事实加固:

1. **`addColumn(IZZ)` 存在两条容器路径**:常规 `addView(View)` 与无障碍流在场时的
   `addView(View, index)`(插到倒数第二位)——因此 H3 **必须同时 hook 双重载**(已实现);
   且两条路径都必然经过 VolumeColumns,H3 劫持点完备,不存在绕行。
2. **`addColumn(IZZ)` 全 APK 仅 2 个调用点**:`addColumn(IZ)` 包装器(z2=false)与
   onStateChangedH 的动态流 `(streamId,true,true)` —— H2 判据严格成立。
3. **委托链确认**:`updateExpandedH(ZZZ)` 中部显式调用
   `mVolumeColumns.updateExpandedH(mExpanded)` → H5 挂在 VolumeColumns 层时机正确;
   before 归还先于原生 reparentChildren 执行,借出列不会被误搬。
4. **新增 CC 守卫**:控制器字段 `isControlCenterPanel`(私有boolean)证实控制中心卡片
   与按键弹窗共用同一套控制器/布局。已增加 `refreshCc()` 守卫:CC 形态下 H3 不路由、
   不借出,完全保持原生混排行为。
5. **可见性机制确认**:列显示/隐藏由 `Util.setVisOrGone(View,Z)` 统一管理;
   借出落位后补一次 `setVisibility(VISIBLE)` 保证弹出瞬间立即可见。

## 注意事项

- 清单声明 `xposedminversion=101`(按需调整;hook 代码为经典 Bridge API,全版本兼容)。
- 控制中心形态、⋯按钮、铃声模式区、定时器、动画类全部未触碰,异常时模块自动静默回退原生行为。
- 调试:`adb logcat -s HyperVolumePager`。
