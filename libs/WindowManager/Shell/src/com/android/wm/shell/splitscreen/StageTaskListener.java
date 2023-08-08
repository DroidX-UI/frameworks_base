/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.splitscreen;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.RemoteAnimationTarget.MODE_OPENING;

import static com.android.wm.shell.common.split.SplitScreenConstants.CONTROLLED_ACTIVITY_TYPES;
import static com.android.wm.shell.common.split.SplitScreenConstants.CONTROLLED_WINDOWING_MODES;
import static com.android.wm.shell.common.split.SplitScreenConstants.CONTROLLED_WINDOWING_MODES_WHEN_ACTIVE;
import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;

import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Slog;
import android.util.SparseArray;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;

import com.android.internal.util.ArrayUtils;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SurfaceUtils;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.split.SplitDecorManager;
import com.android.wm.shell.splitscreen.SplitScreen.StageType;

import java.io.PrintWriter;
import java.util.function.Predicate;

/**
 * Base class that handle common task org. related for split-screen stages.
 * Note that this class and its sub-class do not directly perform hierarchy operations.
 * They only serve to hold a collection of tasks and provide APIs like
 * {@link #addTask(ActivityManager.RunningTaskInfo, WindowContainerTransaction)} for the centralized
 * {@link StageCoordinator} to perform hierarchy operations in-sync with other containers.
 *
 * @see StageCoordinator
 */
class StageTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = StageTaskListener.class.getSimpleName();

    /** Callback interface for listening to changes in a split-screen stage. */
    public interface StageListenerCallbacks {
        void onRootTaskAppeared();

        void onChildTaskAppeared(int taskId);

        void onStatusChanged(boolean visible, boolean hasChildren);

        void onChildTaskStatusChanged(int taskId, boolean present, boolean visible);

        void onRootTaskVanished();

        void onNoLongerSupportMultiWindow();
    }

    private final Context mContext;
    private final StageListenerCallbacks mCallbacks;
    private final SurfaceSession mSurfaceSession;
    private final SyncTransactionQueue mSyncQueue;
    private final IconProvider mIconProvider;

    protected ActivityManager.RunningTaskInfo mRootTaskInfo;
    protected SurfaceControl mRootLeash;
    protected SurfaceControl mDimLayer;
    protected SparseArray<ActivityManager.RunningTaskInfo> mChildrenTaskInfo = new SparseArray<>();
    private final SparseArray<SurfaceControl> mChildrenLeashes = new SparseArray<>();
    // TODO(b/204308910): Extracts SplitDecorManager related code to common package.
    private SplitDecorManager mSplitDecorManager;

    StageTaskListener(Context context, ShellTaskOrganizer taskOrganizer, int displayId,
            StageListenerCallbacks callbacks, SyncTransactionQueue syncQueue,
            SurfaceSession surfaceSession, IconProvider iconProvider) {
        mContext = context;
        mCallbacks = callbacks;
        mSyncQueue = syncQueue;
        mSurfaceSession = surfaceSession;
        mIconProvider = iconProvider;
        taskOrganizer.createRootTask(displayId, WINDOWING_MODE_MULTI_WINDOW, this);
    }

    int getChildCount() {
        return mChildrenTaskInfo.size();
    }

    boolean containsTask(int taskId) {
        return mChildrenTaskInfo.contains(taskId);
    }

    boolean containsToken(WindowContainerToken token) {
        return contains(t -> t.token.equals(token));
    }

    boolean containsContainer(IBinder binder) {
        return contains(t -> t.token.asBinder() == binder);
    }

    /**
     * Returns the top visible child task's id.
     */
    int getTopVisibleChildTaskId() {
        final ActivityManager.RunningTaskInfo taskInfo = getChildTaskInfo(t -> t.isVisible);
        return taskInfo != null ? taskInfo.taskId : INVALID_TASK_ID;
    }

    /**
     * Returns the top activity uid for the top child task.
     */
    int getTopChildTaskUid() {
        final ActivityManager.RunningTaskInfo taskInfo =
                getChildTaskInfo(t -> t.topActivityInfo != null);
        return taskInfo != null ? taskInfo.topActivityInfo.applicationInfo.uid : 0;
    }

    /** @return {@code true} if this listener contains the currently focused task. */
    boolean isFocused() {
        return contains(t -> t.isFocused);
    }

    private boolean contains(Predicate<ActivityManager.RunningTaskInfo> predicate) {
        if (mRootTaskInfo != null && predicate.test(mRootTaskInfo)) {
            return true;
        }

        return getChildTaskInfo(predicate) != null;
    }

    @Nullable
    private ActivityManager.RunningTaskInfo getChildTaskInfo(
            Predicate<ActivityManager.RunningTaskInfo> predicate) {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; --i) {
            final ActivityManager.RunningTaskInfo taskInfo = mChildrenTaskInfo.valueAt(i);
            if (predicate.test(taskInfo)) {
                return taskInfo;
            }
        }
        return null;
    }

    @Override
    @CallSuper
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mRootTaskInfo == null) {
            mRootLeash = leash;
            mRootTaskInfo = taskInfo;
            mSplitDecorManager = new SplitDecorManager(
                    mRootTaskInfo.configuration,
                    mIconProvider,
                    mSurfaceSession);
            mCallbacks.onRootTaskAppeared();
            sendStatusChanged();
            mSyncQueue.runInSync(t -> mDimLayer =
                    SurfaceUtils.makeDimLayer(t, mRootLeash, "Dim layer", mSurfaceSession));
        } else if (taskInfo.parentTaskId == mRootTaskInfo.taskId) {
            final int taskId = taskInfo.taskId;
            mChildrenLeashes.put(taskId, leash);
            mChildrenTaskInfo.put(taskId, taskInfo);
            updateChildTaskSurface(taskInfo, leash, true /* firstAppeared */);
            mCallbacks.onChildTaskStatusChanged(taskId, true /* present */, taskInfo.isVisible);
            if (ENABLE_SHELL_TRANSITIONS) {
                // Status is managed/synchronized by the transition lifecycle.
                return;
            }
            mCallbacks.onChildTaskAppeared(taskId);
            sendStatusChanged();
        } else {
            throw new IllegalArgumentException(this + "\n Unknown task: " + taskInfo
                    + "\n mRootTaskInfo: " + mRootTaskInfo);
        }
    }

    @Override
    @CallSuper
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (mRootTaskInfo.taskId == taskInfo.taskId) {
            // Inflates split decor view only when the root task is visible.
            if (mRootTaskInfo.isVisible != taskInfo.isVisible) {
                if (taskInfo.isVisible) {
                    mSplitDecorManager.inflate(mContext, mRootLeash,
                            taskInfo.configuration.windowConfiguration.getBounds());
                } else {
                    mSyncQueue.runInSync(t -> mSplitDecorManager.release(t));
                }
            }
            mRootTaskInfo = taskInfo;
        } else if (taskInfo.parentTaskId == mRootTaskInfo.taskId) {
            if (!taskInfo.supportsMultiWindow
                    || !ArrayUtils.contains(CONTROLLED_ACTIVITY_TYPES, taskInfo.getActivityType())
                    || !ArrayUtils.contains(CONTROLLED_WINDOWING_MODES_WHEN_ACTIVE,
                    taskInfo.getWindowingMode())) {
                // Leave split screen if the task no longer supports multi window or have
                // uncontrolled task.
                mCallbacks.onNoLongerSupportMultiWindow();
                return;
            }
            if (taskInfo.topActivity == null && mChildrenTaskInfo.contains(taskInfo.taskId)
                    && mChildrenTaskInfo.get(taskInfo.taskId).topActivity != null) {
                // If top activity become null, it means the task is about to vanish, we use this
                // signal to remove it from children list earlier for smooth dismiss transition.
                mChildrenTaskInfo.remove(taskInfo.taskId);
                mChildrenLeashes.remove(taskInfo.taskId);
            } else {
                mChildrenTaskInfo.put(taskInfo.taskId, taskInfo);
            }
            mCallbacks.onChildTaskStatusChanged(taskInfo.taskId, true /* present */,
                    taskInfo.isVisible);
            if (!ENABLE_SHELL_TRANSITIONS && mChildrenLeashes.contains(taskInfo.taskId)) {
                updateChildTaskSurface(taskInfo, mChildrenLeashes.get(taskInfo.taskId),
                        false /* firstAppeared */);
            }
        } else {
            throw new IllegalArgumentException(this + "\n Unknown task: " + taskInfo
                    + "\n mRootTaskInfo: " + mRootTaskInfo);
        }
        if (ENABLE_SHELL_TRANSITIONS) {
            // Status is managed/synchronized by the transition lifecycle.
            return;
        }
        sendStatusChanged();
    }

    @Override
    @CallSuper
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        final int taskId = taskInfo.taskId;
        if (mRootTaskInfo.taskId == taskId) {
            mCallbacks.onRootTaskVanished();
            mRootTaskInfo = null;
            mRootLeash = null;
            mSyncQueue.runInSync(t -> {
                t.remove(mDimLayer);
                mSplitDecorManager.release(t);
            });
        } else if (mChildrenTaskInfo.contains(taskId)) {
            mChildrenTaskInfo.remove(taskId);
            mChildrenLeashes.remove(taskId);
            mCallbacks.onChildTaskStatusChanged(taskId, false /* present */, taskInfo.isVisible);
            if (ENABLE_SHELL_TRANSITIONS) {
                // Status is managed/synchronized by the transition lifecycle.
                return;
            }
            sendStatusChanged();
        }
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        b.setParent(findTaskSurface(taskId));
    }

    @Override
    public void reparentChildSurfaceToTask(int taskId, SurfaceControl sc,
            SurfaceControl.Transaction t) {
        t.reparent(sc, findTaskSurface(taskId));
    }

    private SurfaceControl findTaskSurface(int taskId) {
        if (mRootTaskInfo.taskId == taskId) {
            return mRootLeash;
        } else if (mChildrenLeashes.contains(taskId)) {
            return mChildrenLeashes.get(taskId);
        } else {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
    }

    void onResizing(Rect newBounds, Rect sideBounds, SurfaceControl.Transaction t, int offsetX,
            int offsetY, boolean immediately) {
        if (mSplitDecorManager != null && mRootTaskInfo != null) {
            mSplitDecorManager.onResizing(mRootTaskInfo, newBounds, sideBounds, t, offsetX,
                    offsetY, immediately);
        }
    }

    void onResized(SurfaceControl.Transaction t) {
        if (mSplitDecorManager != null) {
            mSplitDecorManager.onResized(t, null);
        }
    }

    void screenshotIfNeeded(SurfaceControl.Transaction t) {
        if (mSplitDecorManager != null) {
            mSplitDecorManager.screenshotIfNeeded(t);
        }
    }

    void fadeOutDecor(Runnable finishedCallback) {
        if (mSplitDecorManager != null) {
            mSplitDecorManager.fadeOutDecor(finishedCallback);
        } else {
            finishedCallback.run();
        }
    }

    SplitDecorManager getSplitDecorManager() {
        return mSplitDecorManager;
    }

    void addTask(ActivityManager.RunningTaskInfo task, WindowContainerTransaction wct) {
        // Clear overridden bounds and windowing mode to make sure the child task can inherit
        // windowing mode and bounds from split root.
        wct.setWindowingMode(task.token, WINDOWING_MODE_UNDEFINED)
                .setBounds(task.token, null);

        wct.reparent(task.token, mRootTaskInfo.token, true /* onTop*/);
    }

    void reorderChild(int taskId, boolean onTop, WindowContainerTransaction wct) {
        if (!containsTask(taskId)) {
            return;
        }
        wct.reorder(mChildrenTaskInfo.get(taskId).token, onTop /* onTop */);
    }

    /** Collects all the current child tasks and prepares transaction to evict them to display. */
    void evictAllChildren(WindowContainerTransaction wct) {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; i--) {
            final ActivityManager.RunningTaskInfo taskInfo = mChildrenTaskInfo.valueAt(i);
            wct.reparent(taskInfo.token, null /* parent */, false /* onTop */);
        }
    }

    void evictOtherChildren(WindowContainerTransaction wct, int taskId) {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; i--) {
            final ActivityManager.RunningTaskInfo taskInfo = mChildrenTaskInfo.valueAt(i);
            if (taskId == taskInfo.taskId) continue;
            wct.reparent(taskInfo.token, null /* parent */, false /* onTop */);
        }
    }

    void evictNonOpeningChildren(RemoteAnimationTarget[] apps, WindowContainerTransaction wct) {
        final SparseArray<ActivityManager.RunningTaskInfo> toBeEvict = mChildrenTaskInfo.clone();
        for (int i = 0; i < apps.length; i++) {
            if (apps[i].mode == MODE_OPENING) {
                toBeEvict.remove(apps[i].taskId);
            }
        }
        for (int i = toBeEvict.size() - 1; i >= 0; i--) {
            final ActivityManager.RunningTaskInfo taskInfo = toBeEvict.valueAt(i);
            wct.reparent(taskInfo.token, null /* parent */, false /* onTop */);
        }
    }

    void evictInvisibleChildren(WindowContainerTransaction wct) {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; i--) {
            final ActivityManager.RunningTaskInfo taskInfo = mChildrenTaskInfo.valueAt(i);
            if (!taskInfo.isVisible) {
                wct.reparent(taskInfo.token, null /* parent */, false /* onTop */);
            }
        }
    }

    void reparentTopTask(WindowContainerTransaction wct) {
        wct.reparentTasks(null /* currentParent */, mRootTaskInfo.token,
                CONTROLLED_WINDOWING_MODES, CONTROLLED_ACTIVITY_TYPES,
                true /* onTop */, true /* reparentTopOnly */);
    }

    void resetBounds(WindowContainerTransaction wct) {
        wct.setBounds(mRootTaskInfo.token, null);
        wct.setAppBounds(mRootTaskInfo.token, null);
    }

    void onSplitScreenListenerRegistered(SplitScreen.SplitScreenListener listener,
            @StageType int stage) {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; --i) {
            int taskId = mChildrenTaskInfo.keyAt(i);
            listener.onTaskStageChanged(taskId, stage,
                    mChildrenTaskInfo.get(taskId).isVisible);
        }
    }

    private void updateChildTaskSurface(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl leash, boolean firstAppeared) {
        final Point taskPositionInParent = taskInfo.positionInParent;
        mSyncQueue.runInSync(t -> {
            // The task surface might be released before running in the sync queue for the case like
            // trampoline launch, so check if the surface is valid before processing it.
            if (!leash.isValid()) {
                Slog.w(TAG, "Skip updating invalid child task surface of task#" + taskInfo.taskId);
                return;
            }
            t.setCrop(leash, null);
            t.setPosition(leash, taskPositionInParent.x, taskPositionInParent.y);
            if (firstAppeared && !ENABLE_SHELL_TRANSITIONS) {
                t.setAlpha(leash, 1f);
                t.setMatrix(leash, 1, 0, 0, 1);
                t.show(leash);
            }
        });
    }

    private void sendStatusChanged() {
        mCallbacks.onStatusChanged(mRootTaskInfo.isVisible, mChildrenTaskInfo.size() > 0);
    }

    @Override
    @CallSuper
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
    }
}
