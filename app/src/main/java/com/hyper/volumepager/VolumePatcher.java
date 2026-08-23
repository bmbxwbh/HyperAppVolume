package com.hyper.volumepager;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 音量面板分页改造主体(动态页数版)。
 *
 * 分页模型:
 *   总页数 = 1 + ceil(动态应用列数 / PER_PAGE)
 *   第1页      = 原生容器(@volume_dialog_columns,默认媒体/铃声/闹钟三列,不碰)
 *   第2页起    = 应用音量列,每页最多 PER_PAGE 条,装入"胶片条"(可超宽的水平 LinearLayout),
 *                外层为裁剪视口(FrameLayout, clipChildren=true),翻页即平移胶片条。
 *   圆点行     = 动态重建,数量随总页数变化;插在竖条区与静音/勿扰区之间(index=1)。
 *
 * 布局改造后结构:
 *
 * MiuiVolumeDialogView (vertical LinearLayout)
 * ├─ [0] @volume_dialog_container
 * │    └─ @volume_dialog_content (FrameLayout)
 * │         ├─ @volume_dialog_columns          ← 第1页(原生,不碰)
 * │         ├─ pageViewport                    ← 注入:裁剪视口(GONE↔VISIBLE)
 * │         │    └─ pageStrip                  ← 注入:胶片条(所有动态列)
 * │         ├─ @volume_dialog_column_collapsed (原生,勿动;借出列临时栖身处)
 * │         └─ @volume_expand_button           ← 原生⋯按钮,勿动
 * ├─ [1] dotsRow                               ← 注入:动态圆点行
 * └─ [2] 铃声模式区(原生 gt3.xml,不碰)
 *
 * Hook 清单:
 *  H0  PluginLoaderCapture                     捕获插件 ClassLoader(见该类)
 *  H1  initPanelView()V               after    注入视口/胶片条/圆点行
 *  H2  addColumn(IZZ)V                before   (true,true)=动态流调用约定 → 置标记
 *  H3  $VolumeColumns.addView(View[/int]) before 标记列入胶片条并吞掉原调用
 *  H4  $VolumeColumns.removeView(View) before 从实际父容器移除并吞掉原调用
 *  H5  $VolumeColumns.updateExpandedH(Z) before/after 归还/借出 + 收起复位 + 圆点同步
 *  补充 onStateChangedH*/setActiveStream(IZ) after 折叠态活动流变化补借出
 */
public final class VolumePatcher {

    /** 总开关(false 时所有 hook 直通原生) */
    public static volatile boolean ENABLED = true;

    /** 每页容纳的应用音量条数(第1页为原生默认三列,不受此值影响) */
    public static final int PER_PAGE = 3;

    private static final long ANIM_MS = 260L;
    private static final String PLUGIN_PKG = "miui.systemui.plugin";

    // ---------------- 运行时 UI 引用 ----------------
    private static volatile Object controller;        // VolumePanelViewController 实例
    private static volatile ViewGroup content;        // @volume_dialog_content
    private static volatile LinearLayout dialogRoot;  // MiuiVolumeDialogView
    private static volatile ViewGroup page1;          // @volume_dialog_columns
    private static volatile FrameLayout pageViewport; // 裁剪视口
    private static volatile LinearLayout pageStrip;   // 胶片条(装全部动态列)
    private static volatile LinearLayout dotsRow;     // 圆点行
    private static final List<View> DOT_CIRCLES = new ArrayList<>();
    private static volatile ViewGroup collapsedBox;   // VolumeColumns.mColumnsCollapsed

    private static volatile int page = 0;             // 当前页:0=默认,1..n=应用音量分页
    private static volatile boolean expanded = false;
    private static volatile View borrowedView;        // 被借出到折叠容器的动态列
    private static volatile boolean ccMode = false;   // 控制中心形态守卫(isControlCenterPanel)

    /** 所有动态列视图(身份集合) */
    private static final Set<View> DYNAMIC_VIEWS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** H2→H3 同栈传递:addColumn(IZZ) 的 (z,z2)===(true,true) 即动态流 */
    private static final ThreadLocal<Integer> PENDING_DYNAMIC = new ThreadLocal<>();

    /** 圆点颜色(首次拿到插件 Context 后解析) */
    private static volatile boolean dotColorsResolved = false;
    private static int colSelected = 0xE6FFFFFF;
    private static int colUnselected = 0x2EFFFFFF;

    private VolumePatcher() {}

    // ==================================================================
    // 安装入口
    // ==================================================================

    public static void install(final ClassLoader cl) {
        final Class<?> vc = XposedHelpers.findClass(
                "com.android.systemui.miui.volume.VolumePanelViewController", cl);
        final Class<?> cols = XposedHelpers.findClass(
                "com.android.systemui.miui.volume.VolumePanelViewController$VolumeColumns", cl);

        // ---------------- H1 initPanelView ----------------
        XposedHelpers.findAndHookMethod(vc, "initPanelView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ENABLED) return;
                try {
                    onInitPanelView(param.thisObject);
                } catch (Throwable t) {
                    Logx.e("H1 inject failed", t);
                }
            }
        });

        // ---------------- H2 addColumn(IZZ) ----------------
        XposedHelpers.findAndHookMethod(vc, "addColumn",
                boolean.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ENABLED) return;
                        if (Boolean.TRUE.equals(param.args[1])
                                && Boolean.TRUE.equals(param.args[2])) {
                            PENDING_DYNAMIC.set((Integer) param.args[0]);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 正常路径:H3 已在方法体内同步消费;异常路径:此处兜底清除
                        PENDING_DYNAMIC.remove();
                    }
                });

        // ---------------- H3 addView(View) / addView(View,int) ----------------
        // 反编译确认:addColumn(IZZ) 存在两条容器路径——常规 addView(View),
        // 以及无障碍流在场时 addView(View,index)(插到倒数第二位),故双重载都要 hook。
        XC_MethodHook addHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Integer sid = PENDING_DYNAMIC.get();
                if (sid == null) return;
                PENDING_DYNAMIC.remove();
                if (!ENABLED) return;
                refreshCc();
                if (ccMode) {
                    // 控制中心卡片形态:保持原生混排路由
                    Logx.i("cc panel: dynamic column keeps native routing");
                    return;
                }
                try {
                    View child = (View) param.args[0];
                    DYNAMIC_VIEWS.add(child);
                    if (borrowedView == child) borrowedView = null;
                    ensurePagedUi();
                    if (pageStrip != null) {
                        pageStrip.addView(child);   // 列自带原生 LinearLayout LP,直接复用
                        child.setTranslationX(0f);
                        resolveDotColorsFrom(child);
                        onDynamicSetChanged();
                        param.result = null;        // 吞掉原生路由
                        Logx.i("dynamic column routed to strip (stream=" + sid + ")");
                    } else {
                        Logx.w("paged ui unavailable, falls back to native");
                    }
                } catch (Throwable t) {
                    Logx.e("H3 route failed", t);
                }
            }
        };
        XposedHelpers.findAndHookMethod(cols, "addView", View.class, addHook);
        try {
            XposedHelpers.findAndHookMethod(cols, "addView", View.class, int.class, addHook);
        } catch (Throwable ignore) { }

        // ---------------- H4 removeView(View) ----------------
        XposedHelpers.findAndHookMethod(cols, "removeView", View.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View child = (View) param.args[0];
                if (!ENABLED || !DYNAMIC_VIEWS.contains(child)) return;
                try {
                    ViewGroup p = (ViewGroup) child.getParent();
                    if (p != null) p.removeView(child);
                    if (borrowedView == child) borrowedView = null;
                    onDynamicSetChanged();
                    param.result = null;
                } catch (Throwable t) {
                    Logx.e("H4 remove failed", t);
                }
            }
        });

        // ---------------- H5 $VolumeColumns.updateExpandedH(Z) ----------------
        XposedHelpers.findAndHookMethod(cols, "updateExpandedH", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ENABLED) return;
                        // 展开:先把借出的列还回胶片条,避免被原生 reparentChildren 搬走
                        if (Boolean.TRUE.equals(param.args[0])) repatriateBorrowed();
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!ENABLED) return;
                        boolean exp = Boolean.TRUE.equals(param.args[0]);
                        expanded = exp;
                        if (!exp) {
                            collapseUi();
                            borrowActiveIfDynamic();
                        } else {
                            refreshDots();
                        }
                    }
                });

        // ---------------- 补充触发:折叠态活动流变化 ----------------
        for (final Method m : vc.getDeclaredMethods()) {
            if (!"onStateChangedH".equals(m.getName())) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!ENABLED || expanded) return;
                    try {
                        borrowActiveIfDynamic();
                    } catch (Throwable t) {
                        Logx.e("borrow(onStateChangedH) failed", t);
                    }
                }
            });
        }
        try {
            XposedHelpers.findAndHookMethod(vc, "setActiveStream",
                    int.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!ENABLED || expanded) return;
                            try {
                                borrowActiveIfDynamic();
                            } catch (Throwable ignored) { }
                        }
                    });
        } catch (Throwable ignore) { }

        Logx.i("volume pager hooks installed (dynamic pages, PER_PAGE=" + PER_PAGE + ")");
    }

    // ==================================================================
    // 页数计算
    // ==================================================================

    /** 除第1页外的应用音量分页数 */
    private static int extraPageCount() {
        int n = DYNAMIC_VIEWS.size();
        if (n <= 0) return 0;
        return (n + PER_PAGE - 1) / PER_PAGE;
    }

    private static int lastPageIndex() {
        return extraPageCount(); // 页索引 0..N,N=extraPageCount
    }

    /** 动态列集合变化后的重排:夹取当前页、重建圆点、刷新显隐 */
    private static void onDynamicSetChanged() {
        int last = lastPageIndex();
        if (page > last) {
            if (expanded) {
                page = last;
                animateToPage(page);
            } else {
                page = 0;   // 折叠态无需维持页位
            }
        }
        refreshDots();
    }

    // ==================================================================
    // H1:注入视口 + 胶片条 + 圆点行
    // ==================================================================

    private static void onInitPanelView(Object ctl) {
        controller = ctl;
        refreshCc();
        DYNAMIC_VIEWS.clear();
        DOT_CIRCLES.clear();
        borrowedView = null;
        page = 0;
        expanded = false;

        content = (ViewGroup) XposedHelpers.getObjectField(ctl, "mVolumeContentView");
        dialogRoot = (LinearLayout) XposedHelpers.getObjectField(ctl, "mVolumeView");
        page1 = (ViewGroup) XposedHelpers.getObjectField(ctl, "mVolumeContentColumns");

        Object volCols = XposedHelpers.getObjectField(ctl, "mVolumeColumns");
        collapsedBox = volCols == null ? null
                : (ViewGroup) XposedHelpers.getObjectField(volCols, "mColumnsCollapsed");

        if (content == null || dialogRoot == null) {
            Logx.w("initPanelView: content/dialogRoot null, skip inject");
            return;
        }
        content.setClipChildren(false);

        Context ctx = content.getContext();

        // ---- 视口 + 胶片条 ----
        if (pageViewport != null && pageViewport.getParent() instanceof ViewGroup) {
            ((ViewGroup) pageViewport.getParent()).removeView(pageViewport);
        }
        pageViewport = new FrameLayout(ctx);
        pageViewport.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pageViewport.setClipChildren(true);   // 胶片条出界部分被裁掉 → 电影胶片效果
        pageViewport.setVisibility(View.GONE);

        pageStrip = new LinearLayout(ctx);
        pageStrip.setOrientation(LinearLayout.HORIZONTAL);
        pageStrip.setClipChildren(false);
        pageStrip.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pageViewport.addView(pageStrip);
        content.addView(pageViewport);

        // ---- 圆点行(插在竖条区与静音/勿扰区之间:index=1)----
        if (dotsRow != null && dotsRow.getParent() instanceof ViewGroup) {
            ((ViewGroup) dotsRow.getParent()).removeView(dotsRow);
        }
        dotsRow = new LinearLayout(ctx);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        dotsRow.setVisibility(View.GONE);
        dialogRoot.addView(dotsRow, 1);

        refreshDots();
        Logx.i("paged ui injected");
    }

    // ==================================================================
    // 翻页控制
    // ==================================================================

    private static float stripOffsetFor(int p, int w) {
        return (p <= 0) ? 0f : -(float) (p - 1) * w;
    }

    private static void animateToPage(int target) {
        if (content == null || page1 == null || pageViewport == null || pageStrip == null) return;
        int max = lastPageIndex();
        target = Math.max(0, Math.min(target, max));
        if (target == page) return;

        int w = Math.max(content.getWidth(), page1.getWidth());
        if (w <= 0) w = 1;

        float p1To = (target >= 1) ? -w : 0f;
        float sFrom = stripOffsetFor(page, w);
        float sTo = stripOffsetFor(target, w);

        pageViewport.setVisibility(View.VISIBLE);
        pageStrip.setTranslationX(sFrom);
        page1.animate().translationX(p1To).setDuration(ANIM_MS).start();
        pageStrip.animate().translationX(sTo).setDuration(ANIM_MS).withEndAction(() -> {
            if (page == 0 && pageViewport != null) pageViewport.setVisibility(View.GONE);
        }).start();

        page = target;
        refreshDots();
    }

    private static void collapseUi() {
        page = 0;
        if (page1 != null) page1.setTranslationX(0f);
        if (pageStrip != null) pageStrip.setTranslationX(0f);
        if (pageViewport != null) pageViewport.setVisibility(View.GONE);
        if (dotsRow != null) dotsRow.setVisibility(View.GONE);
    }

    // ==================================================================
    // 借出 / 归还
    // ==================================================================

    /** 展开(H5 before z=true):把借出到折叠容器的动态列归还胶片条 */
    private static void repatriateBorrowed() {
        if (borrowedView == null) return;
        try {
            ViewGroup p = (ViewGroup) borrowedView.getParent();
            if (p != null && p != pageStrip) p.removeView(borrowedView);
            if (pageStrip != null && borrowedView.getParent() != pageStrip) {
                pageStrip.addView(borrowedView);
            }
        } catch (Throwable t) {
            Logx.e("repatriate failed", t);
        } finally {
            borrowedView = null;
        }
    }

    /**
     * 折叠态:若当前活动流是动态应用列,把它从胶片条"借"到折叠容器,
     * 保证按音量键弹出的折叠条正常显示该App音量(否则会空白)。
     */
    private static void borrowActiveIfDynamic() {
        if (borrowedView != null) return;                       // 已有借出
        refreshCc();
        if (ccMode) return;                                     // 控制中心形态不借出
        Object ctl = controller;
        if (ctl == null || pageStrip == null || collapsedBox == null) return;
        if (DYNAMIC_VIEWS.isEmpty()) return;
        try {
            int active = XposedHelpers.getIntField(ctl, "mActiveStream");
            Object col = XposedHelpers.callMethod(ctl, "findColumn", active);
            if (col == null) return;
            View v = (View) XposedHelpers.getObjectField(col, "view");
            if (!DYNAMIC_VIEWS.contains(v)) return;             // 只处理动态列

            ViewGroup p = (ViewGroup) v.getParent();
            if (p == collapsedBox) return;                      // 已经在折叠容器
            if (p != null && p != pageStrip) return;
            if (p == pageStrip) pageStrip.removeView(v);
            collapsedBox.addView(v);
            // 可见性由原生 Util.setVisOrGone(View,Z) 统一管理;
            // 此处强制 VISIBLE 保证折叠弹出瞬间活动列立即可见,后续原生逻辑接管
            v.setVisibility(View.VISIBLE);
            borrowedView = v;
            Logx.i("active dynamic column borrowed to collapsed box (stream=" + active + ")");
        } catch (Throwable t) {
            Logx.e("borrow failed", t);
        }
    }

    // ==================================================================
    // 圆点行(动态重建)
    // ==================================================================

    private static void refreshDots() {
        if (dotsRow == null) return;
        int pages = lastPageIndex() + 1;                 // 含第1页
        boolean show = expanded && pages > 1;
        if (dotsRow.getChildCount() != pages) rebuildDots(pages);
        dotsRow.setVisibility(show ? View.VISIBLE : View.GONE);
        applyDotTints();
    }

    private static void rebuildDots(int pages) {
        if (dotsRow == null) return;
        Context ctx = dotsRow.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        dotsRow.removeAllViews();
        DOT_CIRCLES.clear();
        for (int i = 0; i < pages; i++) {
            FrameLayout cell = new FrameLayout(ctx);
            cell.setLayoutParams(new LinearLayout.LayoutParams((int) (26 * d), (int) (16 * d)));

            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setCornerRadius(3f * d);

            View circle = new View(ctx);
            circle.setBackground(gd);
            circle.setLayoutParams(new FrameLayout.LayoutParams(
                    (int) (6 * d), (int) (6 * d), Gravity.CENTER));
            cell.addView(circle);

            final int idx = i;
            cell.setOnClickListener(v -> animateToPage(idx));

            dotsRow.addView(cell);
            DOT_CIRCLES.add(circle);
        }
    }

    private static void applyDotTints() {
        for (int i = 0; i < DOT_CIRCLES.size(); i++) {
            View c = DOT_CIRCLES.get(i);
            if (c == null || !(c.getBackground() instanceof GradientDrawable)) continue;
            ((GradientDrawable) c.getBackground()).setColor(i == page ? colSelected : colUnselected);
        }
    }

    /** 首次拿到插件 Context 时解析圆点颜色(插件包资源,宿主 Context 解析不到) */
    private static void resolveDotColorsFrom(View pluginChild) {
        if (dotColorsResolved || pluginChild == null) return;
        try {
            Context pc = pluginChild.getContext();
            int id = pc.getResources()
                    .getIdentifier("miui_volume_color_primary", "color", PLUGIN_PKG);
            if (id != 0) {
                int base = pc.getColor(id);
                colSelected = base;
                colUnselected = (base & 0x00FFFFFF) | 0x2E000000; // 约18%透明度
                dotColorsResolved = true;
                refreshDots();
                Logx.i("dot colors resolved from plugin resources");
            }
        } catch (Throwable t) {
            dotColorsResolved = true; // 解析失败不再重试,使用兜底色
            Logx.w("resolve dot colors failed, use fallback");
        }
    }

    // ==================================================================
    // 守卫与防御性恢复
    // ==================================================================

    /**
     * 控制中心形态守卫:CC 音量卡片与按键弹窗复用同一控制器/布局体系
     * (字段 isControlCenterPanel,私有 boolean,反编译已核实)。
     * 分页路由、借出逻辑仅作用于弹窗形态;CC 形态一律保持原生。
     */
    private static void refreshCc() {
        try {
            ccMode = controller != null
                    && XposedHelpers.getBooleanField(controller, "isControlCenterPanel");
        } catch (Throwable t) {
            ccMode = false;
        }
    }

    /** 分页UI因异常丢失时,基于当前 controller 重建(H1 的最小子集) */
    private static void ensurePagedUi() {
        if (pageViewport != null && content != null && pageViewport.getParent() == content
                && pageStrip != null) {
            return;
        }
        if (controller != null) {
            Logx.w("paged ui missing, re-injecting via initPanelView state");
            onInitPanelView(controller);
        }
    }
}
