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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import org.lineageos.consumerirtransmitter.beans.IRCMDBean;
import org.lineageos.consumerirtransmitter.utils.IRCMDCacheManager;
import org.lineageos.consumerirtransmitter.utils.ReflectionUtils;

public class ConsumerirTransmitterService extends Service {
    private static final String TAG = "ConsumerirTransmitter";
    private static final boolean DEBUG = true;

    private static final String ACTION_TRANSMIT_IR =
        "org.lineageos.consumerirtransmitter.TRANSMIT_IR";
    private static final String SYS_FILE_ENABLE_IR_BLASTER = "/sys/remote/enable";

    private Object mLock = new Object(); // lock to rw mBound & mControl
    private boolean mBound = false;
    private IControl mControl;
    private TransmitHandler mHandler = null;
    private HandlerThread mHandlerThread = null;
    private static final int MSG_TRANSMIT_IR_CMD = 1000;
    private static final int MSG_BIND_QUICKSET_RETRY = 1001;
    private static final long BIND_QUICKSET_SDK_RETRY_TIME = 5000;

    @Override
    public void onCreate() {
        if (DEBUG)
            Log.d(TAG, "Creating service");
        switchIr("1");
        mHandlerThread = new HandlerThread("transmit_handler");
        mHandlerThread.start();
        mHandler = new TransmitHandler(mHandlerThread.getLooper());
        bindQuickSetService();
        IRCMDCacheManager.getInstance().clear();
        registerReceiver(mIrReceiver, new IntentFilter(ACTION_TRANSMIT_IR));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG)
            Log.d(TAG, "Starting service");
        parseIRCMDIntent(intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (DEBUG)
            Log.d(TAG, "Destroying service");
        super.onDestroy();
        this.unregisterReceiver(mIrReceiver);
        this.unbindService(mControlServiceConnection);
        IRCMDCacheManager.getInstance().clear();
        switchIr("0");
        if (null != mHandlerThread && null != mHandler) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
            mHandlerThread.quitSafely();
            mHandlerThread.interrupt();
            mHandlerThread = null;
        } else {
            mHandler = null;
            mHandlerThread = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Service Connection used to control the bound QuickSet SDK Service
     */
    private final ServiceConnection mControlServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mBound = true;
                mControl = new IControl(service);
            }
            while (!IRCMDCacheManager.getInstance().isEmpty()) {
                IRCMDBean ircmdBean = IRCMDCacheManager.getInstance().consume();
                if (null == ircmdBean) {
                    break;
                }
                if (DEBUG) {
                    Log.i(TAG, "sending cached cmd to QuickSet SDK: " + ircmdBean);
                }
                if (null != mHandler) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_TRANSMIT_IR_CMD, ircmdBean));
                }
            }

            if (DEBUG)
                Log.i(TAG, "QuickSet SDK Service SUCCESSFULLY CONNECTED!");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mBound = false;
                mControl = null;
            }
            if (DEBUG)
                Log.i(TAG, "QuickSet SDK Service DISCONNECTED!");
        }
    };

    /**
     * Try to bind QuickSet SDK Service
     */
    public void bindQuickSetService() {
        if (DEBUG)
            Log.d(TAG,
                "Trying to bind QuickSet service: " + IControl.QUICKSET_UEI_PACKAGE_NAME + " - "
                    + IControl.QUICKSET_UEI_SERVICE_CLASS);
        try {
            Intent controlIntent = new Intent(IControl.ACTION);
            controlIntent.setClassName(
                IControl.QUICKSET_UEI_PACKAGE_NAME, IControl.QUICKSET_UEI_SERVICE_CLASS);
            boolean bindResult = (Boolean) ReflectionUtils.invokeMethod(this, "bindServiceAsUser",
                new Class[] {Intent.class, ServiceConnection.class, int.class, UserHandle.class

                },
                new Object[] {controlIntent, mControlServiceConnection, Context.BIND_AUTO_CREATE,
                    ReflectionUtils.getStaticAttribute("android.os.UserHandle", "CURRENT")});
            if (!bindResult && DEBUG) {
                Log.e(TAG, "Binding QuickSet Control service failed!, retry later");
                if (null != mHandler) {
                    mHandler.sendEmptyMessageDelayed(
                        MSG_BIND_QUICKSET_RETRY, BIND_QUICKSET_SDK_RETRY_TIME);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Binding QuickSet Control service failed!", t);
        }
    }

    /**
     * Try to send Infrared pattern, catch and log exceptions.
     *
     * @param carrierFrequency carrier frequency, see ConsumerIrManager Android
     *     API
     * @param pattern          IR pattern to send, see ConsumerIrManager Android
     *     API
     */
    public int transmitIrPattern(int carrierFrequency, int[] pattern) {
        if (DEBUG)
            Log.d(TAG,
                "transmitIrPattern called: freq: " + carrierFrequency
                    + ", pattern-len: " + pattern.length);

        if (mControl == null || !mBound) {
            if (DEBUG)
                Log.w(TAG, "QuickSet Service seems not to be bound. Trying to bind again and exit!");
            IRCMDCacheManager.getInstance().produce(new IRCMDBean(carrierFrequency, pattern));
            bindQuickSetService();
            return -1;
        }
        try {
            mControl.transmit(carrierFrequency, pattern);
            int resultCode = mControl.getLastResultcode();
            return resultCode;
        } catch (Throwable t) {
            Log.e(TAG, "Exception while trying to send command to QuickSet Service!", t);
            return -1;
        }
    }

    /**
     * Enable or disable Ir chip
     *
     * @param value 0 to disable, 1 to enable
     */
    private static boolean switchIr(String value) {
        File file = new File(SYS_FILE_ENABLE_IR_BLASTER);
        if (!file.exists() || !file.isFile() || !file.canWrite()) {
            return false;
        }
        try {
            FileWriter fileWriter = new FileWriter(file);
            fileWriter.write(value);
            fileWriter.flush();
            fileWriter.close();
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private BroadcastReceiver mIrReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            parseIRCMDIntent(intent);
        }
    };

    private void parseIRCMDIntent(Intent intent) {
        if (null == intent) {
            if (DEBUG) {
                Log.e(TAG, "parseIRCMDIntent: null intent");
            }
            return;
        }
        if (TextUtils.equals(ACTION_TRANSMIT_IR, intent.getAction())) {
            if (intent.getStringExtra("carrier_freq") != null
                && intent.getStringExtra("pattern") != null) {
                int carrierFrequency = Integer.parseInt(intent.getStringExtra("carrier_freq"));
                String patternStr = intent.getStringExtra("pattern");
                int[] pattern = Arrays.stream(patternStr.split(","))
                                    .map(String::trim)
                                    .mapToInt(Integer::parseInt)
                                    .toArray();
                if (null != mHandler) {
                    mHandler.sendMessage(mHandler.obtainMessage(
                        MSG_TRANSMIT_IR_CMD, new IRCMDBean(carrierFrequency, pattern)));
                }
            }
        }
    }

    private class TransmitHandler extends Handler {
        // doing transmit in Work Thread, no to block UI Thread

        public TransmitHandler(Looper looper) { super(looper); }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case MSG_TRANSMIT_IR_CMD:
                    IRCMDBean cmd = (IRCMDBean) msg.obj;
                    synchronized (mLock) { transmitIrPattern(cmd.carrierFrequency, cmd.pattern); }
                    break;
                case MSG_BIND_QUICKSET_RETRY:
                    synchronized (mLock) {
                        if (mControl == null || !mBound) {
                            if (DEBUG) {
                                Log.d(TAG, "retry to binding quick sdk");
                            }
                            bindQuickSetService();
                        }
                    }
                    break;
            }
        }
    }
}
