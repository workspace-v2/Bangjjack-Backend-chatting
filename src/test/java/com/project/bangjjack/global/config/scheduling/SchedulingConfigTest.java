package com.project.bangjjack.global.config.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @Test
    void blockedWebSocketIoDoesNotBlockOtherScheduledTasks() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(SchedulingConfig.class)) {
            ThreadPoolTaskScheduler websocket = context.getBean("webSocketKeepAliveScheduler", ThreadPoolTaskScheduler.class);
            ThreadPoolTaskScheduler general = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            try {
                websocket.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(general.submit(() -> "completed").get(5, TimeUnit.SECONDS)).isEqualTo("completed");
            } finally {
                release.countDown();
            }
        }
    }
}
