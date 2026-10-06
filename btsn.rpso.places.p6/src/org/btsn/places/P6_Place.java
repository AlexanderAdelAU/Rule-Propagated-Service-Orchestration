package org.btsn.places;

/**
 * Retained physical host placeholder.
 *
 * The generic orchestration agent uses this deployment identity in its rules.
 * Business operations and implementation classes are supplied by deployment
 * metadata and invoked through ServiceHelper. This class contains no business
 * operations, delegates or business-base inheritance.
 */
public class P6_Place {

    /** Retains the existing constructor signature for the host placeholder. */
    public P6_Place(String sequenceID) {
    }
    /**
     * Retains the legacy constructor signature for source compatibility.
     * Service capacity and delay belong to the separately deployed implementation.
     */
    public P6_Place(String sequenceID, int capacity, long processingDelayMs) {
    }
}
