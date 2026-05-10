package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;

import java.util.List;
import java.util.Optional;

/** Repository port for per-profile product reservations. */
public interface ProductReservationPort {

	ProductReservation save(ProductReservation reservation);

	Optional<ProductReservation> findById(Long id);

	List<ProductReservation> findByStatus(ReservationStatus status);

	List<ProductReservation> findByProfileId(Long profileId);

	void update(ProductReservation reservation);

}
