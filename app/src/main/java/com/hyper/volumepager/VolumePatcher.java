package com.hyper.volumepager;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * 音量面板分页改造主体 —— libxposed API 102 全量迁移版。
 *
 * 分页模型:总页数 = 1 + ceil(动态应用列数 / PER_PAGE)
 *   第1页   = 原生容器(@volume_dialog_columns,媒体/铃声/闹钟三列,不碰)
 *   第2页起 = 应用音量列装入"胶片条"(可超宽水平 LinearLayout),
 *             外层裁剪视口(FrameLayout, clipChildren=true),翻页平移胶片条。
 *   圆点行  = 动态重建,数量随总页数变化;按 @volume_dialog_container 子项ID定位插入。
 *
 * Hook 点(API102 Chain/intercept 模型):
 *  H1  initPanelView()V                 after   注入视口/胶片条/圆点行
 *  H2  addColumn(IZZ)V                  around  (true,true)=动态流约定 → 置标记
 *  H3  $VolumeColumns.addView(View[/int]) before 动态列入胶片条,吞掉原调用(不proceed)
 *  H4  $VolumeColumns.removeView(View)  before  从实际父容器移除并吞掉原调用
 *  H5  $VolumeColumns.updateExpandedH(Z) around 归还/借出 + 收起复位
 *  补充 onStateChangedH 系列 与 setActiveStream(IZ) 的 after:折叠态活动流变化补借出
 */
public final class VolumePatcher {

    /** 总开关(false 时所有 hook 直通原生) */
    public static volatile boolean ENABLED = true;

    /** 每页容纳的应用音量条数 */
    public static final int PER_PAGE = 3;

    private static volatile boolean installedFlag = false;

    private static volatile XposedInterface xp;
    private static volatile Object controller;
    private static volatile Class<?> vcClass;
    private static volatile Class<?> colsClass;
    private static volatile ViewGroup content;        // @volume_dialog_content
    private static volatile LinearLayout dialogRoot;  // MiuiVolumeDialogView(extends LinearLayout)
    private static volatile ViewGroup page1;          // @volume_dialog_columns
    private static volatile FrameLayout pageViewport; // 裁剪视口
    private static volatile LinearLayout pageStrip;   // 胶片条
    private static volatile LinearLayout dotsRow;
    private static final List<View> DOT_CIRCLES = new ArrayList<>();
    private static volatile ViewGroup collapsedBox;   // VolumeColumns.mColumnsCollapsed

    private static volatile int page = 0;
    private static volatile boolean expanded = false;
    private static volatile View borrowedView;
    private static volatile boolean ccMode = false;

    private static final Set<View> DYNAMIC_VIEWS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** H2→H3 同栈传递:addColumn(IZZ) 的 (z,z2)===(true,true) 即动态流 */
    private static final ThreadLocal<Integer> PENDING_DYNAMIC = new ThreadLocal<>();

    private static volatile boolean dotColorsResolved = false;
    private static int colSelected = 0xE6FFFFFF;
    private static int colUnselected = 0x2EFFFFFF;
    private static volatile int ID_container = 0;

    private VolumePatcher() {}

    /** PluginLoaderCapture 回调用 */
    public static XposedInterface xp() {
        if (xp == null) throw new IllegalStateException("not installed yet");
        return xp;
    }

    public static boolean isInstalled() {
        return installedFlag;
    }

    // ==================================================================
    // 安装
    // ==================================================================

    public static void install(final XposedInterface api, final ClassLoader hostClRef) {
        xp = api;
        hostClRef.getClass(); // NPE 快速失败

        try {
            Class<?> rid = hostClRef.loadClass("com.android.systemui.miui.volume.R$id");
            ID_container = getStaticInt(rid, "volume_dialog_container");
        } catch (Throwable t) {
            Logx.w("resolve R$id.volume_dialog_container failed, use index fallback");
        }

        vcClass = hostClRef.loadClass(
                "com.android.systemui.miui.volume.VolumePanelViewController");
        colsClass = hostClRef.loadClass(
                "com.android.systemui.miui.volume.VolumePanelViewController$VolumeColumns");

        // ---------------- H1 initPanelView ----------------
        hook(vcClass, "initPanelView", "H1", chain -> {
            Object r = chain.proceed();
            onInitPanelView(chain.getThisObject());
            return r;
        });

        // ---------------- H2 addColumn(IZZ) ----------------
        hookByTypes(vcClass, "addColumn",
                new Class<?>[]{boolean.class, boolean.class, boolean.class}, "H2",
                chain -> {
                    List<Object> a = chain.getArgs();
                    controller = chain.getThisObject(); // 自愈同步
                    if (Boolean.TRUE.equals(a.get(1)) && Boolean.TRUE.equals(a.get(2))) {
                        PENDING_DYNAMIC.set((Integer) a.get(0));
                    }
                    Object r;
                    try {
                        r = chain.proceed();
                    } finally {
                        PENDING_DYNAMIC.remove(); // H3 已消费或异常路径均安全
                    }
                    return r;
                });

        // ---------------- H3 addView(View)/(View,int) ----------------
        Hooker addHooker = chain -> {
            Integer sid = PENDING_DYNAMIC.get();
            if (sid == null) return chain.proceed();
            PENDING_DYNAMIC.remove();
            refreshCc();
            if (!ENABLED || ccMode) return chain.proceed(); // CC 形态保持原生混排
            try {
                View child = (View) chain.getArgs().get(0);
                DYNAMIC_VIEWS.add(child);
                if (borrowedView == child) borrowedView = null;
                ensurePagedUi();
                if (pageStrip != null) {
                    pageStrip.addView(child);
                    child.setTranslationX(0f);
                    resolveDotColorsFrom(child);
                    onDynamicSetChanged();
                    Logx.i("dynamic column routed to strip (stream=" + sid + ")");
                    return null; // 吞掉原生路由 → reparentChildren 扫不到
                }
                Logx.w("paged ui unavailable, falls back to native");
            } catch (Throwable t) {
                Logx.e("H3 route failed", t);
            }
            return chain.proceed();
        };
        hookByTypes(colsClass, "addView", new Class<?>[]{View.class}, "H3a", addHooker);
        try {
            hookByTypes(colsClass, "addView",
                    new Class<?>[]{View.class, int.class}, "H3b", addHooker);
        } catch (Throwable ignore) { }

        // ---------------- H4 removeView(View) ----------------
        hookByTypes(colsClass, "removeView", new Class<?>[]{View.class}, "H4",
                chain -> {
                    View child = (View) chain.getArgs().get(0);
                    if (!ENABLED || !DYNAMIC_VIEWS.contains(child)) return chain.proceed();
                    ViewGroup p = (ViewGroup) child.getParent();
                    if (p != null) p.removeView(child);
                    if (borrowedView == child) borrowedView = null;
                    onDynamicSetChanged();
                    return null;
                });

        // ---------------- H5 $VolumeColumns.updateExpandedH(Z) ----------------
        hookByTypes(colsClass, "updateExpandedH", new Class<?>[]{boolean.class}, "H5",
                chain -> {
                    boolean expArg = Boolean.TRUE.equals(chain.getArgs().get(0));
                    if (expArg) repatriateBorrowed();     // 先归还,防被原生搬走
                    Object r = chain.proceed();
                    expanded = expArg;
                    if (!expArg) {
                        collapseUi();
                        borrowActiveIfDynamic();
                    } else {
                        refreshDots();
                    }
                    return r;
                });

        // ---------------- 补充触发 ----------------
        for (final Method m : vcClass.getDeclaredMethods()) {
            if (!"onStateChangedH".equals(m.getName())) continue;
            final String id = "S1-" + m.getName();
            xp.hook(m).setId(id).intercept(chain -> {
                Object r = chain.proceed();
                if (!ENABLED || expanded) return r;
                borrowActiveIfDynamic();
                return r;
            });
        }
        try {
            hookByTypes(vcClass, "setActiveStream",
                    new Class<?>[]{int.class, boolean.class}, "S2",
                    chain -> {
                        Object r = chain.proceed();
                        if (!ENABLED || expanded) return r;
                        borrowActiveIfDynamic();
                        return r;
                    });
        } catch (Throwable ignore) { }

        installedFlag = true;
        Logx.i("volume pager hooks installed (api102, dynamic pages, PER_PAGE="
                + PER_PAGE + ")");
    }

    private static HookHandle hook(Class<?> declaring, String name, String id,
                                   Hooker body) throws Throwable {
        return xp.hook(declaring.getDeclaredMethod(name)).setId(id).intercept(body);
    }

    private static void hookByTypes(Class<?> declaring, String name,
                                    Class<?>[] types, String id,
                                    Hooker body) throws Throwable {
        xp.hook(declaring.getDeclaredMethod(name, types)).setId(id).intercept(body);
    }

    // ==================================================================
    // 反射小工具(替代原 XposedHelpers)
    // ==================================================================

    private static Object getField(Object obj, String name)
            throws ReflectiveOperationException {
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static int getIntField(Object obj, String name)
            throws ReflectiveOperationException {
        return (Integer) getField(obj, name);
    }

    private static boolean getBoolField(Object obj, String name)
            throws ReflectiveOperationException {
        return (Boolean) getField(obj, name);
    }

    private static int getStaticInt(Class<?> clazz, String name)
            throws ReflectiveOperationException {
        Field f = clazz.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static Object callMethod(Object obj, String name, Object... args)
            throws ReflectiveOperationException {
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != args.length) continue;
                m.setAccessible(true);
                try {
                    return m.invoke(obj, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable c = e.getCause();
                    throw new RuntimeException(c == null ? e : c);
                } catch (IllegalAccessException e) {
                    /* fallthrough */ }
            }
        }
        throw new NoSuchMethodException(name);
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

        content = (ViewGroup) getField(ctl, "mVolumeContentView");
        dialogRoot = (LinearLayout) getField(ctl, "mVolumeView");
        page1 = (ViewGroup) getField(ctl, "mVolumeContentColumns");

        Object volCols = getField(ctl, "mVolumeColumns");
        collapsedBox = volCols == null ? null
                : (ViewGroup) getField(volCols, "mColumnsCollapsed");

        if (content == null || dialogRoot == null) {
            Logx.w("initPanelView: content/dialogRoot null, skip inject");
            return;
        }
        content.setClipChildren(false);

        Context ctx = content.getContext();

        // ---- 视口 + 胶片条(重建时抢救旧胶片条中的动态列)----
        List<View> salvage = new ArrayList<>();
        if (pageStrip != null) {
            for (int i = pageStrip.getChildCount() - 1; i >= 0; i--) {
                View c = pageStrip.getChildAt(i);
                if (DYNAMIC_VIEWS.contains(c)) salvage.add(c);
            }
            Collections.reverse(salvage);
        }
        if (pageViewport != null && pageViewport.getParent() instanceof ViewGroup) {
            ((ViewGroup) pageViewport.getParent()).removeView(pageViewport);
        }
        pageViewport = new FrameLayout(ctx);
        pageViewport.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pageViewport.setClipChildren(true);
        pageViewport.setVisibility(View.GONE);

        pageStrip = new LinearLayout(ctx);
        pageStrip.setOrientation(LinearLayout.HORIZONTAL);
        pageStrip.setClipChildren(false);
        pageStrip.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pageViewport.addView(pageStrip);
        for (View v : salvage) {
            pageStrip.addView(v);
            v.setTranslationX(0f);
        }
        content.addView(pageViewport);

        // ---- 圆点行(按 @volume_dialog_container 子项ID定位)----
        if (dotsRow != null && dotsRow.getParent() instanceof ViewGroup) {
            ((ViewGroup) dotsRow.getParent()).removeView(dotsRow);
        }
        dotsRow = new LinearLayout(ctx);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        dotsRow.setVisibility(View.GONE);
        int insertIdx = -1;
        if (ID_container != 0) {
            for (int i = 0; i < dialogRoot.getChildCount(); i++) {
                View ch = dialogRoot.getChildAt(i);
                if (ch != null && ch.getId() == ID_container) { insertIdx = i + 1; break; }
            }
        }
        if (insertIdx < 0) insertIdx = Math.min(1, dialogRoot.getChildCount());
        dialogRoot.addView(dotsRow, insertIdx);

        refreshDots();
        Logx.i("paged ui injected");
    }

    // ==================================================================
    // 页数 / 翻页
    // ==================================================================

    private static int extraPageCount() {
        int n = DYNAMIC_VIEWS.size();
        return n <= 0 ? 0 : (n + PER_PAGE - 1) / PER_PAGE;
    }

    private static int lastPageIndex() {
        return extraPageCount();
    }

    private static void onDynamicSetChanged() {
        int last = lastPageIndex();
        if (page > last) {
            if (expanded) {
                page = last;
                animateToPage(page);
            } else {
                page = 0;
            }
        }
        refreshDots();
    }

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
        page1.animate().translationX(p1To).setDuration(260L).start();
        pageStrip.animate().translationX(sTo).setDuration(260L).withEndAction(() -> {
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

    private static void borrowActiveIfDynamic() {
        if (borrowedView != null) return;
        refreshCc();
        if (ccMode) return;
        Object ctl = controller;
        if (ctl == null || pageStrip == null || collapsedBox == null) return;
        if (DYNAMIC_VIEWS.isEmpty()) return;
        try {
            int active = getIntField(ctl, "mActiveStream");
            Object col = callMethod(ctl, "findColumn", active);
            if (col == null) return;
            View v = (View) getField(col, "view");
            if (!DYNAMIC_VIEWS.contains(v)) return;

            ViewGroup p = (ViewGroup) v.getParent();
            if (p == collapsedBox) return;
            if (p != null && p != pageStrip) return;
            if (p == pageStrip) pageStrip.removeView(v);
            collapsedBox.addView(v);
            v.setVisibility(View.VISIBLE); // 原生 Util.setVisOrGone 统一管理前的即时显示
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
        int pages = lastPageIndex() + 1;
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
            ((GradientDrawable) c.getBackground())
                    .setColor(i == page ? colSelected : colUnselected);
        }
    }

    private static void resolveDotColorsFrom(View pluginChild) {
        if (dotColorsResolved || pluginChild == null) return;
        try {
            Context pc = pluginChild.getContext();
            int id = pc.getResources()
                    .getIdentifier("miui_volume_color_primary", "color", "miui.systemui.plugin");
            if (id != 0) {
                int base = pc.getColor(id);
                colSelected = base;
                colUnselected = (base & 0x00FFFFFF) | 0x2E000000;
                dotColorsResolved = true;
                refreshDots();
                Logx.i("dot colors resolved from plugin resources");
            }
        } catch (Throwable t) {
            dotColorsResolved = true;
            Logx.w("resolve dot colors failed, use fallback");
        }
    }

    // ==================================================================
    // 守卫与防御
    // ==================================================================

    private static void refreshCc() {
        try {
            ccMode = controller != null && getBoolField(controller, "isControlCenterPanel");
        } catch (Throwable t) {
            ccMode = false;
        }
    }

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
