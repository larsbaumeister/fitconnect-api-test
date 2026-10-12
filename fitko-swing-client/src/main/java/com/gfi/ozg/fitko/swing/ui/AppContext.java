package com.gfi.ozg.fitko.swing.ui;

import com.gfi.ozg.fitko.swing.model.Connection;
import com.gfi.ozg.fitko.swing.model.Preset;
import com.gfi.ozg.fitko.swing.model.SubmissionRecord;
import com.gfi.ozg.fitko.swing.model.Zustellpunkt;
import com.gfi.ozg.fitko.swing.service.DestinationInfoService;
import com.gfi.ozg.fitko.swing.service.SenderService;
import com.gfi.ozg.fitko.swing.service.StatusPoller;
import com.gfi.ozg.fitko.swing.store.AppData;

import java.util.UUID;

/** What the panels share: the data, the services, and navigation between tabs. */
public class AppContext {

    /** Implemented by {@link MainFrame}. */
    public interface Navigator {
        void compose(Preset form, Zustellpunkt zustellpunkt, UUID caseId);

        void showInHistory(SubmissionRecord record);

        void message(String message);
    }

    public final AppData data;
    public final SenderService sender;
    public final DestinationInfoService destinations;
    public StatusPoller poller;
    public Navigator navigator;

    public AppContext(AppData data, SenderService sender, DestinationInfoService destinations) {
        this.data = data;
        this.sender = sender;
        this.destinations = destinations;
    }

    /** The first connection, used when nothing more specific is set. */
    public Connection defaultConnection() {
        return data.connections().isEmpty() ? null : data.connections().get(0);
    }

    /** {@code overrideId}'s connection, else the Zustellpunkt's, else the default. */
    public Connection connectionFor(Zustellpunkt zustellpunkt, String overrideId) {
        if (overrideId != null) {
            Connection c = data.connection(overrideId).orElse(null);
            if (c != null) {
                return c;
            }
        }
        if (zustellpunkt != null && zustellpunkt.connectionId != null) {
            Connection c = data.connection(zustellpunkt.connectionId).orElse(null);
            if (c != null) {
                return c;
            }
        }
        return defaultConnection();
    }

    public String connectionLabel(String connectionId) {
        return data.connection(connectionId).map(Connection::toString).orElse("");
    }
}
