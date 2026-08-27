package com.hjmicro.netty;

import java.util.concurrent.*;

public class MachineLock {

    private static final ConcurrentHashMap<String, Semaphore> map = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Thread> lockThread =
            new ConcurrentHashMap<>();


    private static final ConcurrentHashMap<String, Long> timeLock =
            new ConcurrentHashMap<>();
    public static synchronized void unLock(String ip) {
        System.out.println("释放锁：" + ip);
        Semaphore semaphore = map.get(ip);
        if (semaphore != null) {
            // 检查当前可用许可数，只有当它小于1时才释放许可
            if (semaphore.availablePermits() < 1) {
                semaphore.release();
            }
        }
    }


    public static void lock(String ip)  {
        Semaphore semaphore = map.computeIfAbsent(ip, k -> new Semaphore(1));
        try {
            System.out.println("添加锁：" + ip);
            semaphore.acquire();
            lockThread.put(ip, Thread.currentThread());
            System.out.println("加锁成功！：" + ip);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void doubleLock(String ip,Long time)  {
        timeLock.put(ip,time);
        Semaphore semaphore = map.computeIfAbsent(ip, k -> new Semaphore(1));
        try {
            System.out.println("添加锁：" + ip);
            semaphore.acquire();
            lockThread.put(ip, Thread.currentThread());
            System.out.println("加锁成功！：" + ip);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 机器如果不在线调用这个，会直接进入等待状态
     * @param ip
     */
//    public static void offLineLock(String ip)  {
//        Semaphore semaphore = map.computeIfAbsent(ip, k -> new Semaphore(1));
//        synchronized (semaphore){
//            try {
//                System.out.println("增加机器离线锁：" + ip);
//                semaphore.acquire(semaphore.availablePermits() + 1);
//                lockThread.put(ip, Thread.currentThread());
//                System.out.println("加锁成功！：" + ip);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
//        }
//    }

    public static synchronized boolean unDoubleLock(String ip,Long time) {
        Long l = timeLock.get(ip);
        if (l != null && time < l){
            return false;
        }
        System.out.println("释放锁：" + ip);
        Semaphore semaphore = map.get(ip);
        if (semaphore != null) {
            // 检查当前可用许可数，只有当它小于1时才释放许可
            if (semaphore.availablePermits() < 1) {
                semaphore.release();
                return true;
            }
            if (semaphore.availablePermits() == 1){
                Thread thread = lockThread.get(ip);
                if (thread != null){
                    thread.interrupt();
                }
                return true;
            }
        }
        return false;
    }



    public static void updateLockTime(String ip,long time)  {
        timeLock.put(ip,time);
    }

}
