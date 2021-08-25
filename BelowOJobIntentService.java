package com.allan.services;

import android.app.Service;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 本类用于处理排队的任务；当跑在O（8.0）以上的机器，你的工作将被当做一个job分发JobScheduler.enqueue.
 * 而老的机器会跑到Context.startService;
 *
 * 你必须在manifest中发布你的类，以便于系统去调度。
 * android:permission="android.permission.BIND_JOB_SERVICE"
 * android:exported="true"
 *
 * 使用{@link #enqueueWork(Context, Class, int, Intent)} 将新的任务排队，
 * 它将被最终分发到{@link #onHandleWork(Intent)}去处理。
 *
 * 你不需要使用{@link androidx.legacy.content.WakefulBroadcastReceiver}.
 * 跑到androidO以上，JobScheduler会处理好wakelock，在有任务执行的时候，他会保证持有锁.
 * 在老平台上的话，这个类已经通过PowerManager帮你处理了;
 * 也就是说，这个应用必须申请wakelock权限{@link android.Manifest.permission#WAKE_LOCK}。
 *
 * 有不少的区别，当跑在O以上或者以下：
 *
 * 1. 当跑在老版本上，不论是否在doze状态或者其他条件，实际上，排队的任务会直接startService起一个服务。
 * 而在android O以上，被当做一个Job跑起来，它将被提交给标准的JobScheduler处理，并且使用{@link JobInfo.Builder#setOverrideDeadline(long)}
 *为0的，这样的话：job不会在doze模块运行，它可能会因为设备有内存压力或者很多的任务的时候，被推迟。
 *
 * 2. 当跑在老版本上，作为一个普通的Service：它是可以无限运行的，运行的越久系统将越容易杀掉它的进程，并且在内存有压力的时候，
 * 可能被刚刚起来就被杀掉；
 * 而当做一个Job来执行的话，作为JobService，执行的时间是有限定的，Job执行后将被停止(清理掉，但不是说杀进程) 并且在以后重新开启它的动作。
 * 当系统有内存压力的时候，Job通常不会被杀，因为并行的job数量，是被设备当前的内存状态动态调节的。
 */
public abstract class BelowOJobIntentService extends Service {
    protected static final String TAG = "JobIntentService";

    static final boolean DEBUG = true;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    CommandProcessor mCurProcessor;
    boolean mInterruptIfStopped = false;
    boolean mStopped = false;
    boolean mDestroyed = false;

    static final Object sLock = new Object();
    static final HashMap<ComponentName, WorkEnqueuer> sClassWorkEnqueuer = new HashMap<>();

    WorkEnqueuer mCompatWorkEnqueuer;
    final ArrayList<CompatWorkItem> mCompatQueue = new ArrayList<>(); //这边单独就直接写了。便于学习。
    /**
     * 抽象目标服务，用于传递任务；并且实现怎么样去传递
     */
    abstract static class WorkEnqueuer {
        final ComponentName mComponentName;

        boolean mHasJobId;
        int mJobId;

        WorkEnqueuer(Context context, ComponentName cn) {
            mComponentName = cn;
        }

        /**
         * 检查设置的jobId是否相同
         */
        void ensureJobId(int jobId) {
            if (!mHasJobId) {
                mHasJobId = true;
                mJobId = jobId;
            } else if (mJobId != jobId) {
                throw new IllegalArgumentException("Given job ID " + jobId
                        + " is different than previous " + mJobId);
            }
        }

        abstract void enqueueWork(Intent work);

        public void serviceStartReceived() {
        }

        public void serviceProcessingStarted() {
        }

        public void serviceProcessingFinished() {
        }
    }

    /**
     * 给androidO以下的设备实现，纯Service的方式。
     */
    static final class CompatWorkEnqueuer extends WorkEnqueuer {
        private final Context mContext;
        private final PowerManager.WakeLock mLaunchWakeLock;
        private final PowerManager.WakeLock mRunWakeLock;
        boolean mLaunchingService;
        boolean mServiceProcessing;

        CompatWorkEnqueuer(Context context, ComponentName cn) {
            super(context, cn);
            mContext = context.getApplicationContext();
            // Make wake locks.  We need two, because the launch wake lock wants to have
            // a timeout, and the system does not do the right thing if you mix timeout and
            // non timeout (or even changing the timeout duration) in one wake lock.
            PowerManager pm = ((PowerManager) context.getSystemService(Context.POWER_SERVICE));
            mLaunchWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    cn.getClassName() + ":launch");
            mLaunchWakeLock.setReferenceCounted(false);
            mRunWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    cn.getClassName() + ":run");
            mRunWakeLock.setReferenceCounted(false);
        }

        @Override
        void enqueueWork(Intent work) {
            Intent intent = new Intent(work);
            intent.setComponent(mComponentName);
            if (DEBUG) Log.d(TAG, "Starting service for work: " + work);
            if (mContext.startService(intent) != null) {
                synchronized (this) {
                    if (!mLaunchingService) {
                        mLaunchingService = true;
                        if (!mServiceProcessing) {
                            // If the service is not already holding the wake lock for
                            // itself, acquire it now to keep the system running until
                            // we get this work dispatched.  We use a timeout here to
                            // protect against whatever problem may cause it to not get
                            // the work.
                            mLaunchWakeLock.acquire(60 * 1000);
                        }
                    }
                }
            }
        }

        @Override
        public void serviceStartReceived() {
            synchronized (this) {
                // Once we have started processing work, we can count whatever last
                // enqueueWork() that happened as handled.
                mLaunchingService = false;
            }
        }

        @Override
        public void serviceProcessingStarted() {
            synchronized (this) {
                // We hold the wake lock as long as the service is processing commands.
                if (!mServiceProcessing) {
                    mServiceProcessing = true;
                    // Keep the device awake, but only for at most 10 minutes at a time
                    // (Similar to JobScheduler.)
                    mRunWakeLock.acquire(10 * 60 * 1000L);
                    mLaunchWakeLock.release();
                }
            }
        }

        @Override
        public void serviceProcessingFinished() {
            synchronized (this) {
                if (mServiceProcessing) {
                    // If we are transitioning back to a wakelock with a timeout, do the same
                    // as if we had enqueued work without the service running.
                    if (mLaunchingService) {
                        mLaunchWakeLock.acquire(60 * 1000);
                    }
                    mServiceProcessing = false;
                    mRunWakeLock.release();
                }
            }
        }
    }

    /**
     * 抽象定义了一份被分发的任务
     */
    interface GenericWorkItem {
        Intent getIntent();
        void complete();
    }

    /**
     * 对GenericWorkItem在androidO以下平台的实现的实现: intents通过纯服务的onStartCommand做的.
     */
    final class CompatWorkItem implements GenericWorkItem {
        final Intent mIntent;
        final int mStartId;

        CompatWorkItem(Intent intent, int startId) {
            mIntent = intent;
            mStartId = startId;
        }

        @Override
        public Intent getIntent() {
            return mIntent;
        }

        @Override
        public void complete() {
            if (DEBUG) Log.d(TAG, "Stopping self: #" + mStartId);
            stopSelf(mStartId);
        }
    }

    /**
     * 这里一个Task用于在后台，出列和处理任务。
     */
    final class CommandProcessor extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            GenericWorkItem work;

            if (DEBUG) Log.d(TAG, "Starting to dequeue work...");

            while ((work = dequeueWork()) != null) {
                if (DEBUG) Log.d(TAG, "Processing next work: " + work);
                onHandleWork(work.getIntent());
                if (DEBUG) Log.d(TAG, "Completing work: " + work);
                work.complete();
            }

            if (DEBUG) Log.d(TAG, "Done processing work!");

            return null;
        }

        @Override
        protected void onCancelled(Void aVoid) {
            if (DEBUG) Log.d(TAG, "on Cancelled!");
            processorFinished();
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if (DEBUG) Log.d(TAG, "on PostExecute!");
            processorFinished();
        }
    }

    /**
     * Default empty constructor.
     */
    public BelowOJobIntentService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) Log.d(TAG, "service CREATING: " + this);
        ComponentName cn = new ComponentName(this, this.getClass());
        mCompatWorkEnqueuer = getWorkEnqueuer(this, cn, false, 0);
    }

    /**
     * 在androidO以下处理onStartCommand 把任务排队，后面会被分发到{@link #onHandleWork(Intent)}里面执行.
     */
    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        mCompatWorkEnqueuer.serviceStartReceived();
        if (DEBUG) Log.d(TAG, "onStart Command #" + startId + ": " + intent);
        synchronized (mCompatQueue) {
            mCompatQueue.add(new CompatWorkItem(intent != null ? intent : new Intent(),
                    startId));
            ensureProcessorRunningLocked(true);
        }
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (mCompatQueue) {
            mDestroyed = true;
            mCompatWorkEnqueuer.serviceProcessingFinished();
        }
        if (DEBUG) Log.d(TAG, "service on Destroy!");
    }

    /**
     * 在子类中调用这个方法，去排队的你的任务.这个将在androidO以下直接起一个Service；或者在androidO以上当做一个Job排队任务.
     * 不管哪种情况，都会有wakelock帮你持有，来确保工作运行。任务将排队，最终会在{@link #onHandleWork(Intent)}出现.
     *
     * @param context 调用方
     * @param cls  你的JobIntentService子类.
     * @param jobId 一个单独用于调度的jobID; 同一个类所有的任务都必须是相同的
     * @param work 任务就是一个Intent，用于排队用的.
     */
    public static void enqueueWork(@NonNull Context context, @NonNull Class cls, int jobId,
                                   @NonNull Intent work) {
        enqueueWork(context, new ComponentName(context, cls), jobId, work);
    }

    /**
     * 就像 {@link #enqueueWork(Context, Class, int, Intent)}, 但是提供了ComponentName替代class。
     */
    public static void enqueueWork(@NonNull Context context, @NonNull ComponentName component,
                                   int jobId, @NonNull Intent work) {
        synchronized (sLock) {
            WorkEnqueuer we = getWorkEnqueuer(context, component, true, jobId);
            we.ensureJobId(jobId);
            we.enqueueWork(work);
        }
    }

    static WorkEnqueuer getWorkEnqueuer(Context context, ComponentName cn, boolean hasJobId,
                                                                           int jobId) {
        WorkEnqueuer we = sClassWorkEnqueuer.get(cn);
        if (we == null) {
            we = new CompatWorkEnqueuer(context, cn);
            sClassWorkEnqueuer.put(cn, we);
        }
        return we;
    }

    /**
     * 每一份分发的任务都将被服务顺序的调用到这里。这个方法是在子线程运行的，所以你可以做耗时操作。
     * 一旦返回, 这个任务将被认为完成了；并且，如果有下一份工作就可以从这里继续分发出去，
     * 否则，整个服务就销毁了再也没事可以干。
     *
     * 在androidO以下，对于执行时间是没有限制的。
     * 但是在androidO以上，需要注意，因为任务被当做Job来运行，执行是有最大限制时间的。这样的话，
     * 某个任务或者整串任务超过了限制，服务就会被停止掉（即使你正在工作），然后，过会儿重启最后一个没有完成的任务。
     *
     * @param intent Intent用于描述你的任务
     */
    protected abstract void onHandleWork(@NonNull Intent intent);

    /**
     * 控制执行在{@link #onHandleWork(Intent)} 里面的代码，是否可以被打断如果这个Job已经停止。
     * 默认是false.  如果设置了true， 当调用了{@link #onStopCurrentWork()},
     * 此类将第一时间调用{@link AsyncTask#cancel(boolean) AsyncTask.cancel(true)} 去停止正在工作中的Task。
     *
     * @param interruptIfStopped 设为true，允许系统去打断正在工作中的任务
     */
    public void setInterruptIfStopped(boolean interruptIfStopped) {
        mInterruptIfStopped = interruptIfStopped;
    }

    /**
     * 如果{@link #onStopCurrentWork()}已经被调用过了返回true. 当你执行任务的时候，你可以通过这个方法来判断，是否应该结束了.
     */
    public boolean isStopped() {
        return mStopped;
    }

    /**
     * 当JobScheduler决定停止这个job，这个方法会被回调； 服务的Job没有任何的限制；所以这个方法只会在
     * 这个服务执行了超过Job的执行时间。
     *
     * @return True 来标识让JobManager继续重启这个任务；否则false，放弃这个任务并且后面的任务。
     * 不论返回什么，你的服务都必须停止，否则，系统也会最后也会杀掉他；
     * 默认返回true，这大概率是你想要返回的 (以保证没有任务丢失).
     */
    public boolean onStopCurrentWork() {
        return true;
    }

    boolean doStopCurrentWork() {
        if (mCurProcessor != null) {
            mCurProcessor.cancel(mInterruptIfStopped);
        }
        mStopped = true;
        return onStopCurrentWork();
    }

    void ensureProcessorRunningLocked(boolean reportStarted) {
        if (mCurProcessor == null) {
            mCurProcessor = new CommandProcessor();
            if (mCompatWorkEnqueuer != null && reportStarted) {
                mCompatWorkEnqueuer.serviceProcessingStarted();
            }
            if (DEBUG) Log.d(TAG, "Starting processor: " + mCurProcessor);
            mCurProcessor.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    void processorFinished() {
        synchronized (mCompatQueue) {
            mCurProcessor = null;
            // AsyncTask已经结束，但是此时我们却有排队任务。
            // 因此，我们需要去重启新的流程来执行。如果没有更多的任务，
            // 要么这个服务已经在stop的过程中（因为我们调用了stopSelf在最后一个任务开始的时候）了，
            // 要么，有人又调用了startService一个新的任务又快来了，不管哪种情况，我们都期望直接结束掉等待，
            //即destroyed或者起一个新的onStartCommand，这样就能触发一个新的流程。
            if (mCompatQueue.size() > 0) {
                ensureProcessorRunningLocked(false);
            } else if (!mDestroyed) {
                mCompatWorkEnqueuer.serviceProcessingFinished();
            }
        }
    }

    GenericWorkItem dequeueWork() {
        synchronized (mCompatQueue) {
            if (mCompatQueue.size() > 0) {
                return mCompatQueue.remove(0);
            } else {
                return null;
            }
        }
    }
}

