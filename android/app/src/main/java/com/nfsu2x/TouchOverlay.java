package com.nfsu2x;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Multi-touch Xbox layout. Each finger owns one control, so throttle, brake,
 * steering and camera buttons can be used at the same time. The native side
 * receives one complete state per event; there is no polling or JNI callback
 * on the render thread.
 */
final class TouchOverlay extends View {
    private static final int NONE = 0;
    private static final int STICK = 1;
    private static final int DPAD_UP = 2;
    private static final int DPAD_DOWN = 3;
    private static final int DPAD_LEFT = 4;
    private static final int DPAD_RIGHT = 5;
    private static final int START = 6;
    private static final int BACK = 7;
    private static final int A = 8;
    private static final int B = 9;
    private static final int X = 10;
    private static final int Y = 11;
    private static final int WHITE = 12;
    private static final int BLACK = 13;
    private static final int LT = 14;
    private static final int RT = 15;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final HashMap<Integer, Integer> fingers = new HashMap<>();
    private final boolean[] analog = new boolean[8];
    private int digital;
    private float stickX;
    private float stickY;
    private float stickRadius;
    private float stickBaseX;
    private float stickBaseY;

    TouchOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setFocusable(false);
        paint.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setTextSize(15 * getResources().getDisplayMetrics().density);
        // Keep this view on the app's hardware layer; the overlay is drawn
        // once per display frame and must not force the game into a software
        // composition path.
    }

    private float unit() {
        return Math.min(getWidth(), getHeight());
    }

    private void geometry() {
        float u = unit();
        stickBaseX = getWidth() * 0.16f;
        stickBaseY = getHeight() * 0.72f;
        stickRadius = u * 0.105f;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        geometry();
        float u = unit();
        int idle = Color.argb(74, 245, 247, 250);
        int held = Color.argb(155, 240, 68, 78);
        int outline = Color.argb(120, 245, 247, 250);

        // A subtle base prevents controls disappearing over bright race scenes.
        paint.setColor(Color.argb(18, 0, 0, 0));
        c.drawRect(0, 0, getWidth(), getHeight(), paint);

        paint.setColor(Color.argb(82, 245, 247, 250));
        c.drawCircle(stickBaseX, stickBaseY, stickRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2, u * .004f));
        paint.setColor(outline);
        c.drawCircle(stickBaseX, stickBaseY, stickRadius, paint);
        paint.setStyle(Paint.Style.FILL);
        float knobX = stickBaseX + stickX * stickRadius * .68f;
        float knobY = stickBaseY - stickY * stickRadius * .68f;
        paint.setColor(isHeld(STICK) ? held : idle);
        c.drawCircle(knobX, knobY, stickRadius * .43f, paint);

        float d = u * .065f;
        float dcx = getWidth() * .095f, dcy = getHeight() * .46f;
        circle(c, dcx, dcy - d, d * .72f, isHeld(DPAD_UP) ? held : idle, "");
        circle(c, dcx, dcy + d, d * .72f, isHeld(DPAD_DOWN) ? held : idle, "");
        circle(c, dcx - d, dcy, d * .72f, isHeld(DPAD_LEFT) ? held : idle, "");
        circle(c, dcx + d, dcy, d * .72f, isHeld(DPAD_RIGHT) ? held : idle, "");

        float bx = getWidth() * .84f, by = getHeight() * .63f, r = u * .047f;
        circle(c, bx + r * 1.28f, by, r, analog[0] ? held : idle, "A");
        circle(c, bx, by + r * 1.28f, r, analog[1] ? held : idle, "B");
        circle(c, bx - r * 1.28f, by, r, analog[2] ? held : idle, "X");
        circle(c, bx, by - r * 1.28f, r, analog[3] ? held : idle, "Y");

        pill(c, getWidth() * .19f, getHeight() * .17f, u * .085f, u * .028f,
                isHeld(WHITE) ? held : idle, "L");
        pill(c, getWidth() * .81f, getHeight() * .17f, u * .085f, u * .028f,
                isHeld(BLACK) ? held : idle, "R");
        pill(c, getWidth() * .19f, getHeight() * .27f, u * .085f, u * .025f,
                analog[6] ? held : idle, "LT");
        pill(c, getWidth() * .81f, getHeight() * .27f, u * .085f, u * .025f,
                analog[7] ? held : idle, "RT");

        circle(c, getWidth() * .42f, getHeight() * .14f, u * .028f,
                isHeld(BACK) ? held : idle, "−");
        circle(c, getWidth() * .58f, getHeight() * .14f, u * .028f,
                isHeld(START) ? held : idle, "+");
    }

    private void circle(Canvas c, float x, float y, float radius, int color, String caption) {
        paint.setColor(color);
        c.drawCircle(x, y, radius, paint);
        if (!caption.isEmpty()) {
            text.setColor(Color.argb(220, 255, 255, 255));
            text.setTextSize(Math.max(11, radius * .62f));
            c.drawText(caption, x, y - (text.ascent() + text.descent()) / 2, text);
        }
    }

    private void pill(Canvas c, float cx, float cy, float width, float height,
                      int color, String caption) {
        paint.setColor(color);
        c.drawRoundRect(new RectF(cx - width, cy - height, cx + width, cy + height),
                height, height, paint);
        text.setColor(Color.argb(220, 255, 255, 255));
        text.setTextSize(Math.max(10, height * 1.0f));
        c.drawText(caption, cx, cy - (text.ascent() + text.descent()) / 2, text);
    }

    private boolean isHeld(int control) {
        for (Integer value : fingers.values())
            if (value == control)
                return true;
        return false;
    }

    private int controlAt(float x, float y) {
        float u = unit();
        float dx = x - stickBaseX, dy = y - stickBaseY;
        if (dx * dx + dy * dy < stickRadius * stickRadius * 1.55f)
            return STICK;
        float d = u * .065f;
        float dcx = getWidth() * .095f, dcy = getHeight() * .46f;
        if (distance(x, y, dcx, dcy - d) < d * .78f) return DPAD_UP;
        if (distance(x, y, dcx, dcy + d) < d * .78f) return DPAD_DOWN;
        if (distance(x, y, dcx - d, dcy) < d * .78f) return DPAD_LEFT;
        if (distance(x, y, dcx + d, dcy) < d * .78f) return DPAD_RIGHT;

        float bx = getWidth() * .84f, by = getHeight() * .63f, r = u * .047f;
        if (distance(x, y, bx + r * 1.28f, by) < r * 1.15f) return A;
        if (distance(x, y, bx, by + r * 1.28f) < r * 1.15f) return B;
        if (distance(x, y, bx - r * 1.28f, by) < r * 1.15f) return X;
        if (distance(x, y, bx, by - r * 1.28f) < r * 1.15f) return Y;

        if (insidePill(x, y, getWidth() * .19f, getHeight() * .17f, u * .085f, u * .035f)) return WHITE;
        if (insidePill(x, y, getWidth() * .81f, getHeight() * .17f, u * .085f, u * .035f)) return BLACK;
        if (insidePill(x, y, getWidth() * .19f, getHeight() * .27f, u * .085f, u * .032f)) return LT;
        if (insidePill(x, y, getWidth() * .81f, getHeight() * .27f, u * .085f, u * .032f)) return RT;
        if (distance(x, y, getWidth() * .42f, getHeight() * .14f) < u * .038f) return BACK;
        if (distance(x, y, getWidth() * .58f, getHeight() * .14f) < u * .038f) return START;
        return NONE;
    }

    private static float distance(float x, float y, float cx, float cy) {
        float dx = x - cx, dy = y - cy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean insidePill(float x, float y, float cx, float cy,
                                      float halfW, float halfH) {
        return x >= cx - halfW && x <= cx + halfW && y >= cy - halfH && y <= cy + halfH;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        geometry();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            fingers.clear();
            clearState();
            sendState();
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            int id = event.getPointerId(index);
            int control = controlAt(event.getX(index), event.getY(index));
            if (control != NONE && !fingers.containsValue(control)) {
                fingers.put(id, control);
                applyPressed(control, event.getX(index), event.getY(index));
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (Map.Entry<Integer, Integer> entry : fingers.entrySet()) {
                if (entry.getValue() == STICK) {
                    int index = event.findPointerIndex(entry.getKey());
                    if (index >= 0)
                        updateStick(event.getX(index), event.getY(index));
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int index = event.getActionIndex();
            int id = event.getPointerId(index);
            Integer control = fingers.remove(id);
            if (control != null)
                releaseControl(control);
        }
        sendState();
        invalidate();
        return true;
    }

    private void applyPressed(int control, float x, float y) {
        switch (control) {
            case STICK: updateStick(x, y); break;
            case DPAD_UP: digital |= 0x0001; break;
            case DPAD_DOWN: digital |= 0x0002; break;
            case DPAD_LEFT: digital |= 0x0004; break;
            case DPAD_RIGHT: digital |= 0x0008; break;
            case START: digital |= 0x0010; break;
            case BACK: digital |= 0x0020; break;
            case A: analog[0] = true; break;
            case B: analog[1] = true; break;
            case X: analog[2] = true; break;
            case Y: analog[3] = true; break;
            case BLACK: analog[4] = true; break;
            case WHITE: analog[5] = true; break;
            case LT: analog[6] = true; break;
            case RT: analog[7] = true; break;
            default: break;
        }
    }

    private void releaseControl(int control) {
        switch (control) {
            case STICK: stickX = stickY = 0; break;
            case DPAD_UP: digital &= ~0x0001; break;
            case DPAD_DOWN: digital &= ~0x0002; break;
            case DPAD_LEFT: digital &= ~0x0004; break;
            case DPAD_RIGHT: digital &= ~0x0008; break;
            case START: digital &= ~0x0010; break;
            case BACK: digital &= ~0x0020; break;
            case A: analog[0] = false; break;
            case B: analog[1] = false; break;
            case X: analog[2] = false; break;
            case Y: analog[3] = false; break;
            case BLACK: analog[4] = false; break;
            case WHITE: analog[5] = false; break;
            case LT: analog[6] = false; break;
            case RT: analog[7] = false; break;
            default: break;
        }
    }

    private void clearState() {
        digital = 0;
        for (int i = 0; i < analog.length; i++) analog[i] = false;
        stickX = stickY = 0;
    }

    private void updateStick(float x, float y) {
        float dx = x - stickBaseX, dy = stickBaseY - y;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length > stickRadius) {
            dx *= stickRadius / length;
            dy *= stickRadius / length;
        }
        stickX = dx / stickRadius;
        stickY = dy / stickRadius;
    }

    private void sendState() {
        byte[] values = new byte[8];
        for (int i = 0; i < values.length; i++)
            values[i] = (byte) (analog[i] ? 255 : 0);
        GameActivity.nativeTouchState(digital, values,
                Math.round(stickX * 32767), Math.round(stickY * 32767), 0, 0);
    }
}
