package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import com.microsoft.playwright.Page;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Scripted {@link PageHttpClient} that replays queued responses, so the JSON clients can
 * be driven through rate limits, bot walls and malformed bodies without a browser.
 */
final class FakePageHttpClient implements PageHttpClient {

	private final Deque<InPageResponse> responses = new ArrayDeque<>();

	private final List<String> requestedUrls = new ArrayList<>();

	private InPageResponse fallback = InPageResponse.transportError("no scripted response");

	FakePageHttpClient enqueue(InPageResponse... scripted) {
		for (var response : scripted) {
			responses.add(response);
		}
		return this;
	}

	FakePageHttpClient enqueueJson(int status, String body) {
		return enqueue(new InPageResponse(status, body, ""));
	}

	FakePageHttpClient fallingBackTo(InPageResponse response) {
		this.fallback = response;
		return this;
	}

	List<String> requestedUrls() {
		return List.copyOf(requestedUrls);
	}

	@Override
	public InPageResponse get(Page page, String url) {
		return next(url);
	}

	@Override
	public InPageResponse delete(Page page, String url) {
		return next(url);
	}

	@Override
	public InPageResponse postJson(Page page, String url, Map<String, Object> body) {
		return next(url);
	}

	private InPageResponse next(String url) {
		requestedUrls.add(url);
		var response = responses.poll();
		return response != null ? response : fallback;
	}

}
