package com.otilm.np.webhook;

import com.otilm.api.model.client.connector.InfoResponse;
import com.otilm.api.model.core.connector.EndpointDto;
import com.otilm.api.model.core.connector.FunctionGroupCode;
import com.otilm.np.webhook.api.InfoControllerImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the application so that the endpoint inventory is built the way it is at runtime. The inventory is populated
 * from a context refresh event, so it cannot be exercised without a context.
 */
@SpringBootTest
class ApplicationContextTest {

    private static final String NOTIFICATION_PROVIDER_PREFIX = "/v1/notificationProvider/";

    @Autowired
    private EndpointsListener endpointsListener;

    @Autowired
    private InfoControllerImpl infoController;

    @Test
    void notificationProviderEndpointsAreDiscovered() {
        List<EndpointDto> endpoints = endpointsListener.getEndpoints(FunctionGroupCode.NOTIFICATION_PROVIDER);

        assertFalse(endpoints.isEmpty(), "the notification provider endpoints must be discoverable");
        for (EndpointDto endpoint : endpoints) {
            assertTrue(endpoint.getContext().startsWith(NOTIFICATION_PROVIDER_PREFIX),
                    "unexpected endpoint reported for the notification provider: " + endpoint.getContext());
            assertNotNull(endpoint.getMethod());
            assertNotNull(endpoint.getName());
        }
    }

    /** The connector implements one function group only; the rest must not be advertised. */
    @Test
    void endpointsOfOtherFunctionGroupsAreNotReported() {
        assertTrue(endpointsListener.getEndpoints(FunctionGroupCode.AUTHORITY_PROVIDER).isEmpty());
        assertTrue(endpointsListener.getEndpoints(FunctionGroupCode.CRYPTOGRAPHY_PROVIDER).isEmpty());
    }

    @Test
    void supportedFunctionsAdvertiseTheWebhookKind() {
        List<InfoResponse> functions = infoController.listSupportedFunctions();

        assertEquals(1, functions.size());
        InfoResponse function = functions.get(0);
        assertEquals(FunctionGroupCode.NOTIFICATION_PROVIDER, function.getFunctionGroupCode());
        assertEquals(List.of("WEBHOOK"), function.getKinds());
        assertFalse(function.getEndPoints().isEmpty());
    }
}
