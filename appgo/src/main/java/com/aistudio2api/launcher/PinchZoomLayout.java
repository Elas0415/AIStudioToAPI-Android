package com.aistudio2api.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Aspect-fit container for the noVNC view with a two-finger zoom/pan gesture.
 *
 * <p>Why this exists: noVNC calls {@code preventDefault()} on every touch event,
 * so the WebView's own pinch-zoom can never start. Multi-touch is therefore
 * handled here and kept away from the WebView — single-finger input still
 * reaches noVNC as mouse input, and because Android inverse-maps the touch
 * coordinates it forwards to the child, noVNC's pointer maths stays correct.
 *
 * <p>Zooming resizes the child view instead of applying a scale transform: the
 * WebView re-lays out, noVNC's ResizeObserver re-fits the remote framebuffer to
 * the (still aspect-correct) container, and Chromium rasterises at the final
 * size — so magnified text stays sharp instead of turning into a stretched
 * bitmap.
 *
 * <p>The child is kept at the remote screen's aspect ratio, which means noVNC's
 * autoscale needs no letterbox and always fills the child exactly.
 */
public class PinchZoomLayout extends FrameLayout {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 3f;

    /** Remote desktop size, used to keep the child aspect-correct. */
    private int remoteW = 1280;
    private int remoteH = 800;

    private float scale = MIN_SCALE;
    /** Extra offset on top of the centred (home) position of the child. */
    private float panX, panY;
    /** Child size at scale 1: the largest remote-aspect rect that fits. */
    private float baseW, baseH;

    private boolean pinching;
    private float startScale, startPanX, startPanY;
    private float startMidX, startMidY, startSpan;

    public PinchZoomLayout(Context context) {
        super(context);
    }

    public PinchZoomLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PinchZoomLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setRemoteSize(int width, int height) {
        if (width > 0 && height > 0) {
            remoteW = width;
            remoteH = height;
        }
        computeBase();
        reset();
    }

    /** Back to "fit the whole desktop", which is also what a fresh login shows. */
    public void reset() {
        pinching = false;
        scale = MIN_SCALE;
        panX = 0f;
        panY = 0f;
        apply();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeBase();
        reset();
    }

    /**
     * Last line of defence against a stale geometry: whatever path changed the
     * container's bounds (rotation, keyboard, overlay re-show), the child is
     * re-fitted to the size it really has now.
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) {
            apply();
        }
    }

    private void computeBase() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            baseW = baseH = 0f;
            return;
        }
        float aspect = (float) remoteW / (float) remoteH;
        float bw = w;
        float bh = w / aspect;
        if (bh > h) {
            bh = h;
            bw = h * aspect;
        }
        baseW = bw;
        baseH = bh;
    }

    /** Top-left of the child when centred at the given scale (before pan). */
    private float homeX(float s) {
        return (getWidth() - baseW * s) / 2f;
    }

    private float homeY(float s) {
        return (getHeight() - baseH * s) / 2f;
    }

    private void apply() {
        View child = getChildAt(0);
        if (child == null) {
            return;
        }
        // Recompute from the container's *current* size before using it. A
        // rotation or the soft keyboard (adjustResize) can resize this view
        // between two apply() calls; a base computed for the old height would
        // leave the child taller than the container and the bottom of the
        // remote desktop would fall off-screen.
        computeBase();
        if (baseW <= 0f) {
            return;
        }
        int w = Math.max(1, Math.round(baseW * scale));
        int h = Math.max(1, Math.round(baseH * scale));
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp == null) {
            lp = new LayoutParams(w, h);
        }
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            child.setLayoutParams(lp);
        }
        child.setTranslationX(homeX(scale) + panX);
        child.setTranslationY(homeY(scale) + panY);
    }

    /** Keeps the (possibly enlarged) child covering the container. */
    private void clampPan() {
        float w = baseW * scale;
        float h = baseH * scale;
        float lx = (getWidth() - w) / 2f;
        float ly = (getHeight() - h) / 2f;
        // lx <= 0 once the child is at least as big as the container; the
        // allowed pan range is then [lx, -lx].
        panX = w >= getWidth() ? Math.min(-lx, Math.max(lx, panX)) : 0f;
        panY = h >= getHeight() ? Math.min(-ly, Math.max(ly, panY)) : 0f;
    }

    // ------------------------------------------------------------------
    // Two-finger gesture
    // ------------------------------------------------------------------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            begin(ev);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                begin(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching) {
                    update(ev);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerCount() <= 2) {
                    pinching = false;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pinching = false;
                return true;
            default:
                return pinching;
        }
    }

    private void begin(MotionEvent ev) {
        if (pinching || ev.getPointerCount() < 2 || baseW <= 0f) {
            return;
        }
        pinching = true;
        startScale = scale;
        startPanX = panX;
        startPanY = panY;
        startMidX = (ev.getX(0) + ev.getX(1)) / 2f;
        startMidY = (ev.getY(0) + ev.getY(1)) / 2f;
        startSpan = span(ev);
    }

    private void update(MotionEvent ev) {
        if (ev.getPointerCount() < 2 || baseW <= 0f) {
            return;
        }
        float raw = startSpan > 1f ? startScale * (span(ev) / startSpan) : startScale;
        float s1 = Math.min(MAX_SCALE, Math.max(MIN_SCALE, raw));
        float midX = (ev.getX(0) + ev.getX(1)) / 2f;
        float midY = (ev.getY(0) + ev.getY(1)) / 2f;

        // Keep the remote pixel that was under the initial focal point glued to
        // the current focal point: pan = f1 - home(s1) - v * k * s1, where v is
        // that pixel's position in remote coordinates.
        float k = baseW / (float) remoteW;
        float vx = (startMidX - homeX(startScale) - startPanX) / (k * startScale);
        float vy = (startMidY - homeY(startScale) - startPanY) / (k * startScale);

        scale = s1;
        panX = midX - homeX(s1) - vx * k * s1;
        panY = midY - homeY(s1) - vy * k * s1;
        clampPan();
        apply();
    }

    private static float span(MotionEvent ev) {
        float dx = ev.getX(0) - ev.getX(1);
        float dy = ev.getY(0) - ev.getY(1);
        return (float) Math.hypot(dx, dy);
    }
}
