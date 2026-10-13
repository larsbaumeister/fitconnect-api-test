package com.gfi.ozg.fitko.swing.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Saved compose-form settings. Also used as the compose form's working state:
 * {@code payloadTemplate} may contain placeholders that are resolved on send
 * (see {@link com.gfi.ozg.fitko.swing.service.PayloadTemplate}).
 */
public class Preset {

    public String id = UUID.randomUUID().toString();
    public String name = "";
    /** A saved Zustellpunkt, or {@code null} for an ad-hoc {@link #destinationId}. */
    public String zustellpunktId;
    public UUID destinationId;
    /** Overrides the Zustellpunkt's connection when set. */
    public String connectionId;
    public String serviceId;
    public String serviceName;
    public String serviceRegion;
    public DataFormat dataFormat = DataFormat.XML;
    public String dataSchema;
    public String payloadTemplate = "";
    public List<AttachmentSpec> attachments = new ArrayList<>();
    public String replyChannelEmail;

    public Preset copy() {
        Preset p = new Preset();
        p.id = id;
        p.name = name;
        p.zustellpunktId = zustellpunktId;
        p.destinationId = destinationId;
        p.connectionId = connectionId;
        p.serviceId = serviceId;
        p.serviceName = serviceName;
        p.serviceRegion = serviceRegion;
        p.dataFormat = dataFormat;
        p.dataSchema = dataSchema;
        p.payloadTemplate = payloadTemplate;
        p.attachments = new ArrayList<>(attachments.stream().map(AttachmentSpec::copy).toList());
        p.replyChannelEmail = replyChannelEmail;
        return p;
    }

    /**
     * The one built-in example: the Gewerbeanmeldung LeiKa key + its XZuFi
     * schema - the same defaults {@code fitko-spring-integration-tests}
     * sends with (see its {@code ITCredentials}). No Zustellpunkt is bound
     * to it.
     */
    public static Preset exampleGewerbeanmeldung() {
        Preset p = new Preset();
        p.name = "Example: Gewerbeanmeldung (XZuFi)";
        p.serviceId = "urn:de:fim:leika:leistung:99050035001000";
        p.serviceName = "Gewerbeanmeldung";
        p.dataFormat = DataFormat.XML;
        p.dataSchema = "https://fimportal.de/api/v0/leistung-steckbriefe/99050035001000/xzufi";
        p.payloadTemplate = """
                <?xml version="1.0" encoding="UTF-8"?>
                <roundTrip xmlns="https://fitko-swing-client.example/v1">
                    <marker>fitko-swing-client/${uuid}</marker>
                    <sentAt>${now}</sentAt>
                    <counter>${counter}</counter>
                </roundTrip>
                """;
        return p;
    }

    @Override
    public String toString() {
        return name;
    }
}
