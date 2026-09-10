package com.gfi.ozg.fitko.camunda.routing;

import org.camunda.bpm.dmn.engine.DmnDecision;
import org.camunda.bpm.dmn.engine.DmnDecisionTableResult;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration;
import org.camunda.bpm.engine.variable.VariableMap;
import org.camunda.bpm.engine.variable.Variables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@code next-step-routing.dmn}, evaluated with a standalone DMN
 * engine - no process engine, no database.
 */
class NextStepRoutingDmnTest {

    private static final String BAUANTRAG = "urn:de:fim:leika:leistung:99000000000001";
    private static final String GEWERBE = "urn:de:fim:leika:leistung:99000000000002";
    private static final String UNKNOWN = "urn:de:fim:leika:leistung:99999999999999";

    private static DmnEngine dmnEngine;
    private static DmnDecision decision;

    @BeforeAll
    static void parseDecision() {
        dmnEngine = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
        try (InputStream dmn = NextStepRoutingDmnTest.class.getResourceAsStream("/process/next-step-routing.dmn")) {
            decision = dmnEngine.parseDecision("next-step-routing", dmn);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DmnDecisionTableResult evaluate(String serviceIdentifier, boolean caseKnown) {
        VariableMap variables = Variables.createVariables()
                .putValue("serviceIdentifier", serviceIdentifier)
                .putValue("caseKnown", caseKnown);
        return dmnEngine.evaluateDecisionTable(decision, variables);
    }

    private static String step(DmnDecisionTableResult result) {
        return result.getSingleResult().getEntry("nextStep");
    }

    private static String target(DmnDecisionTableResult result) {
        return result.getSingleResult().getEntry("nextStepTarget");
    }

    @Test
    void forwardsAKnownServiceToItsFachverfahren() {
        DmnDecisionTableResult result = evaluate(BAUANTRAG, false);

        assertThat(step(result)).isEqualTo("FORWARD");
        assertThat(target(result)).isEqualTo("bauantrag-fachverfahren");
    }

    @Test
    void routesEachServiceToItsOwnTarget() {
        assertThat(target(evaluate(GEWERBE, false))).isEqualTo("gewerbe-fachverfahren");
    }

    @Test
    void archivesAnUnknownServiceOfANewCase() {
        DmnDecisionTableResult result = evaluate(UNKNOWN, false);

        assertThat(step(result)).isEqualTo("ARCHIVE");
        assertThat(target(result)).isEqualTo("");
    }

    @Test
    void sendsFollowUpsOfUnknownServicesToTheClerkQueue() {
        DmnDecisionTableResult result = evaluate(UNKNOWN, true);

        assertThat(step(result)).isEqualTo("FORWARD");
        assertThat(target(result)).isEqualTo("sachbearbeitung-followup");
    }

    @Test
    void serviceRuleWinsOverTheFollowUpRuleBecauseHitPolicyIsFirst() {
        assertThat(target(evaluate(BAUANTRAG, true))).isEqualTo("bauantrag-fachverfahren");
    }
}
