package com.example.gewerbeamt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A fictional municipal trade-office ("Gewerbeamt") application, used here
 * only to demonstrate integrating the {@code fitko-spring} starter.
 *
 * <p>It deliberately switches on <em>both</em> FIT-Connect roles at once so
 * one runnable app shows both integration points:
 * <ul>
 *   <li><b>Sending</b> ("Onlinedienst"): {@link com.example.gewerbeamt.send}
 *       - a REST call builds an {@code SubmissionToSend} and hands it to the
 *       injected {@code SubmissionSender}.</li>
 *   <li><b>Receiving</b> ("Verwaltungssystem"): {@link com.example.gewerbeamt.receive}
 *       - a background poller downloads submissions and publishes them as
 *       {@code SubmissionReceivedEvent}s that {@code @SubmissionEventListener}
 *       methods handle.</li>
 * </ul>
 *
 * <p>A real application is almost always only one of the two. Turn the other
 * side off with {@code fitconnect.sender.enabled=false} /
 * {@code fitconnect.receiver.enabled=false} (see {@code application.yaml}).
 *
 * <p>There is no {@code @EnableFitConnect} or any other opt-in annotation:
 * having {@code fitko-spring} on the classpath is enough, Spring Boot
 * auto-configuration does the rest from the {@code fitconnect.*} properties.
 */
@SpringBootApplication
public class GewerbeamtApplication {

    public static void main(String[] args) {
        SpringApplication.run(GewerbeamtApplication.class, args);
    }
}
