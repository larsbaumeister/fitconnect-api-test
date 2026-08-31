/**
 * Receiving side ("Verwaltungssystem"): a background poller publishes an
 * {@code AntragReceivedEvent} per submission; {@code @AntragEventListener}
 * methods process it and call {@code accept()} / {@code reject()}. Enabled by
 * {@code fitconnect.receiver.enabled=true} plus subscriber credentials and at
 * least one configured destination.
 */
package com.example.gewerbeamt.receive;
