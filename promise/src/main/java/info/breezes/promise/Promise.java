package info.breezes.promise;

import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Promise {

    private static ExecutorService service = Executors.newScheduledThreadPool(Math.min(10, 2 * Runtime.getRuntime().availableProcessors()));
    private final LinkedList<Func<?, ?>> mQueue = new LinkedList<>();
    private final HashMap<Func, Boolean> mUITask = new HashMap<>();
    private final SparseArray<Object> tags = new SparseArray<>();
    private Func<? extends Throwable, Void> mErrorHandler = null;
    private Handler uiHandler;
    private PStatus mStatus = PStatus.BUILDING;

    private void scheduleNext(Object input) {
        if (mStatus == PStatus.CANCELED) {
            mQueue.clear();
            return;
        }
        if (mQueue.isEmpty()) {
            mStatus = PStatus.DONE;
            return;
        }
        while (!mQueue.isEmpty()) {
            Func<?, ?> nextFunc = mQueue.pop();
            Type[] types = nextFunc.getClass().getGenericInterfaces();
            for (Type c : types) {
                ParameterizedType type = (ParameterizedType) c;
                Type[] ts = type.getActualTypeArguments();
                if (ts != null && ts.length == 2) {
                    if (input == null) {
                        scheduleTask(nextFunc, null, mUITask.remove(nextFunc));
                        return;
                    }
                    if (input.getClass().isAssignableFrom((Class<?>) ts[0])) {
                        scheduleTask(nextFunc, input, mUITask.remove(nextFunc));
                        return;
                    }
                }
            }
        }
        mStatus = PStatus.DONE;
        System.out.println("no handler found for input:" + input);
    }

    @SuppressWarnings("unchecked")
    private void scheduleTask(Func<?, ?> func, Object input, Boolean onUI) {
        if (onUI != null && onUI) {
            if (uiHandler == null) {
                uiHandler = new Handler(Looper.getMainLooper());
            }
            uiHandler.post(new UITask(func, input, tags));
        } else {
            service.execute(new Task(func, input, tags));
        }
    }

    private void handError(Throwable e) {
        mStatus = PStatus.FAILED;
        mQueue.clear();
        if (mErrorHandler != null) {
            scheduleTask(mErrorHandler, e, false);
            mErrorHandler = null;
        }
    }

    private final class UITask<P, V> implements Runnable {
        private final SparseArray<Object> tags;
        private final Func<P, V> func;
        private final P input;
        private V output;

        UITask(Func<P, V> func, P input, SparseArray<Object> tags) {
            this.input = input;
            this.func = func;
            this.tags = tags;
        }

        @Override
        public void run() {
            try {
                output = func.run(input, tags);
                scheduleTask(emptyFunc, output, false);
            } catch (Throwable e) {
                handError(e);
            }
        }
    }

    private final class Task<P, V> implements Runnable {
        private final Func<P, V> func;
        private final SparseArray<Object> tags;
        private final P input;
        private V output;

        Task(Func<P, V> func, P input, SparseArray<Object> tags) {
            this.input = input;
            this.func = func;
            this.tags = tags;
        }

        @Override
        public void run() {
            try {
                output = func.run(input, tags);
                scheduleNext(output);
            } catch (Throwable e) {
                handError(e);
            }
        }
    }

    public <P, V> Promise then(Func<P, V> func) {
        if (mStatus != PStatus.BUILDING) {
            throw new RuntimeException("仅在未开始运行时支持");
        }
        mQueue.add(func);
        return this;
    }

    public <P, V> Promise thenOnUI(Func<P, V> func) {
        if (mStatus != PStatus.BUILDING) {
            throw new RuntimeException("仅在未开始运行时支持");
        }
        mQueue.add(func);
        mUITask.put(func, true);
        return this;
    }

    public Promise error(Func<? extends Throwable, Void> func) {
        if (mStatus != PStatus.BUILDING) {
            throw new RuntimeException("仅在未开始运行时支持");
        }
        mErrorHandler = func;
        return this;
    }

    public Promise done() {
        if (mStatus != PStatus.BUILDING) {
            throw new RuntimeException("仅在未开始运行时支持");
        }
        mStatus = PStatus.RUNNING;
        scheduleTask(emptyFunc, null, false);
        return this;
    }

    @SuppressWarnings("unused")
    public PStatus currentStatus() {
        return mStatus;
    }

    @SuppressWarnings("unused")
    public boolean isFinish() {
        return mStatus == PStatus.DONE || mStatus == PStatus.FAILED || mStatus == PStatus.CANCELED;
    }

    @SuppressWarnings("unused")
    public void cancel() {
        if (mStatus == PStatus.RUNNING || mStatus == PStatus.BUILDING) {
            mStatus = PStatus.CANCELED;
        }
    }

    @SuppressWarnings("unused")
    public void destroy() {
        mErrorHandler = null;
        uiHandler = null;
        mQueue.clear();
        mUITask.clear();
        tags.clear();
    }

    private final Func<Object, Object> emptyFunc = new Func<Object, Object>() {
        @Override
        public Object run(Object param, SparseArray<Object> tags) {
            return param;
        }
    };
}

