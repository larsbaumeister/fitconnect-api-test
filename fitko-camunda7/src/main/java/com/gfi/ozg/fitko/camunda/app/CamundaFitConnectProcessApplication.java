package com.gfi.ozg.fitko.camunda.app;

import org.camunda.bpm.application.ProcessApplication;
import org.camunda.bpm.application.impl.JakartaServletProcessApplication;

/**
 * The process application.
 *
 * <p>A plain {@link JakartaServletProcessApplication} (no Spring): on a Camunda
 * Platform 7 WildFly distribution the {@code camunda} subsystem discovers this
 * {@code @ProcessApplication}-annotated class together with {@code
 * META-INF/processes.xml}, deploys the BPMN/DMN under {@code
 * WEB-INF/classes/process/} to the shared engine on startup and removes them
 * on undeploy, and wires the deployment's CDI bean manager as the expression
 * resolver - so {@code ${pollSubmissionsDelegate}} and the timer start event's
 * {@code ${pollScheduleConfig.pollCycle}} resolve to the {@code @Named} beans
 * in this WAR.
 *
 * <p>Deploy the built {@code fitko-fitconnect-camunda7.war} onto a Camunda 7
 * WildFly distribution whose shared engine is named {@code default}.
 */
@ProcessApplication("FIT-Connect Camunda 7 Poller")
public class CamundaFitConnectProcessApplication extends JakartaServletProcessApplication {
}
