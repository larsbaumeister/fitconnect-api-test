package com.gfi.ozg.fitko.swing.model;

import java.util.UUID;

/** A saved destination (Zustellpunkt) and the connection used to send to it. */
public class Zustellpunkt {

    public String id = UUID.randomUUID().toString();
    public String label = "";
    public UUID destinationId;
    public String connectionId;
    public String notes;
    public String tags;
    public DestinationInfo info;

    public Zustellpunkt copy() {
        Zustellpunkt z = new Zustellpunkt();
        z.id = id;
        z.label = label;
        z.destinationId = destinationId;
        z.connectionId = connectionId;
        z.notes = notes;
        z.tags = tags;
        z.info = info;
        return z;
    }

    @Override
    public String toString() {
        return label + " — " + destinationId;
    }
}
