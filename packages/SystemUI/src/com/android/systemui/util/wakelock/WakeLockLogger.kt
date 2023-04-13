/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.util.wakelock

import android.os.PowerManager
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import javax.inject.Inject

class WakeLockLogger @Inject constructor(@WakeLockLog private val buffer: LogBuffer) {
    fun logAcquire(wakeLock: PowerManager.WakeLock, reason: String, count: Int) {
        buffer.log(
            WakeLock.TAG,
            LogLevel.DEBUG,
            {
                str1 = wakeLock.tag
                str2 = reason
                int1 = count
            },
            { "Acquire tag=$str1 reason=$str2 count=$int1" }
        )
    }

    fun logRelease(wakeLock: PowerManager.WakeLock, reason: String, count: Int) {
        buffer.log(
            WakeLock.TAG,
            LogLevel.DEBUG,
            {
                str1 = wakeLock.tag
                str2 = reason
                int1 = count
            },
            { "Release tag=$str1 reason=$str2 count=$int1" }
        )
    }
}
