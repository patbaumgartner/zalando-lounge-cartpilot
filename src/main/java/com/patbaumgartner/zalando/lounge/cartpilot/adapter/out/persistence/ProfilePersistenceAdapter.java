package com.patbaumgartner.zalando.lounge.cartpilot.adapter.out.persistence;

import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Category;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Gender;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.model.Profile;
import com.patbaumgartner.zalando.lounge.cartpilot.domain.port.out.ProfilePort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Component
class ProfilePersistenceAdapter implements ProfilePort {

	private final ProfileSpringRepository repository;

	ProfilePersistenceAdapter(ProfileSpringRepository repository) {
		this.repository = repository;
	}

	@Override
	public List<Profile> findAllActive() {
		return repository.findAllActive().stream().map(this::toDomain).toList();
	}

	@Override
	public List<Profile> findAll() {
		return StreamSupport.stream(repository.findAll().spliterator(), false).map(this::toDomain).toList();
	}

	@Override
	public Optional<Profile> findById(Long id) {
		return id == null ? Optional.empty() : repository.findById(id).map(this::toDomain);
	}

	@Override
	public Optional<Profile> findByName(String name) {
		return repository.findByName(name).map(this::toDomain);
	}

	@Override
	public Profile save(Profile profile) {
		var entity = toEntity(profile);
		// Every save rebuilt the entity from the domain object, which carries no
		// created_at, so an unconditional now() silently reset the creation date on
		// every profile edit.
		entity.createdAt = existingCreatedAt(profile.id()).orElseGet(LocalDateTime::now);
		var saved = repository.save(entity);
		return toDomain(saved);
	}

	private Optional<LocalDateTime> existingCreatedAt(Long id) {
		return id == null ? Optional.empty() : repository.findById(id).map(existing -> existing.createdAt);
	}

	// ── Mapping ────────────────────────────────────────────────

	private Profile toDomain(ProfileJdbcEntity e) {
		var sizes = new EnumMap<Category, String>(Category.class);
		for (var sizeEntity : e.sizes) {
			sizes.put(Category.fromString(sizeEntity.category), sizeEntity.size);
		}
		return new Profile(e.id, e.name, Gender.valueOf(e.gender), e.active, sizes, splitCsv(e.brandTier1),
				splitCsv(e.brandTier2), parseBrandAliases(e.brandAliases), e.maxPriceShoes, e.maxPriceJackets,
				e.maxPriceClothing);
	}

	private ProfileJdbcEntity toEntity(Profile p) {
		var entity = new ProfileJdbcEntity();
		entity.id = p.id();
		entity.name = p.name();
		entity.gender = p.gender().name();
		entity.active = p.active();
		entity.maxPriceShoes = p.maxPriceShoes();
		entity.maxPriceJackets = p.maxPriceJackets();
		entity.maxPriceClothing = p.maxPriceClothing();
		entity.brandTier1 = String.join(",", p.brandTier1());
		entity.brandTier2 = String.join(",", p.brandTier2());
		entity.brandAliases = formatBrandAliases(p.brandAliases());

		var sizeEntities = new LinkedHashSet<ProfileSizeJdbcEntity>();
		p.sizes().forEach((cat, sz) -> {
			var se = new ProfileSizeJdbcEntity();
			se.category = cat.name();
			se.size = sz;
			sizeEntities.add(se);
		});
		entity.sizes = sizeEntities;
		return entity;
	}

	private List<String> splitCsv(String csv) {
		if (csv == null || csv.isBlank()) {
			return List.of();
		}
		return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	private Map<String, String> parseBrandAliases(String raw) {
		var map = new HashMap<String, String>();
		if (raw == null || raw.isBlank()) {
			return map;
		}
		for (String entry : raw.split(",")) {
			var parts = entry.split("=", 2);
			if (parts.length == 2) {
				map.put(parts[0].trim(), parts[1].trim());
			}
		}
		return map;
	}

	private String formatBrandAliases(Map<String, String> aliases) {
		if (aliases.isEmpty()) {
			return null;
		}
		var sb = new StringBuilder();
		aliases.forEach((k, v) -> {
			if (!sb.isEmpty()) {
				sb.append(',');
			}
			sb.append(k).append('=').append(v);
		});
		return sb.toString();
	}

}
