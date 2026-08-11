package com.example.blestudydemo;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 一个最小 Future 实现，用来把 Android 回调式异步结果转换成 Future。
 *
 * <p>项目 minSdk 是 23，不直接依赖 {@code CompletableFuture}。调用方可以用
 * {@link #get()} 等待结果，BLE/蓝牙回调线程则调用 {@link #set(Object)} 或
 * {@link #setException(Throwable)} 完成它。</p>
 */
class SimpleFuture<T> implements Future<T> {
    private boolean cancelled;
    private boolean done;
    private T value;
    private Throwable error;

    @Override
    public synchronized boolean cancel(boolean mayInterruptIfRunning) {
        if (done) {
            return false;
        }
        cancelled = true;
        done = true;
        notifyAll();
        return true;
    }

    @Override
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    @Override
    public synchronized boolean isDone() {
        return done;
    }

    @Override
    public synchronized T get() throws InterruptedException, ExecutionException {
        // wait/notifyAll 保护完成状态，避免调用方忙等。
        while (!done) {
            wait();
        }
        return resultOrThrow();
    }

    @Override
    public synchronized T get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long timeoutMillis = unit.toMillis(timeout);
        long end = System.currentTimeMillis() + timeoutMillis;
        while (!done && timeoutMillis > 0) {
            wait(timeoutMillis);
            timeoutMillis = end - System.currentTimeMillis();
        }
        if (!done) {
            throw new TimeoutException();
        }
        return resultOrThrow();
    }

    synchronized void set(T value) {
        if (done) {
            return;
        }
        this.value = value;
        done = true;
        notifyAll();
    }

    synchronized void setException(Throwable error) {
        if (done) {
            return;
        }
        this.error = error;
        done = true;
        notifyAll();
    }

    private T resultOrThrow() throws ExecutionException {
        if (cancelled) {
            throw new CancellationException();
        }
        if (error != null) {
            throw new ExecutionException(error);
        }
        return value;
    }
}
