package com.example.ihk.processstarter;

import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;

/**
 * Everything a {@link ProcessStarter} needs to start the right downstream
 * process for one accepted Antrag: the full {@link IncomingSubmission} -
 * payload ({@code getDataAsString()}/{@code getDataAsBytes()}), attachments,
 * metadata, applicationDate, region, service type, ... - plus the tenant it
 * was resolved for (not derivable from the submission itself, see {@code
 * com.example.ihk.routing.TenantDirectory}).
 *
 * <p>Deliberately the real fitko-spring object, not a handful of copied-out
 * fields - a {@link ProcessStarter} implementation genuinely starting a
 * process needs the concrete Antrag content, and guessing upfront which
 * subset of {@link IncomingSubmission}'s accessors would be "enough" only
 * means adding fields here later as real requirements surface.
 *
 * <p><b>Do not call {@link IncomingSubmission#accept()}/{@link
 * IncomingSubmission#reject} from {@link ProcessStarter#start}.</b> {@code
 * AntragRoutingListener} still owns resolving the submission - it accepts it
 * once {@code start} returns without throwing. To reject it, throw {@link
 * ProcessStartRejectedException} with the fitting {@code Problem}(s) instead;
 * any other exception leaves it unresolved, to be retried next poll cycle.
 * Calling {@code accept()}/{@code reject()} directly would make the
 * listener's own call fail with {@link IllegalStateException}.
 */
public record ProcessStartRequest(IncomingSubmission submission, String tenant) {
}
