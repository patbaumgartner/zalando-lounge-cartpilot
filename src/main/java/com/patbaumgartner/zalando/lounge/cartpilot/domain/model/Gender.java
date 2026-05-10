package com.patbaumgartner.zalando.lounge.cartpilot.domain.model;

public enum Gender {

	MEN, WOMEN, KIDS, UNISEX;

	public boolean isCompatibleWith(Gender profileGender) {
		return this == UNISEX || profileGender == UNISEX || this == profileGender;
	}

}
