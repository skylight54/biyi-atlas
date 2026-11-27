package com.biyi.atlas.map;

/** Contract for the publishing service and editor. Revisions are opaque to the UI. */
public record AreaPublishReceipt(String operationId, String areaId, Outcome outcome,
                                 String currentRevision, String reason) {
    public enum Outcome { PUBLISHED, CONFLICT, FAILED }
}
