package com.gfi.ozg.ficon.processstarter;

import com.gfi.ozg.fitko.spring.receive.IncomingSubmission;

/**
 * Everything a {@link ProcessStarter} needs to start the right downstream
 * process for one Antrag: the full {@link IncomingSubmission} - payload
 * ({@code getDataAsString()}/{@code getDataAsBytes()}), <b>attachments</b>
 * ({@code getAttachments()}, not stored anywhere else - hand them to the
 * process here), metadata, applicationDate, region, service type, ... - plus
 * the tenant it was resolved for (not derivable from the submission itself,
 * see {@code com.gfi.ozg.ficon.receive.TenantDirectory}).
 *
 * <p><b>Do not call {@link IncomingSubmission#accept()}/{@link
 * IncomingSubmission#reject} from {@link ProcessStarter#start}.</b> {@code
 * AntragReceiveListener} owns resolving the submission and records it in the
 * inbox: it accepts once {@code start} returned a {@link StartedProcess}, and
 * rejects when {@code start} throws {@link ProcessStartRejectedException}.
 * Calling either directly would make the listener's own call fail with
 * {@link IllegalStateException} and leave the inbox out of step with
 * FIT-Connect.
 */
public record ProcessStartRequest(IncomingSubmission submission, String tenant) {
}
