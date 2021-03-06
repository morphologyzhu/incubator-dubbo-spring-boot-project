/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.boot.dubbo.context.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.util.ObjectUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Awaiting Non-Web Spring Boot {@link ApplicationListener}
 *
 * @since 0.1.1
 */
public class AwaitingNonWebApplicationListener implements SmartApplicationListener {

    private static final Logger logger = LoggerFactory.getLogger(AwaitingNonWebApplicationListener.class);

    private static final Class<? extends ApplicationEvent>[] SUPPORTED_APPLICATION_EVENTS =
            of(ApplicationReadyEvent.class, ContextClosedEvent.class);

    /**
     * 是否已经等待完成
     */
    private static final AtomicBoolean awaited = new AtomicBoolean(false);

    private final Lock lock = new ReentrantLock();

    private final Condition condition = lock.newCondition();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return ObjectUtils.containsElement(SUPPORTED_APPLICATION_EVENTS, eventType);
    }

    @Override
    public boolean supportsSourceType(Class<?> sourceType) {
        return true;
    }

    private static <T> T[] of(T... values) {
        return values;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationReadyEvent) {
            onApplicationReadyEvent((ApplicationReadyEvent) event);
        } else if (event instanceof ContextClosedEvent) {
            onContextClosedEvent((ContextClosedEvent) event);
        }
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }

    protected void onApplicationReadyEvent(ApplicationReadyEvent event) {
        // 如果是 Web 环境，则直接返回
        final SpringApplication springApplication = event.getSpringApplication();
        if (!WebApplicationType.NONE.equals(springApplication.getWebApplicationType())) {
            return;
        }
        // 启动一个用户线程，从而实现等待
        await();
    }

    protected void onContextClosedEvent(ContextClosedEvent event) {
        // 释放
        release();
        // 关闭线程池
        shutdown();
    }

    protected void await() {
        // has been waited, return immediately
        // 如果已经处于阻塞等待，直接返回
        if (awaited.get()) {
            return;
        }

        // 创建任务，实现阻塞
        executorService.execute(() -> executeMutually(() -> {
            while (!awaited.get()) {
                if (logger.isInfoEnabled()) {
                    logger.info(" [Dubbo] Current Spring Boot Application is await...");
                }
                try {
                    condition.await();
                    System.out.println("123");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));
    }

    protected void release() {
        executeMutually(() -> {
            // CAS 设置 awaited 为 true
            while (awaited.compareAndSet(false, true)) {
                if (logger.isInfoEnabled()) {
                    logger.info(" [Dubbo] Current Spring Boot Application is about to shutdown...");
                }
                // 通知 Condition
                condition.signalAll();
            }
        });
    }

    private void shutdown() {
        if (!executorService.isShutdown()) {
            // Shutdown executorService
            executorService.shutdown();
        }
    }

    private void executeMutually(Runnable runnable) {
        try {
            lock.lock();
            // 执行 Runnable
            runnable.run();
        } finally {
            lock.unlock();
        }
    }

    static AtomicBoolean getAwaited() {
        return awaited;
    }
}
