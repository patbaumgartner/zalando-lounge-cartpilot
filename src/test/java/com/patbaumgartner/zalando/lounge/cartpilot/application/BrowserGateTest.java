package com.patbaumgartner.zalando.lounge.cartpilot.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("BrowserGate")
class BrowserGateTest {

	@Test
	@DisplayName("runs work exclusively when two threads compete")
	void serialisesCompetingWorkflows() throws InterruptedException {
		var gate = new BrowserGate();
		var concurrent = new AtomicBoolean(false);
		var inside = new AtomicInteger();
		var completed = new CountDownLatch(8);

		Runnable work = () -> gate.runExclusively("work", () -> {
			if (inside.incrementAndGet() > 1) {
				concurrent.set(true);
			}
			sleep(15);
			inside.decrementAndGet();
			completed.countDown();
		});

		for (int i = 0; i < 8; i++) {
			Thread.ofVirtual().start(work);
		}

		assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(concurrent).isFalse();
	}

	@Test
	@DisplayName("is reentrant so a workflow can call into a nested exclusive operation")
	void allowsReentrantAcquisition() {
		var gate = new BrowserGate();

		String result = gate.runExclusively("outer", () -> gate.runExclusively("inner", () -> "done"));

		assertThat(result).isEqualTo("done");
		assertThat(gate.isBusy()).isFalse();
	}

	@Test
	@DisplayName("tryRunExclusively skips instead of queueing while another workflow holds the browser")
	void skipsWhenBusy() throws InterruptedException {
		var gate = new BrowserGate();
		var holding = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var skippedWorkRan = new AtomicBoolean(false);

		Thread.ofVirtual().start(() -> gate.runExclusively("long scan", () -> {
			holding.countDown();
			awaitQuietly(release);
		}));
		assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

		boolean ran = gate.tryRunExclusively("keep-alive", () -> skippedWorkRan.set(true));

		assertThat(ran).isFalse();
		assertThat(skippedWorkRan).isFalse();

		release.countDown();
		await().atMost(Duration.ofSeconds(5)).until(() -> !gate.isBusy());
		assertThat(gate.tryRunExclusively("keep-alive", () -> skippedWorkRan.set(true))).isTrue();
		assertThat(skippedWorkRan).isTrue();
	}

	@Test
	@DisplayName("releases the browser when the workflow throws")
	void releasesOnFailure() {
		var gate = new BrowserGate();

		try {
			gate.runExclusively("boom", () -> {
				throw new IllegalStateException("boom");
			});
		}
		catch (IllegalStateException expected) {
			// the gate must not stay locked after a failed workflow
		}

		assertThat(gate.isBusy()).isFalse();
		assertThat(gate.tryRunExclusively("next", () -> {
		})).isTrue();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
