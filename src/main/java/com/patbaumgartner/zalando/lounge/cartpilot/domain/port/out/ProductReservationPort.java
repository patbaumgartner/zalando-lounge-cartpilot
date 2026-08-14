package com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Repository port for per-profile product reservations. */
public interface ProductReservationPort {

	ProductReservation save(ProductReservation reservation);

	Optional<ProductReservation> findById(Long id);

	List<ProductReservation> findByStatus(ReservationStatus status);

	List<ProductReservation> findByProfileId(Long profileId);

	/** Reservations in the given status created on the given date. */
	List<ProductReservation> findByStatusCreatedOn(ReservationStatus status, LocalDate date);

	/** Row count per status, without loading the rows. */
	Map<ReservationStatus, Long> countByStatus();

	void update(ProductReservation reservation);

}
