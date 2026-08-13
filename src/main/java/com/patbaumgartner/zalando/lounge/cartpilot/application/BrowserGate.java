package com.patbaumgartner.zalando.lounge.cartpilot.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serialises every workflow that drives the shared browser.
 *
 * <p>
 * There is one Patchright browser, one {@code BrowserContext} and one long-lived API
 * page, and Playwright objects may only be used by one thread at a time. Four callers can
 * reach them: the 06:00 scan, the 15-minute keep-alive, a manual {@code /scan}, and the
 * {@code /clear} and Skip commands.
 *
 * <p>
 * The gate is held for a whole <em>workflow</em>, not per call. Serialising individual
 * calls would still let a {@code /clear} land between a scan's scrape and its cart adds,
 * leaving the basket and the database disagreeing about what is reserved.
 *
 * <p>
 * The lock is reentrant, so a workflow that already holds it (a scan calling into
 * {@code CartService.clearCart}) proceeds without deadlocking.
 */
@Component
public class BrowserGate {

	private static final Logger log = LoggerFactory.getLogger(BrowserGate.class);

	private final ReentrantLock lock = new ReentrantLock();

	private volatile String currentOperation;

	/** Runs {@code work} exclusively, waiting for any operation already in flight. */
	public <T> T runExclusively(String operation, Supplier<T> work) {
		boolean contended = lock.isLocked() && !lock.isHeldByCurrentThread();
		if (contended) {
			log.info("'{}' is waiting for '{}' to release the browser", operation, currentOperation);
		}
		lock.lock();
		String previous = currentOperation;
		currentOperation = operation;
		try {
			return work.get();
		}
		finally {
			currentOperation = previous;
			lock.unlock();
		}
	}

	public void runExclusively(String operation, Runnable work) {
		runExclusively(operation, () -> {
			work.run();
			return null;
		});
	}

	/**
	 * Runs {@code work} only if the browser is free right now, returning {@code false}
	 * when it is busy. Used by the keep-alive, which must never queue up behind a scan:
	 * by the time a scan finishes it has already replaced the basket it would refresh.
	 */
	public boolean tryRunExclusively(String operation, Runnable work) {
		if (!lock.tryLock()) {
			log.info("Skipping '{}' — '{}' is using the browser", operation, currentOperation);
			return false;
		}
		String previous = currentOperation;
		currentOperation = operation;
		try {
			work.run();
			return true;
		}
		finally {
			currentOperation = previous;
			lock.unlock();
		}
	}

	/** True when a browser workflow is in flight, used to report status to the group. */
	public boolean isBusy() {
		return lock.isLocked();
	}

}
