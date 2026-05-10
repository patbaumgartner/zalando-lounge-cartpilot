package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

public enum Category {

	SHOES, SHIRTS, TROUSERS, JACKETS, UNDERWEAR, SWIMWEAR, JEANS, OTHER;

	public static Category fromString(String value) {
		try {
			return Category.valueOf(value.toUpperCase());
		}
		catch (IllegalArgumentException e) {
			return OTHER;
		}
	}

}
