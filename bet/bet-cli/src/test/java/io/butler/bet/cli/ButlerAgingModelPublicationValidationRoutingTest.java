package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerAgingModelPublicationValidationRoutingTest {
    @Test
    void routesPublicationValidationThroughSingleEntryPoint() {
        assertEquals(ButlerCommandRouter.Route.AGING_MODEL_PUBLICATION_VALIDATION,
            ButlerCommandRouter.route(new String[]{"aging-model", "publication-validation"}));
    }
}
