package com.allan.services;


import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;

/**
 * 与JobIntentService的区别：
 * 搞清楚JobIntentService的含义，他代表着顺序的，一个一个排队执行；而且是可以进行耗时操作的。
 * 因此一个耗时操作会等待着另外一个操作的完成。
 * 因此JobIntentService系列的逻辑是onStartCommand中接收到了workIntent，
 * 按照startId按顺序来，一个一个"慢慢"执行;
 * 直到最后一个活儿干完了，stopSelf(startId)就是最后一个id。
 *
 * 但是现在我们的需求是，来一个onStartCommand，我们立刻就要去执行；并不能让他排队。
 * 举例：
 * 我有个Service，他需要根据startService进来的intent做如下几类活儿：
 * 1. Action1 拉起我的FragmentA界面；
 * 2. Action2 执行一段网络请求拿到一份xx数据；
 * 3. Action3 拉起我的FragmentB界面；
 *
 * 显然，如果我们使用JobIntentService当有Action2来工作的时候，我们将无法及时响应Action1，3。
 * 因为他是排队的。与我们的需求不同：
 * 我们要求Action2发起请求就可以结束了，但是Service不能结束。需要等待活干完才能结束。
 *
 * 你或者会想，我直接JobIntentService里面不在他的onHandleWork卡住，开启子线程跑就好了呀？
 * 这样就错误了。因为如果你提前onHandlerWork return掉，就会stopSelf掉就会导致代码游离在Service的生命周期之外。
 * 极度容易被oom_obj memKiller杀掉进程。
 *
 * 因此，设计这个类来满足需求。只有当所有的startId被stopWrap到这里，才能真正stopService。
 */
public abstract class AutoStopService extends Service {
    protected static final String TAG = "AutoStopService";

    static final boolean DEBUG = true;

    private final ArrayList<String> mStartIds = new ArrayList<>(2);

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(DEBUG) Log.d(TAG, "on Destroy!");
    }

    protected final void stopWrap(String startId) {
        if(DEBUG) Log.d(TAG, "stop wrap #" + startId);
        synchronized (mStartIds) {
            if (mStartIds.size() > 1) {
                mStartIds.remove(startId); //注意一定要转为Integer是元素否则错误当成index
                return;
            }

            if (TextUtils.equals(startId, mStartIds.get(0))) {
                mStartIds.clear();
                stopSelf();
                return;
            }

            //size == 0?
            throw new RuntimeException("impossible when stopWrap!");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startIdInt) {
        if (DEBUG) Log.d(TAG, "onStart Command #" + startIdInt + ": " + intent);
        final String startId = "" + startIdInt;

        synchronized (mStartIds) {
            mStartIds.add(startId);
        }

        onHandleWork(intent, startId);
        return START_REDELIVER_INTENT;
    }

    /**
     * 替代你的onStartCommand。
     * 不论这里面同步或者异步，最后完成本次工作后，请调用stopWrap(startIdStr)
     */
    protected abstract void onHandleWork(Intent intent, String startIdStr);
}
