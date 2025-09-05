package com.termux.x11.input;

import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OnePlus Pad 2 Pro Keyboard 触控板手势检测器
 * 采用状态机 + 事件驱动架构重构
 */
public class OnePlusTouchpadDetector {

    private static final String TAG = "OnePlusTouchpadDetector";
    private static final boolean DEBUG = false;

    // 手势监听器接口
    public interface OnTouchpadGestureListener {
        void onMouseMove(float x, float y, boolean isDrag);

        void onMouseClick(MouseButton button);

        void onMousePress(MouseButton button);

        void onMouseRelease(MouseButton button);

        void onScrollWheel(float distanceX, float distanceY);

        void onScrollWheelWithShift(float distanceX, float distanceY);

        void onScrollWheelWithCtrl(float distanceX, float distanceY);
    }

    // 鼠标按钮枚举
    public enum MouseButton {
        LEFT, RIGHT, MIDDLE
    }

    // 手势状态枚举
    public enum TouchpadGestureState {
        IDLE, // 空闲状态
        HOVER_TRACKING, // 悬停跟踪（单指移动鼠标光标）
        SINGLE_TAP_PENDING, // 单指点击等待确认
        DOUBLE_TAP_WINDOW, // 双击窗口期
        DRAG_ACTIVE, // 拖拽激活状态
        TWO_FINGER_TAP_PENDING, // 双指点击等待确认
        TWO_FINGER_SCROLL, // 双指滚动状态
        PINCH_ZOOM, // 双指缩放状态
        PHYSICAL_CLICK // 物理按压状态
    }

    // 手势类型枚举
    public enum TouchpadGestureType {
        UNKNOWN,
        SINGLE_FINGER, // 单指操作（通过 Flags=0x0 识别）
        TWO_FINGER, // 双指操作（通过 Flags=0x40 识别）
        PHYSICAL_PRESS, // 物理按压（通过 Buttons=0x1 识别）
        PINCH_ZOOM // 缩放手势（通过指针数=2 识别）
    }

    // 滚动方向枚举
    public enum ScrollDirection {
        UP, DOWN, LEFT, RIGHT
    }

    // 手势上下文数据结构
    public static class TouchpadGestureContext {
        // 基础信息
        public TouchpadGestureState currentState = TouchpadGestureState.IDLE;
        public TouchpadGestureType gestureType = TouchpadGestureType.UNKNOWN;
        public long gestureStartTime = 0;
        public boolean isActive = false;

        // 位置跟踪
        public final PointF initialPosition = new PointF();

        public final PointF currentPosition = new PointF();

        // 双击检测
        public long lastTapTime = 0;
        public boolean isInDoubleTapWindow = false;
        public Runnable doubleTapTimeoutRunnable = null;

        // 滚动相关
        public final PointF scrollStartPosition = new PointF();
        public float scrollAccumulationX = 0;
        public float scrollAccumulationY = 0;
        public boolean hasScrollStarted = false;

        // 缩放相关
        public float initialPinchDistance = 0;
        public float lastPinchDistance = 0;
        public boolean hasPinchStarted = false;

        // 事件标记
        public boolean expectCancel = false; // 是否期待CANCEL事件
        public boolean pendingTwoFingerTap = false; // 双指点击待确认
        public Runnable twoFingerTapTimeoutRunnable = null;

        // 调试信息
        public String lastEventDescription = "";
        public final List<String> eventHistory = new ArrayList<>();

        public void reset() {
            currentState = TouchpadGestureState.IDLE;
            gestureType = TouchpadGestureType.UNKNOWN;
            gestureStartTime = 0;
            isActive = false;

            scrollAccumulationX = 0;
            scrollAccumulationY = 0;
            hasScrollStarted = false;

            initialPinchDistance = 0;
            lastPinchDistance = 0;
            hasPinchStarted = false;

            expectCancel = false;
            pendingTwoFingerTap = false;

            lastEventDescription = "";
            eventHistory.clear();
        }
    }

    // 事件分类器
    public static class TouchpadEventClassifier {
        /**
         * 根据 MotionEvent 确定手势类型
         */
        public TouchpadGestureType classifyGesture(MotionEvent event) {
            // 物理按压优先级最高
            if (hasPhysicalPress(event)) {
                return TouchpadGestureType.PHYSICAL_PRESS;
            }

            // 缩放手势（双指指针）
            if (event.getPointerCount() >= 2) {
                return TouchpadGestureType.PINCH_ZOOM;
            }

            // 双指操作（Flags标记）
            if (isTwoFingerGesture(event)) {
                return TouchpadGestureType.TWO_FINGER;
            }

            // 单指操作
            return TouchpadGestureType.SINGLE_FINGER;
        }

        private boolean hasPhysicalPress(MotionEvent event) {
            return (event.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0;
        }

        private boolean isTwoFingerGesture(MotionEvent event) {
            return (event.getFlags() & 0x40) != 0;
        }
    }

    // 手势检测工具类
    public static class TouchpadGestureUtils {
        public static boolean isMovementSignificant(PointF start, PointF end, int touchSlop) {
            float distance = calculateDistance(start.x, start.y, end.x, end.y);
            return distance > touchSlop;
        }

        public static ScrollDirection determineScrollDirection(float deltaX, float deltaY) {
            if (Math.abs(deltaX) > Math.abs(deltaY)) {
                return deltaX > 0 ? ScrollDirection.RIGHT : ScrollDirection.LEFT;
            } else {
                return deltaY > 0 ? ScrollDirection.DOWN : ScrollDirection.UP;
            }
        }

        public static float calculateDistance(float x1, float y1, float x2, float y2) {
            return (float) Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
        }

        public static float calculateTwoPointerDistance(MotionEvent event) {
            if (event.getPointerCount() < 2)
                return 0;

            float x1 = event.getX(0);
            float y1 = event.getY(0);
            float x2 = event.getX(1);
            float y2 = event.getY(1);

            return calculateDistance(x1, y1, x2, y2);
        }
    }

    // 状态处理器基类
    public abstract static class TouchpadStateHandler {
        protected OnePlusTouchpadDetector detector;
        protected OnTouchpadGestureListener listener;

        public TouchpadStateHandler(OnePlusTouchpadDetector detector, OnTouchpadGestureListener listener) {
            this.detector = detector;
            this.listener = listener;
        }

        public abstract boolean handleEvent(MotionEvent event, TouchpadGestureContext context);

        protected void logEvent(String action, MotionEvent event, TouchpadGestureContext context) {
            if (DEBUG) {
                String log = String.format("[%s] %s: Action=%s, Flags=0x%X, Buttons=0x%X, Pointers=%d",
                        context.currentState.name(), action,
                        MotionEvent.actionToString(event.getAction()),
                        event.getFlags(), event.getButtonState(), event.getPointerCount());
                Log.d(TAG, log);
                context.eventHistory.add(log);
                context.lastEventDescription = log;
            }
        }

        protected void transitionTo(TouchpadGestureState newState, String reason, TouchpadGestureContext context) {
            if (DEBUG) {
                Log.d(TAG, String.format("State transition: %s -> %s (%s)",
                        context.currentState.name(), newState.name(), reason));
            }
            context.currentState = newState;
        }
    }

    // 空闲状态处理器
    public static class IdleStateHandler extends TouchpadStateHandler {
        public IdleStateHandler(OnePlusTouchpadDetector detector, OnTouchpadGestureListener listener) {
            super(detector, listener);
        }

        @Override
        public boolean handleEvent(MotionEvent event, TouchpadGestureContext context) {
            logEvent("处理事件", event, context);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    transitionTo(TouchpadGestureState.HOVER_TRACKING, "手指接触触控板", context);
                    return true;

                case MotionEvent.ACTION_DOWN:
                    return handleDownEvent(event, context);

                case MotionEvent.ACTION_BUTTON_PRESS:
                    transitionTo(TouchpadGestureState.PHYSICAL_CLICK, "物理按压检测", context);
                    listener.onMousePress(MouseButton.MIDDLE);
                    return true;
            }
            return false;
        }

        private boolean handleDownEvent(MotionEvent event, TouchpadGestureContext context) {
            // 记录初始位置
            context.initialPosition.set(event.getX(), event.getY());
            context.currentPosition.set(event.getX(), event.getY());
            context.gestureStartTime = System.currentTimeMillis();

            // 根据手势类型进入对应状态
            switch (context.gestureType) {
                case SINGLE_FINGER:
                    transitionTo(TouchpadGestureState.SINGLE_TAP_PENDING, "单指点击待确认", context);
                    break;
                case TWO_FINGER:
                    transitionTo(TouchpadGestureState.TWO_FINGER_TAP_PENDING, "双指操作待确认", context);
                    setupTwoFingerTapDetection(context);
                    break;
                case PINCH_ZOOM:
                    transitionTo(TouchpadGestureState.PINCH_ZOOM, "缩放手势开始", context);
                    break;
                case PHYSICAL_PRESS:
                    transitionTo(TouchpadGestureState.PHYSICAL_CLICK, "物理按压", context);
                    listener.onMousePress(MouseButton.MIDDLE);
                    break;
            }
            return true;
        }

        private void setupTwoFingerTapDetection(TouchpadGestureContext context) {
            context.pendingTwoFingerTap = true;
            context.scrollStartPosition.set(context.currentPosition.x, context.currentPosition.y);

            // 设置短暂超时，如果在此期间没有MOVE事件，则认为是点击
            context.twoFingerTapTimeoutRunnable = () -> context.pendingTwoFingerTap = false;
            detector.mHandler.postDelayed(context.twoFingerTapTimeoutRunnable, 100);
        }
    }

    // 悬停跟踪状态处理器
    public static class HoverTrackingStateHandler extends TouchpadStateHandler {
        public HoverTrackingStateHandler(OnePlusTouchpadDetector detector, OnTouchpadGestureListener listener) {
            super(detector, listener);
        }

        @Override
        public boolean handleEvent(MotionEvent event, TouchpadGestureContext context) {
            logEvent("悬停事件", event, context);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_MOVE:
                    // 检查是否在双击窗口内
                    if (context.isInDoubleTapWindow) {
                        // 撤回第一次点击，直接开始拖拽
                        context.isInDoubleTapWindow = false; // 清除双击窗口标记
                        if (context.doubleTapTimeoutRunnable != null) {
                            detector.mHandler.removeCallbacks(context.doubleTapTimeoutRunnable);
                        }

                        // 开始拖拽
                        transitionTo(TouchpadGestureState.DRAG_ACTIVE, "双击拖拽开始", context);
                        listener.onMousePress(MouseButton.LEFT);
                        listener.onMouseMove(event.getX(), event.getY(), true);
                    } else {
                        // 普通鼠标移动
                        listener.onMouseMove(event.getX(), event.getY(), false);
                    }
                    return true;

                case MotionEvent.ACTION_HOVER_EXIT:
                    // 准备进入点击状态
                    context.initialPosition.set(event.getX(), event.getY());
                    transitionTo(TouchpadGestureState.IDLE, "手指离开触控板", context);
                    return true;
            }
            return false;
        }
    }

    // 拖拽状态处理器
    public static class DragActiveStateHandler extends TouchpadStateHandler {
        private static final long DRAG_TIMEOUT_MS = 140; // 140ms内没有移动事件则认为拖拽结束
        private Runnable dragTimeoutRunnable;

        public DragActiveStateHandler(OnePlusTouchpadDetector detector, OnTouchpadGestureListener listener) {
            super(detector, listener);
        }

        @Override
        public boolean handleEvent(MotionEvent event, TouchpadGestureContext context) {
            logEvent("拖拽事件", event, context);

            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {// 拖拽中的移动
                listener.onMouseMove(event.getX(), event.getY(), true);

                // 重置拖拽超时计时器
                resetDragTimeout(context);
                return true;
            }
            endDrag(context, "明确的拖拽结束事件");
            return false;
        }

        /**
         * 重置拖拽超时计时器
         */
        private void resetDragTimeout(TouchpadGestureContext context) {
            // 清除之前的计时器
            if (dragTimeoutRunnable != null) {
                detector.mHandler.removeCallbacks(dragTimeoutRunnable);
            }

            // 创建新的超时检测
            dragTimeoutRunnable = () -> {
                if (context.currentState == TouchpadGestureState.DRAG_ACTIVE) {
                    endDrag(context, "拖拽超时结束");
                }
            };

            // 设置超时
            detector.mHandler.postDelayed(dragTimeoutRunnable, DRAG_TIMEOUT_MS);
        }

        /**
         * 结束拖拽
         */
        private void endDrag(TouchpadGestureContext context, String reason) {
            // 清除计时器
            if (dragTimeoutRunnable != null) {
                detector.mHandler.removeCallbacks(dragTimeoutRunnable);
                dragTimeoutRunnable = null;
            }

            // 释放鼠标左键
            listener.onMouseRelease(MouseButton.LEFT);
            context.isInDoubleTapWindow = false;

            if (DEBUG) {
                Log.d(TAG, "拖拽结束: " + reason);
            }

            transitionTo(TouchpadGestureState.HOVER_TRACKING, reason, context);
        }
    }

    // 单指点击状态处理器
    public static class SingleTapPendingStateHandler extends TouchpadStateHandler {
        public SingleTapPendingStateHandler(OnePlusTouchpadDetector detector, OnTouchpadGestureListener listener) {
            super(detector, listener);
        }

        @Override
        public boolean handleEvent(MotionEvent event, TouchpadGestureContext context) {
            logEvent("单指点击事件", event, context);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_UP:
                    return handleUpEvent(event, context);

                case MotionEvent.ACTION_MOVE:
                    // 如果有明显移动，则不是点击
                    if (TouchpadGestureUtils.isMovementSignificant(
                            context.initialPosition, new PointF(event.getX(), event.getY()),
                            detector.mTouchSlop)) {
                        transitionTo(TouchpadGestureState.IDLE, "移动距离过大，非点击", context);
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    transitionTo(TouchpadGestureState.IDLE, "点击被取消", context);
                    return true;
            }
            return false;
        }

        private boolean handleUpEvent(MotionEvent event, TouchpadGestureContext context) {
            // 检查是否有移动，如果有移动则不触发点击
            float distance = TouchpadGestureUtils.calculateDistance(
                    context.initialPosition.x, context.initialPosition.y,
                    event.getX(), event.getY());

            if (distance < detector.mTouchSlop) {
                // 延迟发送单指点击，以便支持双击拖拽时撤回
                setupDelayedClick(context);
            }

            transitionTo(TouchpadGestureState.IDLE, "单指点击完成", context);
            return true;
        }

        private void setupDelayedClick(TouchpadGestureContext context) {
            context.lastTapTime = System.currentTimeMillis();
            context.isInDoubleTapWindow = true;

            // 延迟发送点击事件，给双击拖拽留时间
            Runnable delayedClickRunnable = () -> {
                // 如果此时还在双击窗口内且没有开始拖拽，则发送点击
                if (context.isInDoubleTapWindow && context.currentState != TouchpadGestureState.DRAG_ACTIVE) {
                    listener.onMouseClick(MouseButton.LEFT);
                    if (DEBUG) {
                        Log.d(TAG, "发送延迟的左键点击");
                    }
                }
            };

            // 延迟发送点击事件（延迟时间应该短于双击检测时间）
            detector.mHandler.postDelayed(delayedClickRunnable, 150);

            // 设置双击窗口超时
            context.doubleTapTimeoutRunnable = () -> {
                context.isInDoubleTapWindow = false;
                if (DEBUG) {
                    Log.d(TAG, "双击窗口超时");
                }
            };
            detector.mHandler.postDelayed(context.doubleTapTimeoutRunnable, 160);
        }
    }

    // 双指操作状态处理器
    public static class TwoFingerTapPendingStateHandler extends TouchpadStateHandler {
        public TwoFingerTapPendingStateHandler(OnePlusTouchpadDetector detector, OnTouchpadGestureListener listener) {
            super(detector, listener);
        }

        @Override
        public boolean handleEvent(MotionEvent event, TouchpadGestureContext context) {
            logEvent("双指操作事件", event, context);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_UP:
                    return handleUpEvent(event, context);

                case MotionEvent.ACTION_MOVE:
                    // 有移动事件，进入滚动状态
                    transitionTo(TouchpadGestureState.TWO_FINGER_SCROLL, "双指滚动开始", context);

                    // 取消双指点击检测
                    cancelTwoFingerTapDetection(context);

                    // 开始滚动
                    return handleTwoFingerScroll(event, context);

                case MotionEvent.ACTION_CANCEL:
                    // 期待CANCEL事件，进入滚动状态
                    transitionTo(TouchpadGestureState.TWO_FINGER_SCROLL, "取消事件后进入滚动", context);
                    context.expectCancel = false;
                    return true;
            }
            return false;
        }

        private boolean handleUpEvent(MotionEvent event, TouchpadGestureContext context) {
            if (context.pendingTwoFingerTap) {
                // 如果还在双指点击检测期内，且没有移动，触发右键点击
                float tapDistance = TouchpadGestureUtils.calculateDistance(
                        context.scrollStartPosition.x, context.scrollStartPosition.y,
                        event.getX(), event.getY());

                if (tapDistance < detector.mTouchSlop) {
                    listener.onMouseClick(MouseButton.RIGHT);
                }

                cancelTwoFingerTapDetection(context);
            }

            transitionTo(TouchpadGestureState.IDLE, "双指操作完成", context);
            return true;
        }

        private void cancelTwoFingerTapDetection(TouchpadGestureContext context) {
            context.pendingTwoFingerTap = false;
            if (context.twoFingerTapTimeoutRunnable != null) {
                detector.mHandler.removeCallbacks(context.twoFingerTapTimeoutRunnable);
            }
        }

        private boolean handleTwoFingerScroll(MotionEvent event, TouchpadGestureContext context) {
            float deltaX = context.scrollStartPosition.x - event.getX();
            float deltaY = context.scrollStartPosition.y - event.getY();

            // 应用滚动敏感度
            float scrollX = deltaX * detector.mScrollSensitivity;
            float scrollY = deltaY * detector.mScrollSensitivity;

            // 根据滚动方向选择滚动类型
            if (Math.abs(scrollX) > Math.abs(scrollY)) {
                // 水平滚动，使用Shift+滚轮
                listener.onScrollWheelWithShift(scrollX, 0);
            } else {
                // 垂直滚动，普通滚轮
                listener.onScrollWheel(0, scrollY);
            }

            context.scrollStartPosition.set(event.getX(), event.getY());
            return true;
        }
    }

    // 双指滚动状态处理器
    public static class TwoFingerScrollStateHandler extends TouchpadStateHandler {
        public TwoFingerScrollStateHandler(OnePlusTouchpadDetector detector, OnTouchpadGestureListener listener) {
            super(detector, listener);
        }

        @Override
        public boolean handleEvent(MotionEvent event, TouchpadGestureContext context) {
            logEvent("双指滚动事件", event, context);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    return handleTwoFingerScroll(event, context);

                case MotionEvent.ACTION_UP:
                    transitionTo(TouchpadGestureState.IDLE, "双指滚动结束", context);
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    // 正常的取消事件，不需要特殊处理
                    return true;
            }
            return false;
        }

        private boolean handleTwoFingerScroll(MotionEvent event, TouchpadGestureContext context) {
            float deltaX = context.scrollStartPosition.x - event.getX();
            float deltaY = context.scrollStartPosition.y - event.getY();

            // 应用滚动敏感度
            float scrollX = deltaX * detector.mScrollSensitivity;
            float scrollY = deltaY * detector.mScrollSensitivity;

            // 根据滚动方向选择滚动类型
            if (Math.abs(scrollX) > Math.abs(scrollY)) {
                // 水平滚动，使用Shift+滚轮
                listener.onScrollWheelWithShift(scrollX, 0);
            } else {
                // 垂直滚动，普通滚轮
                listener.onScrollWheel(0, scrollY);
            }

            context.scrollStartPosition.set(event.getX(), event.getY());
            return true;
        }
    }

    // 缩放手势状态处理器
    public static class PinchZoomStateHandler extends TouchpadStateHandler {
        public PinchZoomStateHandler(OnePlusTouchpadDetector detector, OnTouchpadGestureListener listener) {
            super(detector, listener);
        }

        @Override
        public boolean handleEvent(MotionEvent event, TouchpadGestureContext context) {
            logEvent("缩放手势事件", event, context);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    return handlePinchZoom(event, context);

                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() <= 1) {
                        transitionTo(TouchpadGestureState.IDLE, "缩放手势结束", context);
                        context.initialPinchDistance = 0;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    transitionTo(TouchpadGestureState.IDLE, "缩放手势完全结束", context);
                    context.initialPinchDistance = 0;
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    transitionTo(TouchpadGestureState.IDLE, "缩放手势被取消", context);
                    context.initialPinchDistance = 0;
                    return true;
            }
            return false;
        }

        private boolean handlePinchZoom(MotionEvent event, TouchpadGestureContext context) {
            if (event.getPointerCount() < 2)
                return false;

            float currentDistance = TouchpadGestureUtils.calculateTwoPointerDistance(event);

            if (context.initialPinchDistance == 0) {
                context.initialPinchDistance = currentDistance;
                return true;
            }

            float deltaDistance = currentDistance - context.initialPinchDistance;

            // 缩放手势转换为Ctrl+滚轮
            if (Math.abs(deltaDistance) > detector.mTouchSlop) {
                if (deltaDistance > 0) {
                    // 放大手势 -> Ctrl+滚轮向上
                    listener.onScrollWheelWithCtrl(0, -1.0f);
                } else {
                    // 缩小手势 -> Ctrl+滚轮向下
                    listener.onScrollWheelWithCtrl(0, 1.0f);
                }
                context.initialPinchDistance = currentDistance;
            }

            return true;
        }
    }

    // 物理按压状态处理器
    public static class PhysicalClickStateHandler extends TouchpadStateHandler {
        public PhysicalClickStateHandler(OnePlusTouchpadDetector detector, OnTouchpadGestureListener listener) {
            super(detector, listener);
        }

        @Override
        public boolean handleEvent(MotionEvent event, TouchpadGestureContext context) {
            logEvent("物理按压事件", event, context);

            if (event.getButtonState() == 0) {// 物理按键释放对应鼠标中键释放
                listener.onMouseRelease(MouseButton.MIDDLE);
                transitionTo(TouchpadGestureState.IDLE, "物理按压释放", context);
                return true;
            }
            listener.onMouseMove(event.getX(), event.getY(), true);
            return true;
        }
    }

    // ============ 主状态机和实例成员 ============

    // 核心组件
    private final OnTouchpadGestureListener mListener;
    private final Handler mHandler;
    private final Context mAndroidContext;

    // 配置参数
    private final int mTouchSlop;
    private final int mDoubleTapTimeout;
    private final float mScrollSensitivity = 0.6f;

    // 状态机相关
    private final TouchpadGestureContext mGestureContext;
    private final TouchpadEventClassifier mClassifier;
    private final Map<TouchpadGestureState, TouchpadStateHandler> mStateHandlers;

    /**
     * 构造函数，采用状态机架构
     */
    public OnePlusTouchpadDetector(Context context, OnTouchpadGestureListener listener) {
        this.mAndroidContext = context;
        this.mListener = listener;
        this.mHandler = new Handler();

        ViewConfiguration config = ViewConfiguration.get(context);
        this.mTouchSlop = config.getScaledTouchSlop();
        this.mDoubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();

        // 初始化状态机组件
        this.mGestureContext = new TouchpadGestureContext();
        this.mClassifier = new TouchpadEventClassifier();
        this.mStateHandlers = initializeStateHandlers();
    }

    public Boolean handle(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                return this.handlePointerDown(event);
            case MotionEvent.ACTION_POINTER_UP:
                return this.handlePointerUp(event);
            case MotionEvent.ACTION_BUTTON_PRESS:
                return this.handleButtonPress(event);
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return this.handleButtonRelease(event);
            case MotionEvent.ACTION_CANCEL:
                return this.handleCancel(event);
            default:
                return this.onTouchEvent(event);
        }
    }

    /**
     * 初始化状态处理器映射
     */
    private Map<TouchpadGestureState, TouchpadStateHandler> initializeStateHandlers() {
        Map<TouchpadGestureState, TouchpadStateHandler> handlers = new HashMap<>();

        handlers.put(TouchpadGestureState.IDLE,
                new IdleStateHandler(this, mListener));
        handlers.put(TouchpadGestureState.HOVER_TRACKING,
                new HoverTrackingStateHandler(this, mListener));
        handlers.put(TouchpadGestureState.DRAG_ACTIVE,
                new DragActiveStateHandler(this, mListener));
        handlers.put(TouchpadGestureState.SINGLE_TAP_PENDING,
                new SingleTapPendingStateHandler(this, mListener));
        handlers.put(TouchpadGestureState.TWO_FINGER_TAP_PENDING,
                new TwoFingerTapPendingStateHandler(this, mListener));
        handlers.put(TouchpadGestureState.TWO_FINGER_SCROLL,
                new TwoFingerScrollStateHandler(this, mListener));
        handlers.put(TouchpadGestureState.PINCH_ZOOM,
                new PinchZoomStateHandler(this, mListener));
        handlers.put(TouchpadGestureState.PHYSICAL_CLICK,
                new PhysicalClickStateHandler(this, mListener));

        return handlers;
    }

    /**
     * 主要事件处理方法 - 状态机入口
     */
    public boolean onTouchEvent(MotionEvent event) {
        if (mListener == null)
            return false;

        // 1. 事件分类
        TouchpadGestureType gestureType = mClassifier.classifyGesture(event);

        // 2. 更新上下文
        updateContext(event, gestureType);

        // 3. 状态处理
        TouchpadStateHandler handler = mStateHandlers.get(mGestureContext.currentState);
        if (handler != null) {
            return handler.handleEvent(event, mGestureContext);
        }

        return false;
    }

    /**
     * 更新手势上下文
     */
    private void updateContext(MotionEvent event, TouchpadGestureType gestureType) {
        mGestureContext.gestureType = gestureType;
        mGestureContext.currentPosition.set(event.getX(), event.getY());

        // 记录调试信息
        if (DEBUG) {
            String eventDesc = String.format("Event: %s, Type: %s, State: %s",
                    MotionEvent.actionToString(event.getAction()),
                    gestureType.name(),
                    mGestureContext.currentState.name());
            Log.v(TAG, eventDesc);
        }
    }

    /**
     * 处理特殊事件：POINTER_DOWN（缩放手势开始）
     */
    private boolean handlePointerDown(MotionEvent event) {
        if (event.getPointerCount() == 2) {
            // 双指缩放手势开始
            mGestureContext.currentState = TouchpadGestureState.PINCH_ZOOM;
            mGestureContext.initialPinchDistance = TouchpadGestureUtils.calculateTwoPointerDistance(event);

            TouchpadStateHandler handler = mStateHandlers.get(TouchpadGestureState.PINCH_ZOOM);
            if (handler != null) {
                handler.logEvent("缩放手势开始", event, mGestureContext);
            }
        }
        return true;
    }

    /**
     * 处理特殊事件：POINTER_UP（缩放手势结束）
     */
    private boolean handlePointerUp(MotionEvent event) {
        if (mGestureContext.currentState == TouchpadGestureState.PINCH_ZOOM) {
            TouchpadStateHandler handler = mStateHandlers.get(TouchpadGestureState.PINCH_ZOOM);
            if (handler != null) {
                return handler.handleEvent(event, mGestureContext);
            }
        }
        return true;
    }

    /**
     * 处理特殊事件：BUTTON_PRESS（物理按压）
     */
    private boolean handleButtonPress(MotionEvent event) {
        mGestureContext.currentState = TouchpadGestureState.PHYSICAL_CLICK;
        mListener.onMousePress(MouseButton.MIDDLE);

        TouchpadStateHandler handler = mStateHandlers.get(TouchpadGestureState.PHYSICAL_CLICK);
        if (handler != null) {
            handler.logEvent("物理按压开始", event, mGestureContext);
        }
        return true;
    }

    /**
     * 处理特殊事件：BUTTON_RELEASE（物理按压释放）
     */
    private boolean handleButtonRelease(MotionEvent event) {
        if (mGestureContext.currentState == TouchpadGestureState.PHYSICAL_CLICK) {
            TouchpadStateHandler handler = mStateHandlers.get(TouchpadGestureState.PHYSICAL_CLICK);
            if (handler != null) {
                return handler.handleEvent(event, mGestureContext);
            }
        }
        return true;
    }

    /**
     * 处理取消事件 - 重置状态
     */
    private boolean handleCancel(MotionEvent event) {
        if (DEBUG) {
            Log.d(TAG, "CANCEL event - resetting state from " + mGestureContext.currentState.name());
        }

        // 清理定时器
        if (mGestureContext.doubleTapTimeoutRunnable != null) {
            mHandler.removeCallbacks(mGestureContext.doubleTapTimeoutRunnable);
        }
        if (mGestureContext.twoFingerTapTimeoutRunnable != null) {
            mHandler.removeCallbacks(mGestureContext.twoFingerTapTimeoutRunnable);
        }

        // 重置上下文
        mGestureContext.reset();

        return true;
    }

    /**
     * 获取当前状态（用于调试）
     */
    public TouchpadGestureState getCurrentState() {
        return mGestureContext.currentState;
    }

    /**
     * 获取事件历史（用于调试）
     */
    public List<String> getEventHistory() {
        return new ArrayList<>(mGestureContext.eventHistory);
    }
}
