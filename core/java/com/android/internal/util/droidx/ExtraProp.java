/*
 * Copyright (C) 2024 DroidX-UI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.droidx;

import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class ExtraProp {

    private static final String TAG = ExtraProp.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final Map<String, Object> propsToChangePixel9Pro;

    static {
        propsToChangePixel9Pro = new HashMap<>();
        propsToChangePixel9Pro.put("BRAND", "google");
        propsToChangePixel9Pro.put("MANUFACTURER", "Google");
        propsToChangePixel9Pro.put("DEVICE", "komodo");
        propsToChangePixel9Pro.put("PRODUCT", "komodo");
        propsToChangePixel9Pro.put("MODEL", "Pixel 9 Pro");
        propsToChangePixel9Pro.put("FINGERPRINT", "google/lynx/lynx:14/UP1A.231005.001/1234567:user/release-keys");
        propsToChangePixel9Pro.put("ID", "UP1A.231005.001");
        propsToChangePixel9Pro.put("TYPE", "user");
        propsToChangePixel9Pro.put("TAGS", "release-keys");

        // Additional properties based on the provided snippet
        propsToChangePixel9Pro.put("product.device", "komodo");
        propsToChangePixel9Pro.put("product.model", "Pixel 9 Pro");
        propsToChangePixel9Pro.put("product.name", "komodo");
        propsToChangePixel9Pro.put("product.product.brand", "google");
        propsToChangePixel9Pro.put("product.product.device", "komodo");
        propsToChangePixel9Pro.put("product.product.model", "Pixel 9 Pro");
        propsToChangePixel9Pro.put("product.product.name", "komodo");
        propsToChangePixel9Pro.put("product.bootimage.brand", "google");
        propsToChangePixel9Pro.put("product.bootimage.device", "komodo");
        propsToChangePixel9Pro.put("product.bootimage.model", "Pixel 9 Pro");
        propsToChangePixel9Pro.put("product.bootimage.name", "komodo");
        propsToChangePixel9Pro.put("product.vendor.device", "komodo");
        propsToChangePixel9Pro.put("product.vendor.model", "Pixel 9 Pro");
        propsToChangePixel9Pro.put("product.vendor.name", "komodo");
        propsToChangePixel9Pro.put("product.odm.brand", "google");
        propsToChangePixel9Pro.put("product.odm.device", "komodo");
        propsToChangePixel9Pro.put("product.odm.model", "Pixel 9 Pro");
        propsToChangePixel9Pro.put("product.odm.name", "komodo");
        propsToChangePixel9Pro.put("product.system.brand", "google");
        propsToChangePixel9Pro.put("product.system.device", "generic");
        propsToChangePixel9Pro.put("product.system.model", "mainline");
        propsToChangePixel9Pro.put("product.system.name", "mainline");
        propsToChangePixel9Pro.put("product.system_ext.brand", "google");
        propsToChangePixel9Pro.put("product.system_ext.device", "komodo");
        propsToChangePixel9Pro.put("product.system_ext.model", "Pixel 9 Pro");
        propsToChangePixel9Pro.put("product.system_ext.name", "komodo");
    }

    public static void setPixel9ProProps() {
        if (isPeEnabled()) {
            dlog("Setting Pixel 9 Pro properties");
            for (Map.Entry<String, Object> prop : propsToChangePixel9Pro.entrySet()) {
                setPropValue(prop.getKey(), prop.getValue());
            }
        } else {
            dlog("Pixel Extra is not enabled, skipping setting Pixel 9 Pro properties");
        }
    }

    private static void setPropValue(String key, Object value) {
        try {
            if (value == null || (value instanceof String && ((String) value).isEmpty())) {
                dlog(TAG + " Skipping setting empty value for key: " + key);
                return;
            }
            dlog(TAG + " Setting property for key: " + key + ", value: " + value.toString());
            Field field;
            Class<?> targetClass;
            try {
                targetClass = Build.class;
                field = targetClass.getDeclaredField(key);
            } catch (NoSuchFieldException e) {
                targetClass = Build.VERSION.class;
                field = targetClass.getDeclaredField(key);
            }
            if (field != null) {
                field.setAccessible(true);
                Class<?> fieldType = field.getType();
                if (fieldType == int.class || fieldType == Integer.class) {
                    if (value instanceof Integer) {
                        field.set(null, value);
                    } else if (value instanceof String) {
                        int convertedValue = Integer.parseInt((String) value);
                        field.set(null, convertedValue);
                        dlog(TAG + " Converted value for key " + key + ": " + convertedValue);
                    }
                } else if (fieldType == String.class) {
                    field.set(null, String.valueOf(value));
                }
                field.setAccessible(false);
            }
        } catch (IllegalAccessException | NoSuchFieldException e) {
            dlog(TAG + " Failed to set prop " + key);
        } catch (NumberFormatException e) {
            dlog(TAG + " Failed to parse value for field " + key);
        }
    }

    private static boolean isPeEnabled() {
        return SystemProperties.getBoolean("persist.sys.pixel.nine", false);
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
