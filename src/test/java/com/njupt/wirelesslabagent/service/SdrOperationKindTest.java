package com.njupt.wirelesslabagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdrOperationKindTest {

    @Test
    void shouldClassifyReadOnlyAndIdempotentToolsAsRetrySafe() {
        assertEquals(SdrOperationKind.READ_ONLY,
                SdrOperationKind.fromToolName("query_usrp_device_parameters"));
        assertEquals(SdrOperationKind.READ_ONLY,
                SdrOperationKind.fromToolName("perform_physical_scan"));
        assertEquals(SdrOperationKind.IDEMPOTENT_CONTROL,
                SdrOperationKind.fromToolName("stop_hardware_task"));
        assertTrue(SdrOperationKind.fromToolName("video_stream_status").retrySafe());
    }

    @Test
    void shouldTreatTransmitAndBackgroundStartAsSideEffects() {
        assertEquals(SdrOperationKind.SIDE_EFFECT,
                SdrOperationKind.fromToolName("text_fsk_send_and_receive"));
        assertEquals(SdrOperationKind.SIDE_EFFECT,
                SdrOperationKind.fromToolName("tone_loopback_visualize"));
        assertFalse(SdrOperationKind.fromToolName("adaptive_modulation_transmit").retrySafe());
    }
}
