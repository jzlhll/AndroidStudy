package com.allan.services;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 与JobIntentService的区别：
 * 搞清楚JobIntentService的含义，他代表着顺序的，一个一个排队执行；而且是可以进行耗时操作的。
 * 因此一个耗时操作会等待着另外一个操作的完成。
 * 因此JobIntentService系列的逻辑是onStartCommand中接收到了workIntent，
 * 按照startId按顺序来，一个一个"慢慢"执行;
 * 直到最后一个活儿干完了，stopSelf(startId)就是最后一个id。
 *
 * 但是现在我们的需求是，来一个onStartCommand，我们立刻就要去执行；并不能让他排队
 * （当然，如果有顺序的概念的话，也只是说约定某些状态逻辑切换顺序，而某些内部操作是不用排队的）。
 * 举例：
 * 我有个Service，他需要根据startService进来的intent做如下几类活儿：
 * 1. Action1 拉起我的FragmentA界面；
 * 2. Action2 拉起我的FragmentB界面；
 * 3. Action3 执行一段网络请求拿到一份xx数据；
 *
 * 显然，如果我们使用JobIntentService当有Action3来工作的时候，我们将无法及时响应Action1，2；
 * 而Action1，2确是希望有排队的需求。
 *
 * 你或者会想，我直接JobIntentService里面不在他的onHandleWork卡住，开启子线程跑就好了呀？
 * 这样就错误了。因为如果你提前onHandlerWork return掉，就会stopSelf掉就会导致代码游离在Service的生命周期之外。
 * 极度容易被oom_obj memKiller杀掉进程。
 *
 * 因此，设计这个类来满足上述需求。
 */
public abstract class AutoStopService extends Service {
    protected static final String TAG = "AutoStopService";

    static final boolean DEBUG = true;
    public static final String EXCUTE_TYPE_DIRECT = "type_direct";
    public static final String EXCUTE_TYPE_MAIN = "type_main";
    public static final String EXCUTE_TYPE_SUB = "type_thread";
    public static final String EXCUTE_TYPE_ASYNC = "type_async";

    /**
     * 直接在onStartCommand中执行onHandleWork。
     * 并帮你执行stopWrap
     */
    public static final int EXCUTE_TYPE_DIRECTLY_IN_OnStartCmd = 0;
    /**
     * 在本类中的mainHandler中执行onHandleWork。
     * 并帮你执行stopWrap
     */
    public static final int EXCUTE_TYPE_IN_MainHandler = 1;
    /**
     * 在本类中的ThreadPool或者你提供的executeInSubThread中执行onHandleWork。
     * 并帮你执行stopWrap
     */
    public static final int EXCUTE_TYPE_DIRECTLY_IN_SubThread = 2;
    /**
     * 交给了你外部去执行并且适用于比如一些外部框架network的异步请求不容易做到同步的那种
     * *注意*：自行在执行完成后调用stopWrap
     */
    public static final int EXCUTE_TYPE_DIRECTLY_IN_ExecuteAsync = 3;

    private final ArrayList<Integer> mStartIds = new ArrayList<>(2);

    private ThreadPoolExecutor executorService;
    private synchronized void init() {
        if (executorService == null) {
            executorService = new ThreadPoolExecutor(1, 8, 30L,
                    TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        }
    }

    public void executeRunnable(Runnable r) {
        if (executorService == null) {
            init();
        }

        executorService.execute(r);
    }

    private Handler mMainHandler;
    private Handler getMainHandler() { //不需要做保护sync
        if (mMainHandler == null) {
            mMainHandler = new Handler(Looper.getMainLooper());
        }
        return mMainHandler;
    }

    /**
     * Default empty constructor.
     */
    public AutoStopService() {
    }

    protected final void stopWrap(int startId) {
        if(DEBUG) Log.d(TAG, "stop wrap #" + startId);
        synchronized (mStartIds) {
            if (mStartIds.size() > 1) {
                mStartIds.remove(Integer.valueOf(startId)); //注意一定要转为Integer是元素否则错误当成index
                return;
            }

            if (mStartIds.get(0) == startId) {
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
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        if (DEBUG) Log.d(TAG, "onStart Command #" + startId + ": " + intent);

        synchronized (mStartIds) {
            mStartIds.add(startId);
        }

        final int workType = executeType(intent);
        if (workType == EXCUTE_TYPE_DIRECTLY_IN_OnStartCmd) {
            onHandleWork(intent, workType, startId);
            stopWrap(startId);
        } else if (workType == EXCUTE_TYPE_IN_MainHandler) {
            getMainHandler().post(new Runnable() {
                @Override
                public void run() {
                    onHandleWork(intent, workType, startId);
                    stopWrap(startId);
                }
            });
        } else if (workType == EXCUTE_TYPE_DIRECTLY_IN_SubThread) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    onHandleWork(intent, workType, startId);
                    stopWrap(startId);
                }
            };
            if (!executeInSubThread(runnable)) {
                executeRunnable(runnable);
            }
        } else if (workType == EXCUTE_TYPE_DIRECTLY_IN_ExecuteAsync) {
            onHandleWork(intent, workType, startId);
        }

        return START_REDELIVER_INTENT;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }

        if (mMainHandler != null) {
            mMainHandler.removeCallbacksAndMessages(null);
            mMainHandler = null;
        }
        if(DEBUG) Log.d(TAG, "on Destroy!");
    }

    /**
     * 子类实现；判断onStartCommand传递的这个Intent需要按照哪种executeType去做。
     */
    protected abstract int executeType(Intent intent);

    /**
     * 不论是哪种ExecuteType执行逻辑；
     * 都将会跑到这里，因此不建议做异步动作。
     *
     * 如果你的executeType针对此Intent返回了EXCUTE_TYPE_DIRECTLY_IN_OtherSubThread，
     * 那么：在异步完成后一定要手动执行stopWrap(startId);
     */
    protected abstract void onHandleWork(Intent intent, int executeType, int startId);
    /**
     * 如果你希望子线程跑在你们自定义的线程池中，就调用并返回true即可。
     * 如果不处理不实现 默认为false，则使用内部的ExecutorService
     * executorService.execute(runnable);
     *
     * 真实的工作还是交给onHandleWork
     */
    protected boolean executeInSubThread(Runnable runnable) {
        return false;
    }
}
