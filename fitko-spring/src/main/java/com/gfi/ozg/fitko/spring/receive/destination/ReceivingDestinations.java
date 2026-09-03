package com.gfi.ozg.fitko.spring.receive.destination;

import java.util.List;
import java.util.Objects;

/**
 * Every {@link ReceivingDestination} this application receives on, published
 * as one bean of a dedicated type instead of a raw {@code List<ReceivingDestination>}.
 *
 * <p>A raw {@code List<ReceivingDestination>} bean is a Spring footgun, not
 * just a theoretical one: {@code List<T>} is special-cased by Spring's
 * dependency resolution (see {@code DefaultListableBeanFactory#resolveMultipleBeans})
 * to mean "collect every bean of type {@code T}", and that branch runs
 * unconditionally - it never even considers a bean whose own type happens to
 * be {@code List<T>}. So the moment any {@code ReceivingDestination}-typed
 * bean exists anywhere in the context (a consumer's own, entirely
 * unrelated-looking {@code @Bean}), every {@code List<ReceivingDestination>}
 * injection point silently stops getting the configured destinations and
 * instead gets only that stray bean - verified experimentally: matching the
 * parameter/field name to the list bean's name does <em>not</em> prevent
 * this, since by-name matching only breaks ties between multiple candidates
 * of the same declared type and is never consulted in this branch. No error,
 * no warning, at any point.
 *
 * <p>Wrapping the list in this record sidesteps the mechanism entirely - a
 * {@code ReceivingDestinations} dependency is a plain single-bean-by-type
 * lookup, not a {@code Collection}, so a consumer's stray {@code
 * ReceivingDestination} bean simply doesn't collide with it.
 */
public record ReceivingDestinations(List<ReceivingDestination> all) {

    public ReceivingDestinations {
        all = List.copyOf(Objects.requireNonNull(all, "destinations must not be null"));
    }
}
