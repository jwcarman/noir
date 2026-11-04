package org.jwcarman.noir.factory;

import org.jwcarman.noir.lifecycle.State;

/** One work-item transition, as reported to a {@link Daemon.Listener}. */
public record StateChange(String itemId, State from, State to) {}
