package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CartApi")
class CartApiTest {

	private static final String CART_URL = "https://www.zalando-lounge.ch/api/phoenix/stockcart/cart";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private CartApi cartApi(FakePageHttpClient http) {
		return new CartApi(http, objectMapper, CART_URL);
	}

	@Nested
	@DisplayName("read")
	class Read {

		@Test
		@DisplayName("parses basket line items")
		void parsesItems() {
			var http = new FakePageHttpClient().enqueueJson(200, """
					{"items":[{"cartItemKey":"k1","configSku":"SKU1","simpleSku":"SKU1-52"}]}""");

			var snapshot = cartApi(http).read(null);

			assertThat(snapshot.readable()).isTrue();
			assertThat(snapshot.items()).hasSize(1);
			assertThat(snapshot.contains("SKU1")).isTrue();
		}

		@Test
		@DisplayName("treats an empty 204 body as a readable, empty basket")
		void emptyBodyIsEmptyCart() {
			var http = new FakePageHttpClient().enqueue(new InPageResponse(204, "", ""));

			var snapshot = cartApi(http).read(null);

			assertThat(snapshot.readable()).isTrue();
			assertThat(snapshot.items()).isEmpty();
		}

		@Test
		@DisplayName("treats a bot-wall refusal as unreadable, not empty")
		void botWallIsUnreadable() {
			var http = new FakePageHttpClient().enqueueJson(403, "Access Denied");

			var snapshot = cartApi(http).read(null);

			assertThat(snapshot.readable()).isFalse();
			assertThat(snapshot.status()).isEqualTo(403);
		}

		@Test
		@DisplayName("treats a logged-out HTML page under HTTP 200 as unreadable")
		void htmlUnderTwoHundredIsUnreadable() {
			var http = new FakePageHttpClient().enqueueJson(200, "<html><body>Please sign in</body></html>");

			var snapshot = cartApi(http).read(null);

			assertThat(snapshot.readable()).isFalse();
		}

		@Test
		@DisplayName("treats a JSON document without an items array as unreadable, not as an empty basket")
		void missingItemsArrayIsUnreadable() {
			var http = new FakePageHttpClient().enqueueJson(200, """
					{"basket":{"lines":[]}}""");

			var snapshot = cartApi(http).read(null);

			assertThat(snapshot.readable()).isFalse();
		}

		@Test
		@DisplayName("skips line items missing the keys needed to remove them")
		void skipsIncompleteItems() {
			var http = new FakePageHttpClient().enqueueJson(200, """
					{"items":[{"configSku":"SKU1"},{"cartItemKey":"k2","configSku":"SKU2","simpleSku":"SKU2-52"}]}""");

			var snapshot = cartApi(http).read(null);

			assertThat(snapshot.items()).hasSize(1);
			assertThat(snapshot.contains("SKU2")).isTrue();
		}

	}

	@Nested
	@DisplayName("gainedLineFor")
	class GainedLineFor {

		@Test
		@DisplayName("does not confirm an add when the same article was already in the basket")
		void doesNotConfirmPreexistingLine() {
			String body = """
					{"items":[{"cartItemKey":"k1","configSku":"SKU1","simpleSku":"SKU1-50"}]}""";
			var before = cartApi(new FakePageHttpClient().enqueueJson(200, body)).read(null);
			var after = cartApi(new FakePageHttpClient().enqueueJson(200, body)).read(null);

			assertThat(after.gainedLineFor("SKU1", before)).isFalse();
		}

		@Test
		@DisplayName("confirms an add when a new line for the article appeared")
		void confirmsNewLine() {
			var before = cartApi(new FakePageHttpClient().enqueueJson(200, """
					{"items":[{"cartItemKey":"k1","configSku":"SKU1","simpleSku":"SKU1-50"}]}""")).read(null);
			var after = cartApi(new FakePageHttpClient().enqueueJson(200,
					"""
							{"items":[{"cartItemKey":"k1","configSku":"SKU1","simpleSku":"SKU1-50"},{"cartItemKey":"k2","configSku":"SKU1","simpleSku":"SKU1-52"}]}"""))
				.read(null);

			assertThat(after.gainedLineFor("SKU1", before)).isTrue();
		}

	}

	@Nested
	@DisplayName("removeItem")
	class RemoveItem {

		@Test
		@DisplayName("reports failure when the shop refuses the delete")
		void reportsRefusedDelete() {
			var http = new FakePageHttpClient().enqueueJson(403, "Access Denied");

			boolean removed = cartApi(http).removeItem(null, new CartApi.CartItem("k1", "SKU1", "SKU1-52"));

			assertThat(removed).isFalse();
		}

		@Test
		@DisplayName("url-encodes the cart item key")
		void encodesCartItemKey() {
			var http = new FakePageHttpClient().enqueueJson(200, "");

			cartApi(http).removeItem(null, new CartApi.CartItem("a/b c", "SKU1", "SKU1-52"));

			assertThat(http.requestedUrls()).singleElement().asString().endsWith("/items/a%2Fb+c");
		}

	}

}
