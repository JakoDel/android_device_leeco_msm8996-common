/*
 * Copyright (c) 2018 The LineageOS Project
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

package org.lineageos.consumerirtransmitter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Log;
import org.lineageos.consumerirtransmitter.utils.ReflectionUtils;

public class Startup extends BroadcastReceiver {
    private static final String TAG = "ConsumerirTransmitter";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action.equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)) {
            Log.d(TAG, "ACTION_BOOT_COMPLETED Starting");

            ReflectionUtils.invokeMethod(context, "startServiceAsUser",
                new Class[] {Intent.class, UserHandle.class

                },
                new Object[] {new Intent(context, ConsumerirTransmitterService.class),
                    ReflectionUtils.getStaticAttribute("android.os.UserHandle", "CURRENT")});
        }
    }
}
