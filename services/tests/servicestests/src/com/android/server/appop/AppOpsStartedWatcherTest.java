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

package com.android.server.appop;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OnOpStartedListener;
import android.content.Context;
import android.os.Process;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

/** Tests watching started ops. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppOpsStartedWatcherTest {

    private static final long NOTIFICATION_TIMEOUT_MILLIS = 5000;

    @Test
    public void testWatchStartedOps() {
        // Create a mock listener
        final OnOpStartedListener listener = mock(OnOpStartedListener.class);

        // Start watching started ops
        final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
        appOpsManager.startWatchingStarted(new int[]{AppOpsManager.OP_FINE_LOCATION,
                AppOpsManager.OP_CAMERA}, listener);

        // Start some ops
        appOpsManager.startOp(AppOpsManager.OP_FINE_LOCATION);
        appOpsManager.startOp(AppOpsManager.OP_CAMERA);
        appOpsManager.startOp(AppOpsManager.OP_RECORD_AUDIO);

        // Verify that we got called for the ops being started
        final InOrder inOrder = inOrder(listener);
        inOrder.verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpStarted(eq(AppOpsManager.OP_FINE_LOCATION),
                eq(Process.myUid()), eq(getContext().getPackageName()),
                eq(AppOpsManager.OP_FLAG_SELF), eq(AppOpsManager.MODE_ALLOWED));
        inOrder.verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(1)).onOpStarted(eq(AppOpsManager.OP_CAMERA),
                eq(Process.myUid()), eq(getContext().getPackageName()),
                eq(AppOpsManager.OP_FLAG_SELF), eq(AppOpsManager.MODE_ALLOWED));

        // Stop watching
        appOpsManager.stopWatchingStarted(listener);

        // This should be the only two callbacks we got
        verifyNoMoreInteractions(listener);

        // Start the op again and verify it isn't being watched
        appOpsManager.startOp(AppOpsManager.OP_FINE_LOCATION);
        appOpsManager.finishOp(AppOpsManager.OP_FINE_LOCATION);
        verifyNoMoreInteractions(listener);

        // Start watching an op again (only CAMERA this time)
        appOpsManager.startWatchingStarted(new int[]{AppOpsManager.OP_CAMERA}, listener);

        // Note the ops again
        appOpsManager.startOp(AppOpsManager.OP_CAMERA);
        appOpsManager.startOp(AppOpsManager.OP_FINE_LOCATION);

        // Verify it's watched again
        verify(listener, timeout(NOTIFICATION_TIMEOUT_MILLIS)
                .times(2)).onOpStarted(eq(AppOpsManager.OP_CAMERA),
                eq(Process.myUid()), eq(getContext().getPackageName()),
                eq(AppOpsManager.OP_FLAG_SELF), eq(AppOpsManager.MODE_ALLOWED));
        verifyNoMoreInteractions(listener);

        // Finish up
        appOpsManager.finishOp(AppOpsManager.OP_CAMERA);
        appOpsManager.finishOp(AppOpsManager.OP_FINE_LOCATION);
        appOpsManager.stopWatchingStarted(listener);
    }

    private static Context getContext() {
        return InstrumentationRegistry.getContext();
    }
}
