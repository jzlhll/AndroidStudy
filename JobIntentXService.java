package com.allan.services;

import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobServiceEngine;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

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
 * 而在android O以上，被当做一个Job跑起来，它将被提交给标准的JobScheduler处理，并且使用{@link android.app.job.JobInfo.Builder#setOverrideDeadline(long)}
 *为0的，这样的话：job不会在doze模块运行，它可能会因为设备有内存压力或者很多的任务的时候，被推迟。
 *
 * 2. 当跑在老版本上，作为一个普通的Service：它是可以无限运行的，运行的越久系统将越容易杀掉它的进程，并且在内存有压力的时候，
 * 可能被刚刚起来就被杀掉；
 * 而当做一个Job来执行的话，作为JobService，执行的时间是有限定的，Job执行后将被停止(清理掉，但不是说杀进程) 并且在以后重新开启它的动作。
 * 当系统有内存压力的时候，Job通常不会被杀，因为并行的job数量，是被设备当前的内存状态动态调节的。
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public abstract class JobIntentXService extends Service {
    static final String TAG = "JobIntentService";

    static final boolean DEBUG = false;

    CompatJobEngine mJobImpl;
    CommandProcessor mCurProcessor;
    boolean mInterruptIfStopped = false;
    boolean mStopped = false;

    static final Object sLock = new Object();
    static final HashMap<ComponentName, WorkEnqueuer> sClassWorkEnqueuer = new HashMap<>();

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
     * Get rid of lint warnings about API levels.
     */
    interface CompatJobEngine {
        IBinder compatGetBinder();
        GenericWorkItem dequeueWork();
    }

    /**
     * 实现一个JobServiceEngine用于跟JobIntentService（即我们自己）交互
     */
    @RequiresApi(26)
    static final class JobServiceEngineImpl extends JobServiceEngine
            implements CompatJobEngine {
        static final String TAG = "JobServiceEngineImpl";

        static final boolean DEBUG = false;

        final JobIntentXService mService;
        final Object mLock = new Object();
        JobParameters mParams;

        final class WrapperWorkItem implements GenericWorkItem {
            final JobWorkItem mJobWork;

            WrapperWorkItem(JobWorkItem jobWork) {
                mJobWork = jobWork;
            }

            @Override
            public Intent getIntent() {
                return mJobWork.getIntent();
            }

            @Override
            public void complete() {
                synchronized (mLock) {
                    if (mParams != null) {
                        mParams.completeWork(mJobWork);
                    }
                }
            }
        }

        JobServiceEngineImpl(JobIntentXService service) {
            super(service);
            mService = service;
        }

        @Override
        public IBinder compatGetBinder() {
            return getBinder();
        }

        @Override
        public boolean onStartJob(JobParameters params) {
            if (DEBUG) Log.d(TAG, "onStartJob: " + params);
            mParams = params;
            // We can now start dequeuing work!
            mService.ensureProcessorRunningLocked(false);
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            if (DEBUG) Log.d(TAG, "onStartJob: " + params);
            boolean result = mService.doStopCurrentWork();
            synchronized (mLock) {
                // Once we return, the job is stopped, so its JobParameters are no
                // longer valid and we should not be doing anything with them.
                mParams = null;
            }
            return result;
        }

        /**
         * Dequeue some work.
         */
        @Override
        public GenericWorkItem dequeueWork() {
            JobWorkItem work;
            synchronized (mLock) {
                if (mParams == null) {
                    return null;
                }
                work = mParams.dequeueWork();
            }
            if (work != null) {
                work.getIntent().setExtrasClassLoader(mService.getClassLoader());
                return new JobServiceEngineImpl.WrapperWorkItem(work);
            } else {
                return null;
            }
        }
    }

    @RequiresApi(26)
    static final class JobWorkEnqueuer extends WorkEnqueuer {
        private final JobInfo mJobInfo;
        private final JobScheduler mJobScheduler;

        JobWorkEnqueuer(Context context, ComponentName cn, int jobId) {
            super(context, cn);
            ensureJobId(jobId);
            JobInfo.Builder b = new JobInfo.Builder(jobId, mComponentName);
            mJobInfo = b.setOverrideDeadline(0).build();
            mJobScheduler = (JobScheduler) context.getApplicationContext().getSystemService(
                    Context.JOB_SCHEDULER_SERVICE);
        }

        @Override
        void enqueueWork(Intent work) {
            if (DEBUG) Log.d(TAG, "Enqueueing work: " + work);
            mJobScheduler.enqueue(mJobInfo, new JobWorkItem(work));
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
            processorFinished();
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            processorFinished();
        }
    }

    /**
     * Default empty constructor.
     */
    public JobIntentXService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) Log.d(TAG, "CREATING: " + this);
        mJobImpl = new JobServiceEngineImpl(this);
    }

    /**
     * 啥也不用干
     */
    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (DEBUG) Log.d(TAG, "Ignoring start command: " + intent);
        return START_NOT_STICKY;
    }

    /**
     * androidO以上，返回JobServiceEngine的IBinder
     */
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        if (mJobImpl != null) {
            IBinder engine = mJobImpl.compatGetBinder();
            if (DEBUG) Log.d(TAG, "Returning engine: " + engine);
            return engine;
        } else {
            return null;
        }
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
            if (!hasJobId) {
                throw new IllegalArgumentException("Can't be here without a job id");
            }
            we = new JobWorkEnqueuer(context, cn, jobId);
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
     * 当JobScheduler决定停止这个job，这个方法会被回调； 服务的Job没有任何的限制，所以这个方法只会在
     * 这个服务执行了超过Job的执行时间。
     *
     * @return True 来标识让JobManager继续重启这个任务；否则false，放弃这个任务和后面跟着的任务。
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
            if (DEBUG) Log.d(TAG, "Starting processor: " + mCurProcessor);
            mCurProcessor.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    void processorFinished() {
    }

    GenericWorkItem dequeueWork() {
        if (mJobImpl != null) {
            return mJobImpl.dequeueWork();
        } else {
            return null;
        }
    }
}

