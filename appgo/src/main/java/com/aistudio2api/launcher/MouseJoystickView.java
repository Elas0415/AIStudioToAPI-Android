package com.aistudio2api.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Small semi-transparent virtual joystick for the login window.
 *
 * <p>It only produces relative mouse movement; the actual RFB pointer event is
 * sent by login.html ({@code window.__a2apiMove}). The view is a sibling of the
 * noVNC WebView, so touches outside its circle still reach the page and normal
 * browser interaction is unchanged.
 */
public class MouseJoystickView extends View {

    public interface Listener {
        /** dx/dy are in [-1, 1]; called continuously while the stick is held. */
        void onMove(float dx, float dy);
    }

    private static final int TICK_MS = 40;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float centerX, centerY;
    private float baseRadius, knobRadius;
    private float knobX, knobY;
    private float moveX, moveY;

    private boolean active;
    private int pointerId = -1;
    private Listener listener;

    public MouseJoystickView(Context context) {
        super(context);
        init();
    }

    public MouseJoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MouseJoystickView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Gray, translucent: visible over the browser without hiding it.
        basePaint.setColor(0x55808080);
        basePaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(0x99B0B0B0);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        knobPaint.setColor(0xCC9E9E9E);
        knobPaint.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) * 0.44f;
        knobRadius = baseRadius * 0.38f;
        cancel();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint);
        canvas.drawCircle(centerX + knobX, centerY + knobY, knobRadius, knobPaint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointerId = event.getPointerId(0);
                active = true;
                updateStick(event, 0);
                removeCallbacks(tick);
                post(tick);
                return true;
            case MotionEvent.ACTION_MOVE: {
                int index = event.findPointerIndex(pointerId);
                if (index >= 0) {
                    updateStick(event, index);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancel();
                return true;
            default:
                return active;
        }
    }

    private void updateStick(MotionEvent event, int index) {
        float dx = event.getX(index) - centerX;
        float dy = event.getY(index) - centerY;
        float length = (float) Math.hypot(dx, dy);
        if (length > baseRadius) {
            dx = dx * baseRadius / length;
            dy = dy * baseRadius / length;
        }
        knobX = dx;
        knobY = dy;
        moveX = baseRadius <= 0f ? 0f : dx / baseRadius;
        moveY = baseRadius <= 0f ? 0f : dy / baseRadius;
        invalidate();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active) {
                return;
            }
            if (listener != null) {
                listener.onMove(moveX, moveY);
            }
            postDelayed(this, TICK_MS);
        }
    };

    /** Stops the repeat timer and re-centres the knob. */
    public void cancel() {
        active = false;
        pointerId = -1;
        removeCallbacks(tick);
        knobX = 0f;
        knobY = 0f;
        moveX = 0f;
        moveY = 0f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }
}
