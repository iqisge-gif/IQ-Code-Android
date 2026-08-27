package com.iqge;

import android.animation.LayoutTransition;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * One motion language for the native IQ Code workspace.
 *
 * The helpers deliberately animate only compositor-friendly properties (alpha,
 * translation and scale).  High-frequency surfaces such as TerminalView are
 * animated only when the pane is attached; terminal frames themselves are never
 * animated.  Weak maps prevent view bookkeeping from extending a view's lifetime.
 */
public final class UiMotion {
    private static final TimeInterpolator STANDARD = new PathInterpolator(.20f, 0f, 0f, 1f);
    private static final TimeInterpolator EMPHASIZED = new PathInterpolator(.16f, .84f, .22f, 1f);
    private static final Map<View, Boolean> BOUND = new WeakHashMap<>();
    private static final Map<View, Boolean> SELECTED = new WeakHashMap<>();
    private static final Map<View, Integer> LIST_POSITIONS = new WeakHashMap<>();
    private static volatile long motionStateCheckedAt;
    private static volatile boolean cachedAnimationsEnabled = true;
    private static volatile boolean cachedPowerSave;

    private UiMotion() {}

    public static boolean enabled(Context context) {
        refreshMotionState(context);
        return cachedAnimationsEnabled;
    }

    private static void refreshMotionState(Context context) {
        if (context == null) return;
        long now=android.os.SystemClock.uptimeMillis();
        if(now-motionStateCheckedAt<1_000L)return;
        synchronized(UiMotion.class){
            if(now-motionStateCheckedAt<1_000L)return;
            try {
                cachedAnimationsEnabled=(Build.VERSION.SDK_INT<26||ValueAnimator.areAnimatorsEnabled())
                    && Settings.Global.getFloat(context.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f;
                PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
                cachedPowerSave=power!=null&&power.isPowerSaveMode();
            } catch(Throwable ignored){cachedAnimationsEnabled=true;cachedPowerSave=false;}
            motionStateCheckedAt=now;
        }
    }

    private static long duration(View view, long normal) {
        Context context=view==null?null:view.getContext();
        refreshMotionState(context);
        if(!cachedAnimationsEnabled)return 0L;
        // Keep transitions visible but short enough that frequent workspace updates do
        // not queue behind one another or make the UI feel sluggish.
        long tuned = Math.max(70L, (long)(normal * .78f));
        return cachedPowerSave ? Math.max(70L, (long)(tuned * .72f)) : tuned;
    }

    private static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }

    private static void identity(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    public static void appIn(View view) {
        if (view == null) return;
        long duration = duration(view, 260);
        if (duration == 0L) { identity(view); return; }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setScaleX(.994f);
        view.setScaleY(.994f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(STANDARD).setDuration(duration).withLayer().start();
    }

    /** Slightly brighter cross-fade used when the whole runtime palette changes. */
    public static void themeChanged(View view) {
        if (view == null) return;
        long duration = duration(view, 330);
        if (duration == 0L) { identity(view); return; }
        view.animate().cancel();
        view.setAlpha(.38f);
        view.setScaleX(.988f);
        view.setScaleY(.988f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(EMPHASIZED).setDuration(duration).withLayer().start();
    }

    public static void pageIn(View view) {
        if (view == null) return;
        long duration = duration(view, 150);
        if (duration == 0L) { identity(view); return; }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(view, 3f));
        view.animate().alpha(1f).translationY(0f)
                .setInterpolator(STANDARD).setDuration(duration).start();
    }

    public static void messageIn(View view, boolean fromUser) {
        if (view == null) return;
        long duration = duration(view, 155);
        if (duration == 0L) { identity(view); return; }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(view, 4f));
        view.animate().alpha(1f).translationY(0f)
                .setInterpolator(STANDARD).setDuration(duration).start();
    }

    public static void toolIn(View view) {
        if (view == null) return;
        long duration = duration(view, 240);
        if (duration == 0L) { identity(view); return; }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationX(dp(view, -9f));
        view.setTranslationY(dp(view, 4f));
        view.animate().alpha(1f).translationX(0f).translationY(0f)
                .setInterpolator(STANDARD).setDuration(duration).withLayer().start();
    }

    public static void contentUpdated(View view, boolean emphasized) {
        if (view == null) return;
        long duration = duration(view, emphasized ? 210 : 120);
        if (duration == 0L) { identity(view); return; }
        view.animate().cancel();
        view.setAlpha(emphasized ? .76f : .93f);
        view.setTranslationY(dp(view, emphasized ? 2f : 1f));
        view.animate().alpha(1f).translationY(0f)
                .setInterpolator(STANDARD).setDuration(duration).start();
    }

    public static void state(View view, float alpha, float scale, long normalDuration) {
        if (view == null) return;
        long duration = duration(view, normalDuration);
        view.animate().cancel();
        if (duration == 0L) { view.setAlpha(alpha); view.setScaleX(scale); view.setScaleY(scale); return; }
        view.animate().alpha(alpha).scaleX(scale).scaleY(scale)
                .setInterpolator(EMPHASIZED).setDuration(duration).start();
    }

    public static void breathe(View view, float alpha, long normalDuration) {
        if (view == null) return;
        long duration = duration(view, normalDuration);
        if (duration == 0L) { view.setAlpha(1f); return; }
        view.animate().cancel();
        view.animate().alpha(alpha).setInterpolator(STANDARD).setDuration(duration).start();
    }

    public static void fadeOut(View view, float downDp, long normalDuration, Runnable end) {
        if (view == null) { if (end != null) end.run(); return; }
        long duration = duration(view, normalDuration);
        view.animate().cancel();
        if (duration == 0L) { view.setAlpha(0f); if (end != null) end.run(); return; }
        view.animate().alpha(0f).translationY(dp(view, downDp)).scaleX(.99f).scaleY(.99f)
                .setInterpolator(STANDARD).setDuration(duration).withLayer().withEndAction(end).start();
    }

    public static void dialogIn(View view) {
        if (view == null) return;
        long duration = duration(view, 270);
        if (duration == 0L) { identity(view); return; }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setScaleX(.955f);
        view.setScaleY(.955f);
        view.setTranslationY(dp(view, 14f));
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setInterpolator(EMPHASIZED).setDuration(duration).withLayer().start();
    }

    public static void drawerIn(View scrim, View drawer, float widthDp) {
        if (scrim == null || drawer == null) return;
        long duration = duration(drawer, 260);
        scrim.animate().cancel();
        drawer.animate().cancel();
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        if (duration == 0L) { scrim.setAlpha(1f); identity(drawer); return; }
        scrim.setAlpha(0f);
        drawer.setAlpha(.82f);
        drawer.setTranslationX(dp(drawer, -Math.abs(widthDp)));
        drawer.animate().alpha(1f).translationX(0f).setInterpolator(EMPHASIZED)
                .setDuration(duration).withLayer().start();
        scrim.animate().alpha(1f).setInterpolator(STANDARD).setDuration(duration - 35L).start();
    }

    public static void drawerOut(View scrim, View drawer, float widthDp, Runnable end) {
        if (scrim == null || drawer == null) { if (end != null) end.run(); return; }
        long duration = duration(drawer, 205);
        scrim.animate().cancel();
        drawer.animate().cancel();
        if (duration == 0L) {
            scrim.setVisibility(View.GONE);
            drawer.setVisibility(View.GONE);
            if (end != null) end.run();
            return;
        }
        scrim.animate().alpha(0f).setInterpolator(STANDARD).setDuration(duration - 25L).start();
        drawer.animate().alpha(.88f).translationX(dp(drawer, -Math.abs(widthDp)))
                .setInterpolator(STANDARD).setDuration(duration).withLayer().withEndAction(() -> {
                    scrim.setVisibility(View.GONE);
                    drawer.setVisibility(View.GONE);
                    drawer.setAlpha(1f);
                    if (end != null) end.run();
                }).start();
    }

    public static void selection(View view, boolean active) {
        if (view == null) return;
        Boolean previous = SELECTED.put(view, active);
        if (previous != null && previous == active) return;
        long duration = duration(view, 140);
        view.animate().cancel();
        view.setScaleX(1f);view.setScaleY(1f);
        float targetAlpha=active?1f:.78f;
        if(duration==0L){view.setAlpha(targetAlpha);return;}
        view.animate().alpha(targetAlpha).setInterpolator(STANDARD).setDuration(duration).start();
    }

    public static void staggerChildren(ViewGroup parent, int maxChildren) {
        if (parent == null) return;
        int count = parent.getChildCount();
        int start = Math.max(0, count - Math.max(1, maxChildren));
        if (!enabled(parent.getContext())) {
            for (int i = start; i < count; i++) identity(parent.getChildAt(i));
            return;
        }
        for (int i = start; i < count; i++) {
            View child = parent.getChildAt(i);
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(dp(child, 7f));
            child.setScaleX(.992f);
            child.setScaleY(.992f);
            long delay = Math.min(150L, (long)(i - start) * 22L);
            child.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(delay).setDuration(duration(child, 220))
                    .setInterpolator(STANDARD).withLayer().start();
        }
    }

    public static void listItemIn(View view, int position) {
        if (view == null) return;
        Integer previous = LIST_POSITIONS.put(view, position);
        if (previous != null && previous == position) return;
        long duration = duration(view, 190);
        if (duration == 0L) { identity(view); return; }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationX(dp(view, -7f));
        view.animate().alpha(1f).translationX(0f).setStartDelay(Math.min(90L, position * 12L))
                .setInterpolator(STANDARD).setDuration(duration).start();
    }

    public static void setTextCrossfade(TextView view, CharSequence value) {
        if (view == null) return;
        CharSequence next = value == null ? "" : value;
        if (String.valueOf(view.getText()).contentEquals(next)) return;
        long duration = duration(view, 170);
        if (duration == 0L) { view.setText(next); return; }
        view.animate().cancel();
        view.animate().alpha(.18f).translationY(dp(view, -2f)).setDuration(duration / 2L)
                .setInterpolator(STANDARD).withEndAction(() -> {
                    view.setText(next);
                    view.setTranslationY(dp(view, 2f));
                    view.animate().alpha(1f).translationY(0f).setDuration(duration / 2L + 20L)
                            .setInterpolator(STANDARD).start();
                }).start();
    }

    /** Adds uniform press feedback without replacing click listeners. */
    public static void bindInteractive(View root) {
        if (root == null) return;
        if (root.hasOnClickListeners() && !BOUND.containsKey(root)) {
            BOUND.put(root, Boolean.TRUE);
            root.setOnTouchListener((view, event) -> {
                if (!view.isEnabled()) return false;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    long d = duration(view, 65);
                    if (d > 0L) view.animate().cancel();
                    if (d > 0L) view.animate().scaleX(.985f).scaleY(.985f).alpha(.86f)
                            .setInterpolator(STANDARD).setDuration(d).start();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    long d = duration(view, 120);
                    if (d == 0L) identity(view);
                    else view.animate().cancel();
                    if (d > 0L) view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setInterpolator(EMPHASIZED).setDuration(d).start();
                }
                return false;
            });
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) bindInteractive(group.getChildAt(i));
        }
    }

    /** Low-frequency add/remove transitions; CHANGE_* stays disabled to avoid layout thrash. */
    public static void enableLayoutChanges(ViewGroup group) {
        if (group == null || !enabled(group.getContext())) return;
        LayoutTransition transition = new LayoutTransition();
        transition.disableTransitionType(LayoutTransition.CHANGING);
        transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        transition.setDuration(LayoutTransition.APPEARING, 145L);
        transition.setDuration(LayoutTransition.DISAPPEARING, 105L);
        transition.setStartDelay(LayoutTransition.APPEARING, 0L);
        transition.setStartDelay(LayoutTransition.DISAPPEARING, 0L);
        transition.setInterpolator(LayoutTransition.APPEARING, STANDARD);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, STANDARD);
        group.setLayoutTransition(transition);
    }
}
