package com.hyper.volumepager.libhook;

import android.util.SparseArray;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * 音量面板分页改造(API 102)—— 多控制器版本。
 *
 * HyperOS 同时存在两个 VolumePanelViewController 实例:
 *   ① 按键弹窗面板(cc=false)
 *   ② 控制中心音量卡片(cc=true,保持原生不动)
 * 因此所有运行时状态按「控制器实例」隔离存储,禁止静态单例。
 */
public final class VolumePatcher {

    public static volatile boolean ENABLED = true;
    public static final int PER_PAGE = 3;

    private static volatile boolean installedFlag = false;
    private static volatile XposedInterface xp;
    private static volatile Class<?> vcClass;
    private static volatile Class<?> colsClass;
    private static volatile int ID_container = 0;

    /** 控制器实例 → 各自的注入状态 */
    private static final Map<Object, Ctx> CTX = new ConcurrentHashMap<>();
    /** VolumeColumns 实例 → 所属控制器 */
    private static final Map<Object, Object> COLS_OWNER = new ConcurrentHashMap<>();
    /** 全部动态列视图(全局身份集合) */
    private static final Set<View> DYNAMIC_VIEWS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ThreadLocal<Integer> PENDING_DYNAMIC = new ThreadLocal<>();

    private static volatile boolean dotColorsResolved = false;
    private static int colSelected = 0xE6FFFFFF;
    private static int colUnselected = 0x2EFFFFFF;
    private static volatile int ID_container = 0;

    /** 单个控制器的注入状态 */
    private static final class Ctx {
        final Object controller;
        volatile boolean ccMode = false;
        volatile boolean expanded = false;
        volatile int page = 0;
        volatile View borrowedView;
        volatile ViewGroup content;
        volatile LinearLayout dialogRoot;
        volatile ViewGroup page1;
        volatile FrameLayout pageViewport;
        volatile LinearLayout pageStrip;
        volatile LinearLayout dotsRow;
        volatile ViewGroup collapsedBox;
        volatile Object volumeColumns;
        final List<View> dotCircles = new ArrayList<>();

        Ctx(Object c) { controller = c; }
    }

    private VolumePatcher() {}

    public static boolean isInstalled() { return installedFlag; }

    // ==================================================================
    // 安装
    // ==================================================================

    public static void install(final XposedInterface api, final ClassLoader cl) {
        xp = api;
        try {
            try {
                Class<?> rid = cl.loadClass("com.android.systemui.miui.volume.R$id");
                ID_container = getStaticInt(rid, "volume_dialog_container");
            } catch (Throwable t) {
                Logx.w("resolve R$id.volume_dialog_container failed");
            }

            vcClass = cl.loadClass(
                    "com.android.systemui.miui.volume.VolumePanelViewController");
            colsClass = cl.loadClass(
                    "com.android.systemui.miui.volume.VolumePanelViewController$VolumeColumns");

            // H1 initPanelView
            hook1(vcClass.getDeclaredMethod("initPanelView"));

            // H2 addColumn(IZZ)
            hookByTypes(vcClass, "addColumn",
                    new Class<?>[]{int.class, boolean.class, boolean.class}, "H2",
                    VolumePatcher::h2Intercept);

            // H3 addView(View)/(View,int)
            Hooker h3 = VolumePatcher::h3Intercept;
            hookByTypes(colsClass, "addView", new Class<?>[]{View.class}, "H3a", h3);
            try {
                hookByTypes(colsClass, "addView",
                        new Class[]{View.class, int.class}, "H3b", h3);
            } catch (Throwable ignore) { }

            // H4 removeView(View)
            hookByTypes(colsClass, "removeView", new Class<?>[]{View.class}, "H4",
                    VolumePatcher::h4Intercept);

            // H5 $VolumeColumns.updateExpandedH(Z)
            hookByTypes(colsClass, "updateExpandedH",
                    new Class<?>[]{boolean.class}, "H5", VolumePatcher::h5Intercept);

            // 补充:onStateChangedH*
            for (final Method m : vcClass.getDeclaredMethods()) {
                if (!"onStateChangedH".equals(m.getName())) continue;
                final String id = "S1-" + m.getName();
                xp.hook(m).setId(id).intercept(chain -> {
                    Object r = chain.proceed();
                    try {
                        Object ctl = chain.getThisObject();
                        dumpStreams(ctl);
                        Ctx c = CTX.get(ctl);
                        if (c != null && !c.ccMode && !c.expanded) borrowActiveIfDynamic(c);
                    } catch (Throwable t) {
                        Logx.e("post-state failed", t);
                    }
                    return r;
                });
            }
            // 补充:setActiveStream(IZ)
            try {
                hookByTypes(vcClass, "setActiveStream",
                        new Class<?>[]{int.class, boolean.class}, "S2",
                        chain -> {
                            Object r = chain.proceed();
                            try {
                                Object ctl = chain.getThisObject();
                                Ctx c = CTX.get(ctl);
                                if (c != null && !c.ccMode && !c.expanded) {
                                    borrowActiveIfDynamic(c);
                                }
                            } catch (Throwable ignored) { }
                            return r;
                        });
            } catch (Throwable ignore) { }

            installedFlag = true;
            Logx.i("volume pager hooks installed (api102, per-controller, PER_PAGE="
                    + PER_PAGE + ")");
        } catch (Throwable t) {
            Logx.e("volume pager install failed", t);
        }
    }

    private static void hook1(Method m) throws Throwable {
        xp.hook(m).setId("H1").intercept(chain -> {
            Object r = chain.proceed();
            try {
                onInitPanelView(chain.getThisObject());
            } catch (Throwable t) {
                Logx.e("H1 failed", t);
            }
            return r;
        });
    }

    private static void hookByTypes(Class<?> d, String n, Class<?>[] t,
                                    String id, Hooker body) throws Throwable {
        xp.hook(d.getDeclaredMethod(n, t)).setId(id).intercept(body);
    }

    // ==================================================================
    // H2 / H3 / H4 / H5 实现
    // ==================================================================

    private static Object h2Intercept(Chain chain) throws Throwable {
        List<Object> a = chain.getArgs();
        Object ctl = chain.getThisObject();
        Ctx c = CTX.computeIfAbsent(ctl, Ctx::new);
        if (Boolean.TRUE.equals(a.get(1)) && Boolean.TRUE.equals(a.get(2))) {
            Integer sid = (Integer) a.get(0);
            PENDING_DYNAMIC.set(sid);
            Logx.i("H2 pending dynamic stream=" + sid);
        }
        Object r;
        try {
            r = chain.proceed();
        } finally {
            PENDING_DYNAMIC.remove();
        }
        return r;
    }

    private static Object h3Intercept(Chain chain) throws Throwable {
        Integer sid = PENDING_DYNAMIC.get();
        if (sid == null) return chain.proceed();
        PENDING_DYNAMIC.remove();

        Object cols = chain.getThisObject();
        Object ctl = COLS_OWNER.get(cols);
        Ctx c = ctl == null ? null : CTX.get(ctl);
        refreshCc(c);
        if (!ENABLED || c == null || c.ccMode) return chain.proceed();

        try {
            View child = (View) chain.getArgs().get(0);
            synchronized (DYNAMIC_VIEWS) { DYNAMIC_VIEWS.add(child); }
            if (c.borrowedView == child) c.borrowedView = null;
            ensurePagedUi(c);
            if (c.pageStrip != null) {
                c.pageStrip.addView(child);
                child.setTranslationX(0f);
                resolveDotColorsFrom(child);
                onDynamicSetChanged(c);
                Logx.i("dynamic column routed to strip (stream=" + sid + ")");
                return null; // 吞掉原生路由
            }
            Logx.w("strip unavailable, falls back to native");
        } catch (Throwable t) {
            Logx.e("H3 failed", t);
        }
        return chain.proceed();
    }

    private static Object h4Intercept(Chain chain) throws Throwable {
        View child = (View) chain.getArgs().get(0);
        synchronized (DYNAMIC_VIEWS) {
            if (!DYNAMIC_VIEWS.contains(child)) return chain.proceed();
        }
        try {
            ViewGroup p = (ViewGroup) child.getParent();
            if (p != null) p.removeView(child);
            Ctx c = ownerCtxOfChild(child);
            if (c != null && c.borrowedView == child) c.borrowedView = null;
            onDynamicSetChanged(c);
            return null;
        } catch (Throwable t) {
            Logx.e("H4 failed", t);
            return chain.proceed();
        }
    }

    private static Object h5Intercept(Chain chain) throws Throwable {
        Object cols = chain.getThisObject();
        Object ctl = COLS_OWNER.get(cols);
        Ctx c = ctl == null ? null : CTX.get(ctl);
        boolean expArg = Boolean.TRUE.equals(chain.getArgs().get(0));
        if (expArg && c != null) repatriateBorrowed(c);   // 先归还再让原生搬移
        Object r = chain.proceed();
        if (c != null) {
            c.expanded = expArg;
            if (!expArg) {
                collapseUi(c);
                borrowActiveIfDynamic(c);
            } else {
                refreshDots(c);
            }
        }
        return r;
    }

    // ==================================================================
    // H1 注入
    // ==================================================================

    private static void onInitPanelView(Object ctl) throws Exception {
        Ctx c = CTX.computeIfAbsent(ctl, Ctx::new);
        refreshCc(c);
        synchronized (DYNAMIC_VIEWS) {
            // 抢救旧胶片条中的动态列(跨重建)
        }
        c.borrowedView = null;
        c.page = 0;
        c.expanded = false;

        c.content = (ViewGroup) getField(ctl, "mVolumeContentView");
        c.dialogRoot = (LinearLayout) getField(ctl, "mVolumeView");
        c.page1 = (ViewGroup) getField(ctl, "mVolumeContentColumns");

        Object volCols = getField(ctl, "mVolumeColumns");
        if (volCols != null) {
            COLS_OWNER.put(volCols, ctl);
            c.collapsedBox =
                    (ViewGroup) getField(volCols, "mColumnsCollapsed");
        }

        if (c.content == null || c.dialogRoot == null) {
            Logx.w("initPanelView: null views, skip");
            return;
        }
        c.content.setClipChildren(false);

        Context ctx = c.content.getContext();

        // 抢救旧胶片条中的动态列
        List<View> salvage = new ArrayList<>();
        if (c.pageStrip != null) {
            for (int i = c.pageStrip.getChildCount() - 1; i >= 0; i--) {
                View v = c.pageStrip.getChildAt(i);
                synchronized (DYNAMIC_VIEWS) {
                    if (DYNAMIC_VIEWS.contains(v)) salvage.add(v);
                }
            }
            Collections.reverse(salvage);
        }
        if (c.pageViewport != null && c.pageViewport.getParent() instanceof ViewGroup) {
            ((ViewGroup) c.pageViewport.getParent()).removeView(c.pageViewport);
        }
        c.pageViewport = new FrameLayout(ctx);
        c.pageViewport.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        c.pageViewport.setClipChildren(true);
        c.pageViewport.setVisibility(View.GONE);

        c.pageStrip = new LinearLayout(ctx);
        c.pageStrip.setOrientation(LinearLayout.HORIZONTAL);
        c.pageStrip.setClipChildren(false);
        c.pageStrip.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        c.pageViewport.addView(c.pageStrip);
        for (View v : salvage) {
            c.pageStrip.addView(v);
            v.setTranslationX(0f);
        }
        c.content.addView(c.pageViewport);

        // 圆点行(按容器ID定位插入)
        if (c.dotsRow != null && c.dotsRow.getParent() instanceof ViewGroup) {
            ((ViewGroup) c.dotsRow.getParent()).removeView(c.dotsRow);
        }
        c.dotsRow = new LinearLayout(ctx);
        c.dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        c.dotsRow.setGravity(Gravity.CENTER);
        c.dotsRow.setVisibility(View.GONE);
        int insertIdx = -1;
        if (ID_container != 0) {
            for (int i = 0; i < c.dialogRoot.getChildCount(); i++) {
                View ch = c.dialogRoot.getChildAt(i);
                if (ch != null && ch.getId() == ID_container) { insertIdx = i + 1; break; }
            }
        }
        if (insertIdx < 0) insertIdx = Math.min(1, c.dialogRoot.getChildCount());
        c.dialogRoot.addView(c.dotsRow, insertIdx);

        refreshDots(c);
        Logx.i("paged ui injected");
    }

    // ==================================================================
    // 分页 / 圆点
    // ==================================================================

    private static int extraPageCount(Ctx c) {
        int n = countStripColumns(c);
        return n <= 0 ? 0 : (n + PER_PAGE - 1) / PER_PAGE;
    }

    private static int countStripColumns(Ctx c) {
        if (c == null || c.pageStrip == null) return 0;
        int n = 0;
        for (int i = 0; i < c.pageStrip.getChildCount(); i++) {
            View v = c.pageStrip.getChildAt(i);
            if (v == c.borrowedView) continue;
            if (isDynamic(v)) n++;
        }
        return n;
    }

    private static boolean isDynamic(View v) {
        synchronized (DYNAMIC_VIEWS) { return DYNAMIC_VIEWS.contains(v); }
    }

    private static void onDynamicSetChanged(Ctx c) {
        int last = extraPageCount(c);
        if (c.page > last) {
            if (c.expanded) { c.page = last; animateToPage(c, last); }
            else c.page = 0;
        }
        refreshDots(c);
    }

    private static float stripOffsetFor(int p, int w) {
        return (p <= 0) ? 0f : -(float) (p - 1) * w;
    }

    private static void animateToPage(Ctx c, int target) {
        target = Math.max(0, Math.min(target, extraPageCount(c)));
        if (target == c.page) return;
        int w = 1;
        if (c.content != null) w = Math.max(c.content.getWidth(),
                c.page1 == null ? 0 : c.page1.getWidth());
        float sFrom = stripOffsetFor(c.page, w);
        float sTo = stripOffsetFor(target, w);
        float p1To = (target >= 1) ? -w : 0f;
        if (c.pageViewport != null) c.pageViewport.setVisibility(View.VISIBLE);
        if (c.pageStrip != null) c.pageStrip.setTranslationX(sFrom);
        if (c.page1 != null) c.page1.animate().translationX(p1To).setDuration(260L).start();
        if (c.pageStrip != null) {
            c.pageStrip.animate().translationX(sTo).setDuration(260L).withEndAction(() -> {
                if (c.page == 0 && c.pageViewport != null)
                    c.pageViewport.setVisibility(View.GONE);
            }).start();
        }
        c.page = target;
        refreshDots(c);
    }

    private static void collapseUi(Ctx c) {
        c.page = 0;
        if (c.page1 != null) c.page1.setTranslationX(0f);
        if (c.pageStrip != null) c.pageStrip.setTranslationX(0f);
        if (c.pageViewport != null) c.pageViewport.setVisibility(View.GONE);
        if (c.dotsRow != null) c.dotsRow.setVisibility(View.GONE);
    }

    private static void refreshDots(Ctx c) {
        if (c == null || c.dotsRow == null) return;
        int pages = extraPageCount(c) + 1;
        boolean show = c.expanded && pages > 1 && !c.ccMode;
        if (c.dotsRow.getChildCount() != pages) rebuildDots(c, pages);
        c.dotsRow.setVisibility(show ? View.VISIBLE : View.GONE);
        applyDotTints(c);
    }

    private static void applyDotTints(Ctx c) {
        for (int i = 0; i < c.dotCircles.size(); i++) {
            View v = c.dotCircles.get(i);
            if (v == null || !(v.getBackground() instanceof GradientDrawable)) continue;
            ((GradientDrawable) v.getBackground())
                    .setColor(i == c.page ? colSelected : colUnselected);
        }
    }

    private static void rebuildDots(Ctx c, int pages) {
        if (c.dotsRow == null) return;
        Context ctx = c.dotsRow.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        c.dotsRow.removeAllViews();
        c.dotCircles.clear();
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
            cell.setOnClickListener(v -> animateToPage(c, idx));
            c.dotsRow.addView(cell);
            c.dotCircles.add(circle);
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
            }
        } catch (Throwable t) {
            dotColorsResolved = true;
        }
    }

    // ==================================================================
    // 借出 / 归还 / 收起
    // ==================================================================

    private static void repatriateBorrowed(Ctx c) {
        if (c.borrowedView == null) return;
        try {
            ViewGroup p = (ViewGroup) c.borrowedView.getParent();
            if (p != null && p != c.pageStrip) p.removeView(c.borrowedView);
            if (c.pageStrip != null && c.borrowedView.getParent() != c.pageStrip) {
                c.pageStrip.addView(c.borrowedView);
            }
        } catch (Throwable t) {
            Logx.e("repatriate failed", t);
        } finally {
            c.borrowedView = null;
        }
    }

    private static void borrowActiveIfDynamic(Ctx c) {
        if (c.borrowedView != null) return;
        refreshCc(c);
        if (c.ccMode) return;
        if (c.pageStrip == null || c.collapsedBox == null) return;
        if (countStripColumns(c) == 0) return;
        try {
            int active = getIntField(c.controller, "mActiveStream");
            Object col = callMethod(c.controller, "findColumn", active);
            if (col == null) return;
            View v = (View) getField(col, "view");
            if (!isDynamic(v)) return;
            ViewGroup p = (ViewGroup) v.getParent();
            if (p == c.collapsedBox) return;
            if (p != null && p != c.pageStrip) return;
            if (p == c.pageStrip) c.pageStrip.removeView(v);
            c.collapsedBox.addView(v);
            v.setVisibility(View.VISIBLE);
            c.borrowedView = v;
            Logx.i("active dynamic column borrowed (stream=" + active + ")");
        } catch (Throwable t) {
            Logx.e("borrow failed", t);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static void refreshCc(Ctx c) {
        try {
            c.ccMode = getBoolField(c.controller, "isControlCenterPanel");
        } catch (Throwable t) {
            c.ccMode = false;
        }
    }

    private static Ctx ownerCtxOfChild(View child) {
        for (Map.Entry<Object, Ctx> e : CTX.entrySet()) {
            Ctx c = e.getValue();
            if (c.pageStrip != null && c.pageStrip == child.getParent()) return c;
            if (c.borrowedView == child) return c;
        }
        return null;
    }

    private static void ensurePagedUi(Ctx c) {
        if (c.pageViewport != null && c.content != null
                && c.pageViewport.getParent() == c.content && c.pageStrip != null) {
            return;
        }
        try { onInitPanelView(c.controller); } catch (Throwable t) { Logx.e("re-inject", t); }
    }

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
                } catch (IllegalAccessException ignored) { }
            }
        }
        throw new NoSuchMethodException(name);
    }

    // ==================================================================
    // 诊断转储
    // ==================================================================

    @SuppressWarnings("unchecked")
    private static void dumpStreams(Object ctl) throws Exception {
        Ctx c = CTX.get(ctl);
        Object st = getField(ctl, "mState");
        if (st == null) return;
        SparseArray<Object> states = (SparseArray<Object>) getField(st, "states");
        StringBuilder sb = new StringBuilder("state dump:");
        for (int i = 0; i < states.size(); i++) {
            int k = states.keyAt(i);
            Object s = states.valueAt(i);
            sb.append(" [id=").append(k)
              .append(" dyn=").append(getBoolField(s, "dynamic"))
              .append(" lvl=").append(getIntField(s, "level"))
              .append(getBoolField(s, "muted") ? " mute" : "");
            try {
                String rl = (String) getField(s, "remoteLabel");
                if (rl != null && !rl.isEmpty()) sb.append(" lbl=\"").append(rl).append('"');
            } catch (Throwable ignored) { }
            sb.append(']');
        }
        Logx.i(sb + " | needDlg=" + getBoolField(ctl, "mNeedShowDialog")
                + " active=" + getIntField(ctl, "mActiveStream")
                + " cc=" + getBoolField(ctl, "isControlCenterPanel")
                + " expanded=" + (c != null && c.expanded));
    }
}
