package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Decision;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ProductReservation;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.ReservationStatus;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProductReservationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
class ProductReservationPersistenceAdapter implements ProductReservationPort {

	private static final Logger log = LoggerFactory.getLogger(ProductReservationPersistenceAdapter.class);

	private final ProductReservationSpringRepository repository;

	ProductReservationPersistenceAdapter(ProductReservationSpringRepository repository) {
		this.repository = repository;
	}

	@Override
	public ProductReservation save(ProductReservation reservation) {
		return toDomain(repository.save(toEntity(reservation)));
	}

	@Override
	public Optional<ProductReservation> findById(Long id) {
		return repository.findById(id).map(this::toDomain);
	}

	@Override
	public List<ProductReservation> findByStatus(ReservationStatus status) {
		return repository.findByStatus(status.name()).stream().map(this::toDomain).toList();
	}

	@Override
	public List<ProductReservation> findByProfileId(Long profileId) {
		return repository.findByProfileId(profileId).stream().map(this::toDomain).toList();
	}

	@Override
	public List<ProductReservation> findByStatusCreatedOn(ReservationStatus status, LocalDate date) {
		return repository
			.findByStatusAndCreatedAtBetween(status.name(), date.atStartOfDay(), date.plusDays(1).atStartOfDay())
			.stream()
			.map(this::toDomain)
			.toList();
	}

	@Override
	public Map<ReservationStatus, Long> countByStatus() {
		var counts = new EnumMap<ReservationStatus, Long>(ReservationStatus.class);
		for (var status : ReservationStatus.values()) {
			counts.put(status, 0L);
		}
		for (var row : repository.countGroupedByStatus()) {
			try {
				counts.put(ReservationStatus.valueOf(row.status()), row.total());
			}
			catch (IllegalArgumentException unknownStatus) {
				log.warn("Ignoring unknown reservation status in database: {}", row.status());
			}
		}
		return counts;
	}

	@Override
	public void update(ProductReservation reservation) {
		repository.save(toEntity(reservation));
	}

	// ── Mapping ────────────────────────────────────────────────

	private ProductReservation toDomain(ProductReservationJdbcEntity e) {
		return new ProductReservation(e.id, e.productId, e.profileId, e.size, Decision.valueOf(e.decision),
				ReservationStatus.valueOf(e.status), e.score, e.telegramMsgId, e.cartAddedAt, e.cartExpiresAt,
				e.createdAt);
	}

	private ProductReservationJdbcEntity toEntity(ProductReservation r) {
		var e = new ProductReservationJdbcEntity();
		e.id = r.id();
		e.productId = r.productId();
		e.profileId = r.profileId();
		e.size = r.size();
		e.decision = r.decision().name();
		e.status = r.status().name();
		e.score = r.score();
		e.telegramMsgId = r.telegramMsgId();
		e.cartAddedAt = r.cartAddedAt();
		e.cartExpiresAt = r.cartExpiresAt();
		e.createdAt = r.createdAt();
		return e;
	}

}
