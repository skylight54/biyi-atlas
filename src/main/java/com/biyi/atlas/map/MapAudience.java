package com.biyi.atlas.map;

/** Requested view of the atlas. Explicit author commands still require server permission. */
public enum MapAudience {
    PLAYER, EDITOR;

    public static MapAudience forMapKey(boolean creative, boolean authorizationKnown, boolean canEdit) {
        return creative && authorizationKnown && canEdit ? EDITOR : PLAYER;
    }
}
